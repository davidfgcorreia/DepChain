package com.g27.DepChain.Crypto;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSPublicKey;

import java.util.List;

public class BLSConfig {
    private final BLSKeyPair keyPair;
    private final List<BLSPublicKey> allPublicKeys; // indexed by node id

    public BLSConfig(BLSKeyPair keyPair, List<BLSPublicKey> allPublicKeys) {
        this.keyPair = keyPair;
        this.allPublicKeys = allPublicKeys;
    }

    public BLSKeyPair getKeyPair() {
        return keyPair;
    }

    public List<BLSPublicKey> getAllPublicKeys() {
        return allPublicKeys;
    }
}
