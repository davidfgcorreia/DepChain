package com.g27.DepChain.BLS;

import tech.pegasys.teku.bls.BLS;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;

import org.apache.tuweni.bytes.Bytes;

import com.g27.DepChain.Crypto.BLSConfig;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class BLSImpl {

    // Sign with Private Key from BLSConfig
    public static BLSSignature sign(BLSConfig config, String message) {
        Bytes msgBytes = Bytes.wrap(message.getBytes(StandardCharsets.UTF_8));
        return BLS.sign(config.getKeyPair().getSecretKey(), msgBytes);
    }

    // Aggregate gathered signatures
    public static BLSSignature aggregate(List<BLSSignature> signatures) {
        return BLS.aggregate(signatures);
    }

    /**
     * Verify aggregated signature against the public keys of the signers
     * pubKeys must be in the same order as the nodes that produced the sigs
     */
    public static boolean verify(List<BLSPublicKey> pubKeys, String message, BLSSignature aggregatedSig) {
        Bytes msgBytes = Bytes.wrap(message.getBytes(StandardCharsets.UTF_8));
        return BLS.fastAggregateVerify(pubKeys, msgBytes, aggregatedSig);
    }
}
