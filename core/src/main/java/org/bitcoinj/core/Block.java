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

import com.google.common.annotations.VisibleForTesting;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.VarInt;
import org.bitcoinj.base.internal.Buffers;
import org.bitcoinj.base.internal.StreamUtils;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.base.internal.InternalUtils;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.bitcoinj.base.Coin.FIFTY_COINS;
import static org.bitcoinj.base.Sha256Hash.hashTwice;
import static org.bitcoinj.base.internal.Preconditions.check;
import static org.bitcoinj.base.internal.Preconditions.checkState;

/**
 * <p>A block is a group of transactions, and is one of the fundamental data structures of the Bitcoin system.
 * It records a set of {@link Transaction}s together with some data that links it into a place in the global block
 * chain, and proves that a difficult calculation was done over its contents. See
 * <a href="http://www.bitcoin.org/bitcoin.pdf">the Bitcoin technical paper</a> for
 * more detail on blocks.</p>
 *
 * <p>To get a block, you can either build one from the raw bytes you can get from another implementation, or request one
 * specifically using {@link Peer#getBlock(Sha256Hash)}, or grab one from a downloaded {@link BlockChain}.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class Block implements Message {
    /**
     * Flags used to control which elements of block validation are done on
     * received blocks.
     */
    public enum VerifyFlag {
        /** Check that block height is in coinbase transaction (BIP 34). */
        HEIGHT_IN_COINBASE
    }

    private static final Logger log = LoggerFactory.getLogger(Block.class);

    /** How many bytes are required to represent a block header WITHOUT the trailing 00 length byte. */
    public static final int HEADER_SIZE = 80;

    static final Duration ALLOWED_TIME_DRIFT = Duration.ofHours(2); // Same value as Bitcoin Core.

    /**
     * A constant shared by the entire network: how large in bytes a block is allowed to be. One day we may have to
     * upgrade everyone to change this, so Bitcoin can continue to grow. For now it exists as an anti-DoS measure to
     * avoid somebody creating a titanically huge but valid block and forcing everyone to download/store it forever.
     */
    public static final int MAX_BLOCK_SIZE = 1_000_000;
    /**
     * A "sigop" is a signature verification operation. Because they're expensive we also impose a separate limit on
     * the number in a block to prevent somebody mining a huge block that has way more sigops than normal, so is very
     * expensive/slow to verify.
     */
    public static final int MAX_BLOCK_SIGOPS = MAX_BLOCK_SIZE / 50;

    /** @deprecated use {@link Difficulty#STANDARD_MAX_DIFFICULTY_TARGET} */
    @Deprecated
    public static final long STANDARD_MAX_DIFFICULTY_TARGET = Difficulty.STANDARD_MAX_DIFFICULTY_TARGET.compact();
    /** @deprecated use {@link Difficulty#EASIEST_DIFFICULTY_TARGET} */
    @Deprecated
    public static final long EASIEST_DIFFICULTY_TARGET = Difficulty.EASIEST_DIFFICULTY_TARGET.compact();

    /** Value to use if the block height is unknown */
    public static final int BLOCK_HEIGHT_UNKNOWN = -1;
    /** Height of the first block */
    public static final int BLOCK_HEIGHT_GENESIS = 0;

    public static final long BLOCK_VERSION_GENESIS = 1;
    /** Block version introduced in BIP 34: Height in coinbase */
    public static final long BLOCK_VERSION_BIP34 = 2;
    /** Block version introduced in BIP 66: Strict DER signatures */
    public static final long BLOCK_VERSION_BIP66 = 3;
    /** Block version introduced in BIP 65: OP_CHECKLOCKTIMEVERIFY */
    public static final long BLOCK_VERSION_BIP65 = 4;

    // Fields defined as part of the protocol format.
    private final long version;
    private final Sha256Hash prevHash; // previous block
    private Sha256Hash merkleRoot, witnessRoot;
    private Instant time;
    private Difficulty difficultyTarget; // "nBits"
    private long nonce;

    // If null, it means this object holds only the headers.
    @Nullable
    private final List<Transaction> transactions;

    /** Stores the hash of the block. If null, getHash() will recalculate it. */
    private Sha256Hash hash;

    /**
     * Deserialize this message from a given payload.
     *
     * @param payload payload to deserialize from
     * @return read message
     * @throws BufferUnderflowException if the read message extends beyond the remaining bytes of the payload
     */
    public static Block read(ByteBuffer payload) throws BufferUnderflowException, ProtocolException {
        // header
        payload.mark();
        long version = ByteUtils.readUint32(payload);
        Sha256Hash prevHash = Sha256Hash.read(payload);
        Sha256Hash merkleRoot = Sha256Hash.read(payload);
        Instant time = Instant.ofEpochSecond(ByteUtils.readUint32(payload));
        Difficulty difficultyTarget = Difficulty.ofCompact(ByteUtils.readUint32(payload));
        long nonce = ByteUtils.readUint32(payload);
        payload.reset(); // read again from the mark for the hash
        Sha256Hash hash = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(Buffers.readBytes(payload, HEADER_SIZE)));
        // transactions
        List<Transaction> transactions = payload.hasRemaining() ? // otherwise this message is just a header
                readTransactions(payload) :
                null;
        Block block = new Block(version, prevHash, merkleRoot, time, difficultyTarget, nonce, transactions);
        block.hash = hash;
        return block;
    }

    /**
     * Read transactions from a block message.
     * @param payload Contains the block message being read
     * @return An unmodifiable list of transactions
     * @throws BufferUnderflowException if end-of-buffer reached before a complete, valid message could be read
     * @throws ProtocolException if the message is not compliant with the protocol
     */
    private static List<Transaction> readTransactions(ByteBuffer payload) throws BufferUnderflowException,
            ProtocolException {
        VarInt numTransactions = VarInt.read(payload);
        check(numTransactions.fitsInt(), BufferUnderflowException::new);
        return IntStream.range(0, numTransactions.intValue())
                .mapToObj(i -> Transaction.read(payload))
                .collect(StreamUtils.toUnmodifiableList());
    }

    /** Special case constructor, used for unit tests. */
    // For testing only
    Block(long setVersion) {
        this(setVersion,
                Sha256Hash.ZERO_HASH); // prev block
    }

    /** Special case constructor, used for unit tests. */
    // For testing only
    Block(long setVersion, Sha256Hash prevHash) {
        this(setVersion,
                prevHash,
                Sha256Hash.ZERO_HASH, // merkle root
                TimeUtils.currentTime().truncatedTo(ChronoUnit.SECONDS), // convert to Bitcoin time
                Difficulty.ofCompact(0x1d07fff8L),
                0,
                Collections.emptyList());
    }

    // For unit-test genesis blocks
    // For testing only
    Block(long setVersion, Instant time, Difficulty difficultyTarget, List<Transaction> transactions) {
        this(setVersion, time, difficultyTarget, 0, transactions);
        // Solve for nonce?
    }

    // For genesis blocks (and also unit tests)
    Block(long setVersion, Instant time, Difficulty difficultyTarget, long nonce, List<Transaction> transactions) {
        this.version = setVersion;
        this.time = time;
        this.difficultyTarget = difficultyTarget;
        this.nonce = nonce;
        this.prevHash = Sha256Hash.ZERO_HASH;
        this.transactions = new ArrayList<>(Objects.requireNonNull(transactions));
    }

    /**
     * Construct a block initialized with all the given fields.
     * @param version This should usually be set to 1 or 2, depending on if the height is in the coinbase input.
     * @param prevHash Reference to previous block in the chain or {@link Sha256Hash#ZERO_HASH} if genesis.
     * @param merkleRoot The root of the merkle tree formed by the transactions.
     * @param time time when the block was mined.
     * @param difficultyTarget Number which this block hashes lower than.
     * @param nonce Arbitrary number to make the block hash lower than the target.
     * @param transactions List of transactions including the coinbase, or {@code null} for header-only blocks
     */
    public Block(long version, Sha256Hash prevHash, Sha256Hash merkleRoot, Instant time,
                 Difficulty difficultyTarget, long nonce, @Nullable List<Transaction> transactions) {
        super();
        this.version = version;
        this.prevHash = prevHash;
        this.merkleRoot = merkleRoot;
        this.time = time;
        this.difficultyTarget = difficultyTarget;
        this.nonce = nonce;
        this.transactions = transactions != null ?
                new ArrayList<>(transactions) :
                null;
    }

    /** @deprecated use {@link #Block(long, Sha256Hash, Sha256Hash, Instant, Difficulty, long, List)} */
    @Deprecated
    public Block(long version, Sha256Hash prevHash, Sha256Hash merkleRoot, Instant time,
                 long difficultyTarget, long nonce, @Nullable List<Transaction> transactions) {
        this(version, prevHash, merkleRoot, time, Difficulty.ofCompact(difficultyTarget), nonce, transactions);
    }

    public static Block createGenesis(Instant time, Difficulty difficultyTarget) {
        return new Block(BLOCK_VERSION_GENESIS, time, difficultyTarget, genesisTransactions());
    }

    /** @deprecated use {@link #createGenesis(Instant, Difficulty)} */
    @Deprecated
    public static Block createGenesis(Instant time, long difficultyTarget) {
        return createGenesis(time, Difficulty.ofCompact(difficultyTarget));
    }

    public static Block createGenesis(Instant time, Difficulty difficultyTarget, long nonce) {
        return new Block(BLOCK_VERSION_GENESIS, time, difficultyTarget, nonce, genesisTransactions());
    }

    /** @deprecated use {@link #createGenesis(Instant, Difficulty, long)} */
    @Deprecated
    public static Block createGenesis(Instant time, long difficultyTarget, long nonce) {
        return createGenesis(time, Difficulty.ofCompact(difficultyTarget), nonce);
    }

    private static List<Transaction> genesisTransactions() {
        byte[] messageBytes = GENESIS_MESSAGE.getBytes(StandardCharsets.US_ASCII);
        Script scriptSig = // TODO find out what the pushdata(4) is supposed to mean
                new ScriptBuilder().bigNum(STANDARD_MAX_DIFFICULTY_TARGET).bigNum(4).data(messageBytes).build();
        Transaction tx = Transaction.coinbase(scriptSig.program());
        tx.addOutput(new TransactionOutput(
                tx, FIFTY_COINS, ScriptBuilder.createP2PKOutputScript(GENESIS_OUTPUT_PUBKEY).program()));
        return Collections.singletonList(tx);
    }

    private static final String GENESIS_MESSAGE =
            "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks";
    private static final byte[] GENESIS_OUTPUT_PUBKEY = ByteUtils.parseHex(
            "04678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5f");

    @Override
    public int messageSize() {
        return isHeaderOnly()
                ? HEADER_SIZE
                : HEADER_SIZE
                    + VarInt.sizeOf(transactions.size())
                    + transactions.stream()
                        .mapToInt(Transaction::messageSize)
                        .sum();
    }

    private void writeHeader(ByteBuffer buf) throws BufferOverflowException {
        ByteUtils.writeInt32LE(version, buf);
        prevHash.write(buf);
        getMerkleRoot().write(buf);
        ByteUtils.writeInt32LE(time.getEpochSecond(), buf);
        ByteUtils.writeInt32LE(difficultyTarget.compact(), buf);
        ByteUtils.writeInt32LE(nonce, buf);
    }

    private void writeTransactions(ByteBuffer buf) throws BufferOverflowException {
        checkState(!isHeaderOnly());
        VarInt.of(transactions.size()).write(buf);
        for (Transaction tx : transactions) {
            tx.write(buf);
        }
    }

    @Override
    public ByteBuffer write(ByteBuffer buf) throws BufferOverflowException {
        writeHeader(buf);
        if (!isHeaderOnly())
            writeTransactions(buf);
        return buf;
    }

    protected void unCache() {
        // Since we have alternate uncache methods to use internally this will only ever be called by a child
        // transaction so we only need to invalidate that part of the cache.
        unCacheTransactions();
    }

    private void unCacheHeader() {
        hash = null;
    }

    private void unCacheTransactions() {
        // Current implementation has to uncache headers as well as any change to a tx will alter the merkle root. In
        // future we can go more granular and cache merkle root separately so rest of the header does not need to be
        // rewritten.
        unCacheHeader();
        // Clear merkleRoot last as it may end up being parsed during unCacheHeader().
        merkleRoot = null;
    }

    /**
     * Calculates the block hash by serializing the block and hashing the
     * resulting bytes.
     */
    private Sha256Hash calculateHash() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        writeHeader(buf);
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(buf.array()));
    }

    /**
     * Returns the hash of the block (which for a valid, solved block should be below the target) in the form seen on
     * the block explorer. If you call this on block 1 in the mainnet chain
     * you will get "00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048".
     */
    public String getHashAsString() {
        return getHash().toString();
    }

    /**
     * Returns the hash of the block (which for a valid, solved block should be
     * below the target). Big endian.
     */
    public Sha256Hash getHash() {
        if (hash == null)
            hash = calculateHash();
        return hash;
    }

    /**
     * The number that is one greater than the largest representable SHA-256
     * hash.
     */
    private static BigInteger LARGEST_HASH = BigInteger.ONE.shiftLeft(256);

    /**
     * Returns the work represented by this block.<p>
     *
     * Work is defined as the number of tries needed to solve a block in the
     * average case. Consider a difficulty target that covers 5% of all possible
     * hash values. Then the work of the block will be 20. As the target gets
     * lower, the amount of work goes up.
     */
    public BigInteger getWork() throws VerificationException {
        BigInteger target = difficultyTarget.asInteger();
        return LARGEST_HASH.divide(target.add(BigInteger.ONE));
    }

    /**
     * Returns a copy of just the block header.
     *
     * @return new, header-only {@code Block}
     */
    public Block asHeader() {
        Block block = new Block(version, prevHash, getMerkleRoot(), time, difficultyTarget, nonce, null);
        block.hash = getHash();
        return block;
    }

    /** @deprecated use {@link #asHeader()} */
    @Deprecated
    public Block cloneAsHeader() {
        return asHeader();
    }

    /**
     * Returns a multi-line string containing a description of the contents of
     * the block. Use for debugging purposes only.
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(" block" + (isHeaderOnly() ? " (header-only)" : "") + ": \n");
        s.append("   hash: ").append(getHashAsString()).append('\n');
        s.append("   version: ").append(version);
        String bips = InternalUtils.commaJoin(isBIP34() ? "BIP34" : null, isBIP66() ? "BIP66" : null, isBIP65() ? "BIP65" : null);
        if (!bips.isEmpty())
            s.append(" (").append(bips).append(')');
        s.append('\n');
        s.append("   previous block: ").append(prevHash()).append("\n");
        s.append("   time: ").append(time).append(" (").append(TimeUtils.dateTimeFormat(time)).append(")\n");
        s.append("   difficulty target (nBits): ").append(difficultyTarget).append("\n");
        s.append("   nonce: ").append(nonce).append("\n");
        if (hasTransactions()) {
            s.append("   merkle root: ").append(getMerkleRoot()).append("\n");
            s.append("   witness root: ").append(getWitnessRoot()).append("\n");
            s.append("   with ").append(transactions.size()).append(" transaction(s):\n");
            for (Transaction tx : transactions) {
                s.append(tx).append('\n');
            }
        }
        return s.toString();
    }

    /** @deprecated use {@link #difficultyTarget()} then {@link Difficulty#asInteger()} */
    @Deprecated
    public BigInteger getDifficultyTargetAsInteger() {
        return difficultyTarget.asInteger();
    }

    /**
     * Checks the proof of work of this block. If this method returns without throwing, the hash of this block is OK
     * (lower than difficulty target).
     *
     * @throws VerificationException if there is not enough proof of work
     */
    private void checkProofOfWork() throws VerificationException {
        // shortcut for unit-testing
        if (Context.get().isRelaxProofOfWork())
            return;

        // This part is key - it is what proves the block was as difficult to make as it claims
        // to be. Note however that in the context of this function, the block can claim to be
        // as difficult as it wants to be .... if somebody was able to take control of our network
        // connection and fork us onto a different chain, they could send us valid blocks with
        // ridiculously easy difficulty and this function would accept them.
        //
        // To prevent this attack from being possible, elsewhere we check that the difficultyTarget
        // field is of the right value. This requires us to have the preceding blocks.
        if (!difficultyTarget.isMetByWork(getHash()))
            // Proof of work check failed!
            throw new VerificationException("Hash is higher than target: " + getHashAsString() + " vs "
                    + difficultyTarget.toIntegerString());
    }

    private void checkTimestamp() throws VerificationException {
        final Instant allowedTime = TimeUtils.currentTime().truncatedTo(ChronoUnit.SECONDS).plus(ALLOWED_TIME_DRIFT);
        if (time.isAfter(allowedTime))
            throw new VerificationException(String.format(Locale.US,
                    "Block too far in future: %s (%d) vs allowed %s (%d)",
                    TimeUtils.dateTimeFormat(time), time.toEpochMilli(),
                    TimeUtils.dateTimeFormat(allowedTime), allowedTime.toEpochMilli()));
    }

    /**
     * Sums up all SigOps in all transactions of this block.
     *
     * @return sum of SigOps
     */
    public int sigOpCount() {
        checkState(!isHeaderOnly());
        return transactions.stream().mapToInt(Transaction::getSigOpCount).sum();
    }

    private void checkSigOps() throws VerificationException {
        // Check there aren't too many signature verifications in the block. This is an anti-DoS measure, see the
        // comments for MAX_BLOCK_SIGOPS.
        if (sigOpCount() > MAX_BLOCK_SIGOPS)
            throw new VerificationException("Block had too many Signature Operations");
    }

    private void checkMerkleRoot() throws VerificationException {
        Sha256Hash calculatedRoot = calculateMerkleRoot();
        if (!calculatedRoot.equals(merkleRoot)) {
            log.error("Merkle tree did not verify");
            throw new VerificationException("Merkle hashes do not match: " + calculatedRoot + " vs " + merkleRoot);
        }
    }

    // For testing only
    void checkWitnessRoot() throws VerificationException {
        Transaction coinbase = transactions.get(0);
        checkState(coinbase.isCoinBase());
        Sha256Hash witnessCommitment = coinbase.findWitnessCommitment();
        if (witnessCommitment != null) {
            byte[] witnessReserved = null;
            TransactionWitness witness = coinbase.getInput(0).getWitness();
            if (witness.getPushCount() != 1)
                throw new VerificationException("Coinbase witness reserved invalid: push count");
            witnessReserved = witness.getPush(0);
            if (witnessReserved.length != 32)
                throw new VerificationException("Coinbase witness reserved invalid: length");

            Sha256Hash witnessRootHash = Sha256Hash.twiceOf(getWitnessRoot().serialize(), witnessReserved);
            if (!witnessRootHash.equals(witnessCommitment))
                throw new VerificationException("Witness merkle root invalid. Expected " + witnessCommitment.toString()
                        + " but got " + witnessRootHash.toString());
        } else {
            for (Transaction tx : transactions) {
                if (tx.hasWitnesses())
                    throw new VerificationException("Transaction witness found but no witness commitment present");
            }
        }
    }

    private Sha256Hash calculateMerkleRoot() {
        List<Sha256Hash> tree = buildMerkleTree(false);
        return tree.get(tree.size() - 1);
    }

    private Sha256Hash calculateWitnessRoot() {
        List<Sha256Hash> tree = buildMerkleTree(true);
        return tree.get(tree.size() - 1);
    }

    private List<Sha256Hash> buildMerkleTree(boolean useWTxId) {
        // The Merkle root is based on a tree of hashes calculated from the transactions:
        //
        //     root
        //      / \
        //   A      B
        //  / \    / \
        // t1 t2 t3 t4
        //
        // The tree is represented as a list: t1,t2,t3,t4,A,B,root where each
        // entry is a hash.
        //
        // The hashing algorithm is double SHA-256. The leaves are a hash of the serialized contents of the transaction.
        // The interior nodes are hashes of the concatenation of the two child hashes.
        //
        // This structure allows the creation of proof that a transaction was included into a block without having to
        // provide the full block contents. Instead, you can provide only a Merkle branch. For example to prove tx2 was
        // in a block you can just provide tx2, the hash(tx1) and B. Now the other party has everything they need to
        // derive the root, which can be checked against the block header. These proofs aren't used right now but
        // will be helpful later when we want to download partial block contents.
        //
        // Note that if the number of transactions is not even the last tx is repeated to make it so (see
        // tx3 above). A tree with 5 transactions would look like this:
        //
        //         root
        //        /     \
        //       1        5
        //     /   \     / \
        //    2     3    4  4
        //  / \   / \   / \
        // t1 t2 t3 t4 t5 t5
        ArrayList<Sha256Hash> tree = new ArrayList<>(transactions.size());
        // Start by adding all the hashes of the transactions as leaves of the tree.
        for (Transaction tx : transactions) {
            final Sha256Hash hash;
            if (useWTxId && tx.isCoinBase())
                hash = Sha256Hash.ZERO_HASH;
            else
                hash = useWTxId ? tx.getWTxId() : tx.getTxId();
            tree.add(hash);
        }
        int levelOffset = 0; // Offset in the list where the currently processed level starts.
        // Step through each level, stopping when we reach the root (levelSize == 1).
        for (int levelSize = transactions.size(); levelSize > 1; levelSize = (levelSize + 1) / 2) {
            // For each pair of nodes on that level:
            for (int left = 0; left < levelSize; left += 2) {
                // The right hand node can be the same as the left hand, in the case where we don't have enough
                // transactions.
                int right = Math.min(left + 1, levelSize - 1);
                Sha256Hash leftHash = tree.get(levelOffset + left);
                Sha256Hash rightHash = tree.get(levelOffset + right);
                tree.add(Sha256Hash.wrapReversed(hashTwice(
                        leftHash.serialize(),
                        rightHash.serialize())));
            }
            // Move to the next level.
            levelOffset += levelSize;
        }
        return tree;
    }

    /**
     * Verify the transactions on a block.
     *
     * @param height block height, if known, or -1 otherwise. If provided, used
     * to validate the coinbase input script of v2 and above blocks.
     * @throws VerificationException if there was an error verifying the block.
     */
    private void checkTransactions(final int height, final EnumSet<VerifyFlag> flags)
            throws VerificationException {
        // The first transaction in a block must always be a coinbase transaction.
        if (!transactions.get(0).isCoinBase())
            throw new VerificationException("First tx is not coinbase");
        if (flags.contains(Block.VerifyFlag.HEIGHT_IN_COINBASE) && height >= BLOCK_HEIGHT_GENESIS) {
            transactions.get(0).checkCoinBaseHeight(height);
        }
        // The rest must not be.
        for (int i = 1; i < transactions.size(); i++) {
            if (transactions.get(i).isCoinBase())
                throw new VerificationException("TX " + i + " is coinbase when it should not be.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return getHash().equals(((Block)o).getHash());
    }

    @Override
    public int hashCode() {
        return getHash().hashCode();
    }

    /**
     * Returns the merkle root in big endian form, calculating it from transactions if necessary.
     */
    public Sha256Hash getMerkleRoot() {
        if (merkleRoot == null) {
            //TODO check if this is really necessary.
            unCacheHeader();
            merkleRoot = calculateMerkleRoot();
        }
        return merkleRoot;
    }

    /** Exists only for unit testing. */
    // For testing only
    void setMerkleRoot(Sha256Hash value) {
        unCacheHeader();
        merkleRoot = value;
        hash = null;
    }

    /**
     * Returns the witness root in big endian form, calculating it from transactions if necessary.
     */
    public Sha256Hash getWitnessRoot() {
        if (witnessRoot == null)
            witnessRoot = calculateWitnessRoot();
        return witnessRoot;
    }

    /** Adds a transaction to this block. The nonce and merkle root are invalid after this. */
    public void addTransaction(Transaction t) {
        checkState(!isHeaderOnly());
        unCacheTransactions();
        if (transactions.isEmpty() && !t.isCoinBase())
            throw new RuntimeException("Attempted to add a non-coinbase transaction as the first transaction: " + t);
        else if (!transactions.isEmpty() && t.isCoinBase())
            throw new RuntimeException("Attempted to add a coinbase transaction when there already is one: " + t);
        transactions.add(t);
        // Force a recalculation next time the values are needed.
        merkleRoot = null;
        hash = null;
    }

    /** For testing only. */
    @VisibleForTesting
    void replaceTransactions(List<Transaction> transactions) {
        unCacheTransactions();
        this.transactions.clear();
        this.transactions.addAll(transactions);
    }

    /**
     * Returns the version of the block data structure.
     *
     * @return version of the block
     */
    public long version() {
        return version;
    }

    /** @deprecated use {@link #version()} */
    @Deprecated
    public long getVersion() {
        return version();
    }

    /**
     * Returns the hash of the previous block in the chain, as defined by the block header.
     *
     * @return hash of the previous block
     */
    public Sha256Hash prevHash() {
        return prevHash;
    }

    /** @deprecated use {@link #prevHash()} */
    @Deprecated
    public Sha256Hash getPrevBlockHash() {
        return prevHash();
    }

    /**
     * Returns the time at which the block was solved and broadcast, according to the clock of the solving node.
     */
    public Instant time() {
        return time;
    }

    /**
     * Returns the time at which the block was solved and broadcast, according to the clock of the solving node. This
     * is measured in seconds since the UNIX epoch (midnight Jan 1st 1970).
     * @deprecated use {@link #time()}
     */
    @Deprecated
    public long getTimeSeconds() {
        return time.getEpochSecond();
    }

    // For testing only
    void setTime(Instant time) {
        unCacheHeader();
        this.time = time.truncatedTo(ChronoUnit.SECONDS); // convert to Bitcoin time
        this.hash = null;
    }

    /**
     * Returns the difficulty of the proof of work that this block should meet. The {@link
     * BlockChain} verifies that this is not too easy by looking at the length of the chain when the block is added.
     * To find the actual value the hash should be compared against, use
     * {@link Difficulty#asInteger()}. Note that this is <b>not</b> the same as
     * the difficulty value reported by the Bitcoin "getdifficulty" RPC that you may see on various block explorers.
     * That number is the result of applying a formula to the underlying difficulty to normalize the minimum to 1.
     * Calculating the difficulty that way is currently unsupported.
     */
    public Difficulty difficultyTarget() {
        return difficultyTarget;
    }

    /** @deprecated use {@link #difficultyTarget()} then {@link Difficulty#compact()} */
    @Deprecated
    public long getDifficultyTarget() {
        return difficultyTarget.compact();
    }

    /** Sets the difficulty target. */
    // For testing only
    void setDifficultyTarget(Difficulty difficultyTarget) {
        unCacheHeader();
        this.difficultyTarget = difficultyTarget;
        this.hash = null;
    }

    /**
     * Returns the nonce, an arbitrary value that exists only to make the hash of the block header fall below the
     * difficulty target.
     */
    public long getNonce() {
        return nonce;
    }

    /** Sets the nonce and clears any cached data. */
    // For testing only
    void setNonce(long nonce) {
        unCacheHeader();
        this.nonce = nonce;
        this.hash = null;
    }

    /**
     * Returns an unmodifiable view of the transactions held in this block. The transactions themselves can change.
     * This can only be called on regular (non-header-only) blocks.
     * <p>
     * Before using this method, consider using one of: {@link #transactionCount()}, {@link #transaction(int)},
     * {@link #forEachTransaction(Consumer)}, {@link #findTransactions(Predicate)}, {@link #addTransaction(Transaction)}
     *
     * @return transactions in this block, can be empty
     */
    public List<Transaction> transactions() {
        checkState(!isHeaderOnly(), () -> "block is header-only");
        return Collections.unmodifiableList(transactions);
    }

    /** @deprecated use {@link #transactions()} or {@link #isHeaderOnly()} */
    @Deprecated
    @Nullable
    public List<Transaction> getTransactions() {
        return isHeaderOnly() ? null : transactions();
    }

    /**
     * Returns the number of transactions in this block.
     *
     * @return number of transactions
     */
    public int transactionCount() {
        checkState(!isHeaderOnly());
        return transactions.size();
    }

    /**
     * Gets the transaction at the given index.
     *
     * @param index index of the transaction to get
     * @return transaction
     * @throws IndexOutOfBoundsException if the given index is out of bounds
     */
    public Transaction transaction(int index) {
        checkState(!isHeaderOnly());
        return transactions.get(index);
    }

    /**
     * Performs the given action for each transaction in this block.
     *
     * @param action action to be performed for each transaction
     */
    public void forEachTransaction(Consumer<Transaction> action) {
        checkState(!isHeaderOnly());
        transactions.forEach(action);
    }

    /**
     * Find transactions matching a predicate.
     *
     * @param predicate test that returns {@code true} for a match
     */
    public Stream<Transaction> findTransactions(Predicate<Transaction> predicate) {
        return transactions != null
                ? transactions.stream().filter(predicate)
                : Stream.empty();
    }

    /**
     * Return whether this block is purely a header.
     *
     * @return {@code true} if block is purely a header, {@code false} otherwise
     */
    public boolean isHeaderOnly() {
        return this.transactions == null;
    }

    /**
     * Return whether this block contains any transactions.
     *
     * @return {@code true}  if the block contains transactions, {@code false} otherwise
     */
    public boolean hasTransactions() {
        return this.transactions != null && !this.transactions.isEmpty();
    }

    /**
     * Returns whether this block conforms to
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0034.mediawiki">BIP34: Height in Coinbase</a>.
     */
    public boolean isBIP34() {
        return version >= BLOCK_VERSION_BIP34;
    }

    /**
     * Returns whether this block conforms to
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0066.mediawiki">BIP66: Strict DER signatures</a>.
     */
    public boolean isBIP66() {
        return version >= BLOCK_VERSION_BIP66;
    }

    /**
     * Returns whether this block conforms to
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0065.mediawiki">BIP65: OP_CHECKLOCKTIMEVERIFY</a>.
     */
    public boolean isBIP65() {
        return version >= BLOCK_VERSION_BIP65;
    }

    /**
     * Verifies both the header and that the transactions hash to the merkle root.
     *
     * @param params parameters for the verification rules
     * @param block  block to verify
     * @param height block height, if known, or -1 otherwise.
     * @param flags flags to indicate which tests should be applied (i.e.
     * whether to test for height in the coinbase transaction).
     * @throws VerificationException if at least one of the rules is violated
     */
    public static void verify(NetworkParameters params, Block block, int height, EnumSet<VerifyFlag> flags) throws VerificationException {
        verifyHeader(block);
        verifyTransactions(params, block, height, flags);
    }

    /**
     * Checks the block data to ensure it follows the rules laid out in the network parameters. Specifically,
     * throws an exception if the proof of work is invalid, or if the timestamp is too far from what it should be.
     * This is <b>not</b> everything that is required for a block to be valid, only what is checkable independent
     * of the chain and without a transaction index.
     *
     * @param block  block to verify
     * @throws VerificationException if at least one of the rules is violated
     */
    public static void verifyHeader(Block block) throws VerificationException {
        // Prove that this block is OK. It might seem that we can just ignore most of these checks given that the
        // network is also verifying the blocks, but we cannot as it'd open us to a variety of obscure attacks.
        //
        // Firstly we need to ensure this block does in fact represent real work done. If the difficulty is high
        // enough, it's probably been done by the network.
        block.checkProofOfWork();
        block.checkTimestamp();
    }

    /**
     * Checks the block contents
     *
     * @param params parameters for the verification rules
     * @param block  block to verify
     * @param height block height, if known, or -1 otherwise. If valid, used
     * to validate the coinbase input script of v2 and above blocks.
     * @param flags flags to indicate which tests should be applied (i.e.
     * whether to test for height in the coinbase transaction).
     * @throws VerificationException if at least one of the rules is violated
     */
    public static void verifyTransactions(NetworkParameters params, Block block, int height,
                                          EnumSet<VerifyFlag> flags) throws VerificationException {
        // Now we need to check that the body of the block actually matches the headers. The network won't generate
        // an invalid block, but if we didn't validate this then an untrusted man-in-the-middle could obtain the next
        // valid block from the network and simply replace the transactions in it with their own fictional
        // transactions that reference spent or non-existent inputs.
        if (block.transactions.isEmpty())
            throw new VerificationException("Block had no transactions");
        if (block.messageSize() > MAX_BLOCK_SIZE)
            throw new VerificationException("Block larger than MAX_BLOCK_SIZE");
        block.checkTransactions(height, flags);
        block.checkMerkleRoot();
        block.checkSigOps();
        for (Transaction tx : block.transactions)
            Transaction.verify(params.network(), tx);
    }
}
