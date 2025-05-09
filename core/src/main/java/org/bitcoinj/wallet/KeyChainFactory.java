/*
 * Copyright 2014 devrandom
 * Copyright 2019 Andreas Schildbach
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

package org.bitcoinj.wallet;

import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.crypto.KeyCrypter;

/**
 * Factory interface for creation keychains while de-serializing a wallet.
 */
public interface KeyChainFactory {
    /**
     * Make a keychain (but not a watching one) with the specified account path
     *
     * @param seed             the seed
     * @param crypter          the encrypted/decrypter
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @param accountPath      account path to generate receiving addresses on
     */
    DeterministicKeyChain makeKeyChain(DeterministicSeed seed, KeyCrypter crypter,
                                       ScriptType outputScriptType, HDPath.HDPartialPath accountPath);

    /**
     * Make a watching keychain.
     *
     * <p>isMarried and isFollowingKey must not be true at the same time.
     *
     * @param accountKey       the account extended public key
     * @param outputScriptType type of addresses (aka output scripts) to generate for watching
     */
    DeterministicKeyChain makeWatchingKeyChain(DeterministicKey accountKey,
                                               ScriptType outputScriptType) throws UnreadableWalletException;

    /**
     * Make a spending keychain.
     *
     * <p>isMarried and isFollowingKey must not be true at the same time.
     *
     * @param accountKey       the account extended public key
     * @param outputScriptType type of addresses (aka output scripts) to generate for spending
     */
    DeterministicKeyChain makeSpendingKeyChain(DeterministicKey accountKey,
                                               ScriptType outputScriptType) throws UnreadableWalletException;
}
