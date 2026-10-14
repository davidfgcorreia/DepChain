package com.g27.DepChain.Crypto;

import java.util.Set;

import com.weavechain.curve25519.Scalar;
import com.weavechain.sig.ThresholdSigEd25519;

public class ThresholdConfig {
    private final int n; // total number of parties
    private final int t; // threshold
    private final byte[] groupPublicKey; // group public key
    private final Scalar secretShare; // secret share for this party
    private ThresholdSigEd25519 thresholdSigobj;
    private Set<Integer> nodes;
    private final Scalar sig;

    public ThresholdConfig(int t, int n, byte[] groupPublicKey, Scalar secretShare,Set<Integer> nodes, Scalar sig) {
        this.n = n;
        this.t = t;
        this.groupPublicKey = groupPublicKey;
        this.secretShare = secretShare;
        this.thresholdSigobj = new ThresholdSigEd25519(t, n);
        this.nodes = nodes;
        this.sig = sig;
    }

    public int getN() {
        return n;
    }

    public int getT() {
        return t;
    }

    public byte[] getGroupPublicKey() {
        return groupPublicKey;
    }

    public Scalar getSecretShare() {
        return secretShare;
    }

    public ThresholdSigEd25519 getThresholdSigObj() {
        return thresholdSigobj;
    }

    public Set<Integer> getNodes() {
        return nodes;
    }
    public Scalar getSig() {
        return sig;
    }

}
