/*
 * Copyright 2014 Kosta Korenkov
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

package org.bitcoinj.testing;

import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.signers.CustomTransactionSigner;
import org.bitcoinj.wallet.DeterministicKeyChain;

/**
 * <p>Transaction signer which uses provided keychain to get signing keys from. It relies on previous signer to provide
 * derivation path to be used to get signing key and, once gets the key, just signs given transaction immediately.</p>
 * It should not be used in test scenarios involving serialization as it doesn't have proper serialize/deserialize
 * implementation.
 */
public class KeyChainTransactionSigner extends CustomTransactionSigner {

    private DeterministicKeyChain keyChain;

    public KeyChainTransactionSigner() {
    }

    public KeyChainTransactionSigner(DeterministicKeyChain keyChain) {
        this.keyChain = keyChain;
    }

    @Override
    protected SignatureAndKey getSignature(Sha256Hash sighash, HDPath.HDPartialPath derivationPath) {
        DeterministicKey key = keyChain.getKeyByPath(derivationPath, true);
        return new SignatureAndKey(key.sign(sighash), key.withoutPrivateKey().withoutParent());
    }
}
