/*
 * Copyright 2013 Google Inc.
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

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.FullPrunedBlockStore;
import org.bitcoinj.store.SPVBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.bitcoinj.base.internal.Preconditions.checkArgument;
import static org.bitcoinj.base.internal.Preconditions.checkState;

/**
 * <p>Vends hard-coded {@link StoredBlock}s for blocks throughout the chain. Checkpoints serve two purposes:</p>
 * <ol>
 *    <li>They act as a safety mechanism against huge re-orgs that could rewrite large chunks of history, thus
 *    constraining the block chain to be a consensus mechanism only for recent parts of the timeline.</li>
 *    <li>They allow synchronization to the head of the chain for new wallets/users much faster than syncing all
 *    headers from the genesis block.</li>
 * </ol>
 *
 * <p>Checkpoints are used by the SPV {@link BlockChain} to initialize fresh
 * {@link SPVBlockStore}s. They are not used by fully validating mode, which instead has a
 * different concept of checkpoints that are used to hard-code the validity of blocks that violate BIP30 (duplicate
 * coinbase transactions). Those "checkpoints" can be found in NetworkParameters.</p>
 *
 * <p>Checkpoints are read from a text file, one value per line.
 * It consists of the magic string "TXT CHECKPOINTS 1", followed by the number of signatures
 * to read. The value may not be larger than 256.
 * If the number of signatures is larger than zero, each 65 byte ECDSA secp256k1 signature then follows. The signatures
 * sign the hash of all bytes that follow the last signature.</p>
 *
 * <p>After the signatures come the number of checkpoints in the file. Then each checkpoint follows one per line in
 * compact format (as written by {@link StoredBlock#serializeCompactV2(ByteBuffer)}) as a base64-encoded blob.</p>
 */
public class CheckpointManager {
    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    private static final String TEXTUAL_MAGIC = "TXT CHECKPOINTS 1";
    private static final int MAX_SIGNATURES = 256;

    // Map of block header time (in seconds) to data.
    protected final TreeMap<Instant, StoredBlock> checkpoints = new TreeMap<>();

    protected final NetworkParameters params;
    protected final Sha256Hash dataHash;

    public static final BaseEncoding BASE64 = BaseEncoding.base64().omitPadding();

    /** Loads the default checkpoints bundled with bitcoinj */
    public CheckpointManager(NetworkParameters params) throws IOException {
        this(params, null);
    }

    /** Loads the checkpoints from the given stream */
    public CheckpointManager(NetworkParameters params, @Nullable InputStream inputStream) throws IOException {
        this.params = Objects.requireNonNull(params);
        if (inputStream == null)
            inputStream = openStream(params);
        Objects.requireNonNull(inputStream);
        inputStream = new BufferedInputStream(inputStream);
        inputStream.mark(1);
        int first = inputStream.read();
        inputStream.reset();
        if (first == TEXTUAL_MAGIC.charAt(0))
            dataHash = readTextual(inputStream);
        else
            throw new IOException("Unsupported format.");
    }

    /** Returns a checkpoints stream pointing to inside the bitcoinj JAR */
    public static InputStream openStream(NetworkParameters params) {
        return CheckpointManager.class.getResourceAsStream("/" + params.getId() + ".checkpoints.txt");
    }

    private Sha256Hash readTextual(InputStream inputStream) throws IOException {
        Hasher hasher = Hashing.sha256().newHasher();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.US_ASCII))) {
            String magic = reader.readLine();
            if (!TEXTUAL_MAGIC.equals(magic))
                throw new IOException("unexpected magic: " + magic);
            int numSigs = Integer.parseInt(reader.readLine());
            for (int i = 0; i < numSigs; i++)
                reader.readLine(); // Skip sigs for now.
            int numCheckpoints = Integer.parseInt(reader.readLine());
            checkState(numCheckpoints > 0);
            // Hash numCheckpoints in a way compatible to the binary format.
            hasher.putBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(numCheckpoints).array());
            for (int i = 0; i < numCheckpoints; i++) {
                byte[] bytes = BASE64.decode(reader.readLine());
                hasher.putBytes(bytes);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                StoredBlock block;
                if (bytes.length == StoredBlock.COMPACT_SERIALIZED_SIZE)
                    block = StoredBlock.deserializeCompact(buffer);
                else if (bytes.length == StoredBlock.COMPACT_SERIALIZED_SIZE_V2)
                    block = StoredBlock.deserializeCompactV2(buffer);
                else
                    throw new IllegalStateException("unexpected length of checkpoint: " + bytes.length);
                checkpoints.put(block.getHeader().time(), block);
            }
            HashCode hash = hasher.hash();
            log.info("Read {} checkpoints up to time {}, hash is {}", checkpoints.size(),
                    TimeUtils.dateTimeFormat(checkpoints.lastEntry().getKey()), hash);
            return Sha256Hash.wrap(hash.asBytes());
        }
    }

    /**
     * Returns a {@link StoredBlock} representing the last checkpoint before the given time, for example, normally
     * you would want to know the checkpoint before the earliest wallet birthday.
     */
    public StoredBlock getCheckpointBefore(Instant time) {
        try {
            checkArgument(time.isAfter(params.getGenesisBlock().time()));
            // This is thread safe because the map never changes after creation.
            Map.Entry<Instant, StoredBlock> entry = checkpoints.floorEntry(time);
            if (entry != null) return entry.getValue();
            Block genesis = params.getGenesisBlock().asHeader();
            return new StoredBlock(genesis, genesis.getWork(), 0);
        } catch (VerificationException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    /** Returns the number of checkpoints that were loaded. */
    public int numCheckpoints() {
        return checkpoints.size();
    }

    /** Returns a hash of the concatenated checkpoint data. */
    public Sha256Hash getDataHash() {
        return dataHash;
    }

    /**
     * <p>Convenience method that creates a CheckpointManager, loads the given data, gets the checkpoint for the given
     * time, then inserts it into the store and sets that to be the chain head. Useful when you have just created
     * a new store from scratch and want to use configure it all in one go.</p>
     *
     * <p>Note that time is adjusted backwards by a week to account for possible clock drift in the block headers.</p>
     */
    public static void checkpoint(NetworkParameters params, InputStream checkpoints, BlockStore store, Instant time)
            throws IOException, BlockStoreException {
        Objects.requireNonNull(params);
        Objects.requireNonNull(store);
        checkArgument(!(store instanceof FullPrunedBlockStore), () ->
                "you cannot use checkpointing with a full store");

        time = time.minus(7, ChronoUnit.DAYS);

        log.info("Attempting to initialize a new block store with a checkpoint for time {} ({})", time.getEpochSecond(),
                TimeUtils.dateTimeFormat(time));

        BufferedInputStream stream = new BufferedInputStream(checkpoints);
        CheckpointManager manager = new CheckpointManager(params, stream);
        StoredBlock checkpoint = manager.getCheckpointBefore(time);
        store.put(checkpoint);
        store.setChainHead(checkpoint);
    }
}
