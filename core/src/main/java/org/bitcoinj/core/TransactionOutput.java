/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.VarInt;
import org.bitcoinj.base.internal.Buffers;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.script.ScriptPattern;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.bitcoinj.base.internal.Preconditions.checkArgument;
import static org.bitcoinj.base.internal.Preconditions.checkState;

/**
 * <p>A TransactionOutput message contains a scriptPubKey that controls who is able to spend its value. It is a sub-part
 * of the Transaction message.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class TransactionOutput {
    private static final Logger log = LoggerFactory.getLogger(TransactionOutput.class);

    @Nullable protected Transaction parent;

    // The output's value is kept as a native type in order to save class instances.
    private final long value;

    // A transaction output has a script used for authenticating that the redeemer is allowed to spend
    // this output.
    private final byte[] scriptBytes;

    // The script bytes are parsed and turned into a Script on demand.
    private Script scriptPubKey;

    // These fields are not Bitcoin serialized. They are used for tracking purposes in our wallet
    // only. If set to true, this output is counted towards our balance. If false and spentBy is null the tx output
    // was owned by us and was sent to somebody else. If false and spentBy is set it means this output was owned by
    // us and used in one of our own transactions (eg, because it is a change output).
    private boolean availableForSpending;
    @Nullable private TransactionInput spentBy;

    /**
     * Deserialize this transaction output from a given payload.
     *
     * @param payload           payload to deserialize from
     * @param parentTransaction parent transaction of the output
     * @return read message
     * @throws BufferUnderflowException if the read message extends beyond the remaining bytes of the payload
     */
    public static TransactionOutput read(ByteBuffer payload, Transaction parentTransaction) throws BufferUnderflowException, ProtocolException {
        Objects.requireNonNull(parentTransaction);
        Coin value = Coin.read(payload);
        byte[] scriptBytes = Buffers.readLengthPrefixedBytes(payload);
        return new TransactionOutput(parentTransaction, value, scriptBytes);
    }

    /**
     * Creates an output that sends 'value' to the given address (public key hash). The amount should be created with
     * something like {@link Coin#valueOf(int, int)}. Typically you would use
     * {@link Transaction#addOutput(Coin, Address)} instead of creating a TransactionOutput directly.
     */
    public TransactionOutput(@Nullable Transaction parent, Coin value, Address to) {
        this(parent, value, ScriptBuilder.createOutputScript(to).program());
    }

    /**
     * Creates an output that sends 'value' to the given public key using a simple CHECKSIG script (no addresses). The
     * amount should be created with something like {@link Coin#valueOf(int, int)}. Typically you would use
     * {@link Transaction#addOutput(Coin, ECKey)} instead of creating an output directly.
     */
    public TransactionOutput(@Nullable Transaction parent, Coin value, ECKey to) {
        this(parent, value, ScriptBuilder.createP2PKOutputScript(to).program());
    }

    public TransactionOutput(@Nullable Transaction parent, Coin value, byte[] scriptBytes) {
        super();
        // Negative values obviously make no sense, except for -1 which is used as a sentinel value when calculating
        // SIGHASH_SINGLE signatures, so unfortunately we have to allow that here.
        checkArgument(value.signum() >= 0 || value.equals(Coin.NEGATIVE_SATOSHI), () ->
                "negative values not allowed");
        Objects.requireNonNull(scriptBytes);
        this.value = value.value;
        this.scriptBytes = scriptBytes;
        setParent(parent);
        availableForSpending = true;
    }

    public Script getScriptPubKey() throws ScriptException {
        if (scriptPubKey == null) {
            scriptPubKey = Script.parse(scriptBytes);
        }
        return scriptPubKey;
    }

    /**
     * Write this transaction output into the given buffer.
     *
     * @param buf buffer to write into
     * @return the buffer
     * @throws BufferOverflowException if the output doesn't fit the remaining buffer
     */
    public ByteBuffer write(ByteBuffer buf) throws BufferOverflowException {
        Coin.valueOf(value).write(buf);
        Buffers.writeLengthPrefixedBytes(buf, scriptBytes);
        return buf;
    }

    /**
     * Allocates a byte array and writes this transaction output into it.
     *
     * @return byte array containing the transaction output
     */
    public byte[] serialize() {
        return write(ByteBuffer.allocate(messageSize())).array();
    }

    /**
     * Return the size of the serialized message. Note that if the message was deserialized from a payload, this
     * size can differ from the size of the original payload.
     *
     * @return size of the serialized message in bytes
     */
    public int messageSize() {
        return Coin.BYTES + // value
                Buffers.lengthPrefixedBytesSize(scriptBytes);
    }

    /**
     * Returns the value of this output. This is the amount of currency that the destination address
     * receives.
     */
    public Coin getValue() {
        return Coin.valueOf(value);
    }

    /**
     * Returns a clone of this output, with given value. The typical use case is fee calculation.
     *
     * @param value value for the clone
     * @return clone of output, with given value
     */
    public TransactionOutput withValue(Coin value) {
        Objects.requireNonNull(value);
        return new TransactionOutput(this.parent, value, this.scriptBytes);
    }

    /**
     * Gets the index of this output in the parent transaction, or throws if this output is freestanding. Iterates
     * over the parents list to discover this.
     */
    public int getIndex() {
        List<TransactionOutput> outputs = getParentTransaction().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i) == this)
                return i;
        }
        throw new IllegalStateException("Output linked to wrong parent transaction?");
    }

    /**
     * Will this transaction be considered dust and not be relayable and mined by default miners?
     * @return true if this output is dust
     */
    public boolean isDust() {
        // Transactions that are OP_RETURN can't be dust regardless of their value.
        // If output is not OP_RETURN and value is below getMinNonDustValue() it is dust.
        return !ScriptPattern.isOpReturn(getScriptPubKey()) && getValue().isLessThan(getMinNonDustValue());
    }

    /**
     * <p>Gets the minimum value for a txout of this size to be considered non-dust by Bitcoin Core
     * (and thus relayed). See: CTxOut::IsDust() in Bitcoin Core.</p>
     *
     * <p>You probably should use {@link TransactionOutput#getMinNonDustValue()} which uses
     * a safe fee-per-kb by default.</p>
     *
     * @param feePerKb The fee required per kilobyte. Note that this is the same as Bitcoin Core's -minrelaytxfee * 3
     */
    public Coin getMinNonDustValue(Coin feePerKb) {
        // "Dust" is defined in terms of dustRelayFee,
        // which has units satoshis-per-kilobyte.
        // If you'd pay more in fees than the value of the output
        // to spend something, then we consider it dust.
        // A typical spendable non-segwit txout is 34 bytes big, and will
        // need a CTxIn of at least 148 bytes to spend:
        // so dust is a spendable txout less than
        // 182*dustRelayFee/1000 (in satoshis).
        // 546 satoshis at the default rate of 3000 sat/kB.
        // A typical spendable segwit txout is 31 bytes big, and will
        // need a CTxIn of at least 67 bytes to spend:
        // so dust is a spendable txout less than
        // 98*dustRelayFee/1000 (in satoshis).
        // 294 satoshis at the default rate of 3000 sat/kB.
        long size = this.messageSize();
        final Script script = getScriptPubKey();
        if (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2PK(script) || ScriptPattern.isP2SH(script) || ScriptPattern.isSentToMultisig(script))
            size += 32 + 4 + 1 + 107 + 4; // 148
        else if (ScriptPattern.isP2WH(script))
            size += 32 + 4 + 1 + (107 / 4) + 4; // 68
        else
            return Coin.ZERO;
        return feePerKb.multiply(size).divide(1000);
    }

    /**
     * Returns the minimum value for this output to be considered "not dust", i.e. the transaction will be relayable
     * and mined by default miners.
     */
    public Coin getMinNonDustValue() {
        return getMinNonDustValue(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.multiply(3));
    }

    /**
     * Sets this objects availableForSpending flag to false and the spentBy pointer to the given input.
     * If the input is null, it means this output was signed over to somebody else rather than one of our own keys.
     * @throws IllegalStateException if the transaction was already marked as spent.
     */
    public void markAsSpent(TransactionInput input) {
        checkState(availableForSpending);
        availableForSpending = false;
        spentBy = input;
        if (parent != null)
            if (log.isDebugEnabled()) log.debug("Marked {}:{} as spent by {}", getParentTransactionHash(), getIndex(), input);
        else
            if (log.isDebugEnabled()) log.debug("Marked floating output as spent by {}", input);
    }

    /**
     * Resets the spent pointer / availableForSpending flag to null.
     */
    public void markAsUnspent() {
        if (parent != null)
            if (log.isDebugEnabled()) log.debug("Un-marked {}:{} as spent by {}", getParentTransactionHash(), getIndex(), spentBy);
        else
            if (log.isDebugEnabled()) log.debug("Un-marked floating output as spent by {}", spentBy);
        availableForSpending = true;
        spentBy = null;
    }

    /**
     * Returns whether {@link TransactionOutput#markAsSpent(TransactionInput)} has been called on this class. A
     * {@link Wallet} will mark a transaction output as spent once it sees a transaction input that is connected to it.
     * Note that this flag can be false when an output has in fact been spent according to the rest of the network if
     * the spending transaction wasn't downloaded yet, and it can be marked as spent when in reality the rest of the
     * network believes it to be unspent if the signature or script connecting to it was not actually valid.
     */
    public boolean isAvailableForSpending() {
        return availableForSpending;
    }

    /**
     * The backing script bytes which can be turned into a Script object.
     * @return the scriptBytes
    */
    public byte[] getScriptBytes() {
        return Arrays.copyOf(scriptBytes, scriptBytes.length);
    }

    /**
     * Returns true if this output is to a key in the wallet or to an address/script we are watching.
     */
    public boolean isMineOrWatched(TransactionBag transactionBag) {
        return isMine(transactionBag) || isWatched(transactionBag);
    }

    /**
     * Returns true if this output is to a key, or an address we have the keys for, in the wallet.
     */
    public boolean isWatched(TransactionBag transactionBag) {
        try {
            Script script = getScriptPubKey();
            return transactionBag.isWatchedScript(script);
        } catch (ScriptException e) {
            // Just means we didn't understand the output of this transaction: ignore it.
            log.debug("Could not parse tx output script: {}", e.toString());
            return false;
        }
    }

    /**
     * Returns true if this output is to a key, or an address we have the keys for, in the wallet.
     */
    public boolean isMine(TransactionBag transactionBag) {
        try {
            Script script = getScriptPubKey();
            if (ScriptPattern.isP2PK(script))
                return transactionBag.isPubKeyMine(ScriptPattern.extractKeyFromP2PK(script));
            else if (ScriptPattern.isP2SH(script))
                return transactionBag.isPayToScriptHashMine(ScriptPattern.extractHashFromP2SH(script));
            else if (ScriptPattern.isP2PKH(script))
                return transactionBag.isPubKeyHashMine(ScriptPattern.extractHashFromP2PKH(script),
                        ScriptType.P2PKH);
            else if (ScriptPattern.isP2WPKH(script))
                return transactionBag.isPubKeyHashMine(ScriptPattern.extractHashFromP2WH(script),
                        ScriptType.P2WPKH);
            else
                return false;
        } catch (ScriptException e) {
            // Just means we didn't understand the output of this transaction: ignore it.
            log.debug("Could not parse tx {} output script: {}",
                    parent != null ? ((Transaction) parent).getTxId() : "(no parent)", e.toString());
            return false;
        }
    }

    /**
     * Returns a human-readable debug string.
     * @return debug string
     */
    @Override
    public String toString() {
        return toString(null);
    }

    /**
     * Returns a human-readable debug string.
     * @param network if provided, addresses (of that network) will be printed for standard scripts
     * @return debug string
     */
    public String toString(@Nullable Network network) {
        StringBuilder buf = new StringBuilder("TxOut of ");
        buf.append(Coin.valueOf(value).toFriendlyString());
        try {
            Script script = getScriptPubKey();
            if (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2WPKH(script) || ScriptPattern.isP2TR(script)
                    || ScriptPattern.isP2SH(script)) {
                buf.append(" to ").append(script.getScriptType().name());
                if (network != null)
                    buf.append(" ").append(script.getToAddress(network));
            } else if (ScriptPattern.isP2PK(script)) {
                buf.append(" to pubkey ").append(ByteUtils.formatHex(ScriptPattern.extractKeyFromP2PK(script)));
            } else if (ScriptPattern.isSentToMultisig(script)) {
                buf.append(" to multisig");
            } else {
                buf.append(" (unknown type)");
            }
            buf.append(" script:").append(script);
        } catch (ScriptException e) {
            buf.append(" [exception: ").append(e.getMessage()).append("]");
        }
        return buf.toString();
    }

    /**
     * Returns the connected input.
     */
    @Nullable
    public TransactionInput getSpentBy() {
        return spentBy;
    }

    /**
     * Returns the transaction that owns this output.
     */
    @Nullable
    public Transaction getParentTransaction() {
        return parent;
    }

    /**
     * Returns the transaction hash that owns this output.
     */
    @Nullable
    public Sha256Hash getParentTransactionHash() {
        return parent == null ? null : ((Transaction) parent).getTxId();
    }

    /**
     * Returns the depth in blocks of the parent tx.
     *
     * <p>If the transaction appears in the top block, the depth is one. If it's anything else (pending, dead, unknown)
     * then -1.</p>
     * @return The tx depth or -1.
     */
    public int getParentTransactionDepthInBlocks() {
        if (getParentTransaction() != null) {
            TransactionConfidence confidence = getParentTransaction().getConfidence();
            if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
                return confidence.getDepthInBlocks();
            }
        }
        return -1;
    }

    /**
     * Returns a new {@link TransactionOutPoint}, which is essentially a structure pointing to this output.
     * Requires that this output is not detached.
     */
    public TransactionOutPoint getOutPointFor() {
        return TransactionOutPoint.from(getParentTransaction(), getIndex());
    }

    /** Returns a copy of the output detached from its containing transaction, if need be. */
    public TransactionOutput duplicateDetached() {
        return new TransactionOutput(null, Coin.valueOf(value), Arrays.copyOf(scriptBytes, scriptBytes.length));
    }

    protected final void setParent(@Nullable Transaction parent) {
        this.parent = parent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionOutput other = (TransactionOutput) o;
        return value == other.value && (parent == null || (parent.equals(other.parent) && getIndex() == other.getIndex()))
                && Arrays.equals(scriptBytes, other.scriptBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, parent, Arrays.hashCode(scriptBytes));
    }
}
