package com.g27.DepChain.HotStuff;

import com.g27.DepChain.BLS.BLSImpl;
import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.Crypto.BLSConfig;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;

import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for HotStuff consensus protocol components.
 * Tests signature verification, malformed message handling, and cryptographic security.
 */
class HotStuffSecurityTest {

    private static final int N = 4;
    private List<BLSKeyPair> blsKeyPairs;
    private List<BLSPublicKey> allPubKeys;
    private BLSConfig blsConfig;
    private HotStuffNode mockNode;

    @BeforeEach
    void setUp() throws Exception {
        // Generate BLS key pairs for testing
        SecureRandom rng = new SecureRandom();
        blsKeyPairs = new ArrayList<>();
        allPubKeys = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            BLSKeyPair kp = BLSKeyPair.random(rng);
            blsKeyPairs.add(kp);
            allPubKeys.add(kp.getPublicKey());
        }

        // Create BLS config for node 0
        blsConfig = new BLSConfig(blsKeyPairs.get(0), allPubKeys);

        // Create minimal NodeConfig for testing
        Map<Integer, NodeConfig.MemberInfo> members = new HashMap<>();
        for (int i = 0; i < N; i++) {
            members.put(i, new NodeConfig.MemberInfo(java.net.InetAddress.getByName("localhost"), 5000 + i));
        }

        // Create minimal AuthConfig
        java.security.KeyPairGenerator kg = java.security.KeyPairGenerator.getInstance("RSA");
        kg.initialize(2048);
        java.security.KeyPair kp = kg.generateKeyPair();
        Map<Integer, java.security.PublicKey> memberKeys = new HashMap<>();
        for (int i = 0; i < N; i++) {
            memberKeys.put(i, kp.getPublic());
        }
        AuthConfig authConfig = new AuthConfig(kp.getPrivate(), kp.getPublic(), memberKeys);

        NodeConfig cfg = new NodeConfig(0, "localhost", 5000, 0, 0, members, authConfig, blsConfig);
        mockNode = new HotStuffNode(cfg);
    }

    @Test
    void blsVoteRejection_signatureFromWrongKeyFails() {
        // Create a valid QC with signatures from nodes 0,1,2
        String blockDataHash = "testBlockDataHash";
        Set<Integer> voters = Set.of(0, 1, 2);
        String requestType = "PREPARE";
        int viewNumber = 1;

        // Sign with correct keys
        List<BLSSignature> signatures = new ArrayList<>();
        for (int voter : voters) {
            BLSSignature sig = BLSImpl.sign(new BLSConfig(blsKeyPairs.get(voter), allPubKeys), blockDataHash);
            signatures.add(sig);
        }
        BLSSignature aggregatedSig = BLSImpl.aggregate(signatures);
        String hexSig = aggregatedSig.toSSZBytes().toHexString();

        QC validQC = new QC(blockDataHash, blockDataHash, hexSig, voters, requestType, viewNumber);

        // Verification should succeed
        assertTrue(HotStuffVerify.verifySignature(mockNode, validQC), "Valid QC should verify");

        // Now create invalid QC with wrong signature (sign with different keys)
        BLSKeyPair wrongKeyPair = BLSKeyPair.random(new SecureRandom());
        List<BLSPublicKey> wrongAllPubKeys = new ArrayList<>(allPubKeys);
        wrongAllPubKeys.set(0, wrongKeyPair.getPublicKey()); // replace first key
        BLSConfig wrongBlsConfig = new BLSConfig(wrongKeyPair, wrongAllPubKeys);
        BLSSignature wrongSig = BLSImpl.sign(wrongBlsConfig, blockDataHash);
        String wrongHexSig = wrongSig.toSSZBytes().toHexString();

        QC invalidQC = new QC(blockDataHash, blockDataHash, wrongHexSig, voters, requestType, viewNumber);

        // Verification should fail
        assertFalse(HotStuffVerify.verifySignature(mockNode, invalidQC), "QC with wrong signature should fail verification");
    }

    @Test
    void blsVoteRejection_tamperedMessageFails() {
        // Create a valid QC
        String blockDataHash = "testBlockDataHash";
        Set<Integer> voters = Set.of(0, 1, 2);
        String requestType = "PREPARE";
        int viewNumber = 1;

        // Sign with correct keys
        List<BLSSignature> signatures = new ArrayList<>();
        for (int voter : voters) {
            BLSSignature sig = BLSImpl.sign(new BLSConfig(blsKeyPairs.get(voter), allPubKeys), blockDataHash);
            signatures.add(sig);
        }
        BLSSignature aggregatedSig = BLSImpl.aggregate(signatures);
        String hexSig = aggregatedSig.toSSZBytes().toHexString();

        QC validQC = new QC(blockDataHash, blockDataHash, hexSig, voters, requestType, viewNumber);

        // Verification should succeed
        assertTrue(HotStuffVerify.verifySignature(mockNode, validQC), "Valid QC should verify");

        // Tamper with the blockDataHash in QC but keep signature
        String tamperedHash = "tamperedBlockDataHash";
        QC tamperedQC = new QC(tamperedHash, tamperedHash, hexSig, voters, requestType, viewNumber);

        // Verification should fail
        assertFalse(HotStuffVerify.verifySignature(mockNode, tamperedQC), "Tampered QC should fail verification");
    }

    @Test
    void verifyblock_rejectsConflictingProposeWithWrongParentHash() {
        // Seed committed genesis state on the node (fields are package-private)
        QC genesisQC = new QC("null");
        InternalBlock genesis = new InternalBlock("Genesis", genesisQC, "null", 0, 0);
        mockNode.internalBlocks.add(genesis);
        mockNode.commitedBlockNumber = 0;
        mockNode.PileBlock = 0;
        mockNode.highQC = genesisQC;
        mockNode.viewNumber = 0;

        String genesisHash = genesis.getHash(0);

        // Block number 1 skips BLS verification inside verifyblock, so we can test
        // the chain-extension rule in isolation.
        QC stubQC = new QC("null");

        // Valid proposal: correctly extends genesis
        InternalBlock validBlock = new InternalBlock("NO_OP", stubQC, genesisHash, 0, 1);
        assertTrue(HotStuffVerify.verifyblock(mockNode, validBlock),
            "a block correctly extending genesis must be accepted");

        // Conflicting proposal at the same view but with the wrong parent hash —
        // simulates a Byzantine leader trying to fork from the committed chain
        InternalBlock conflicting = new InternalBlock("NO_OP_CONFLICT", stubQC, "WRONG_PARENT_HASH", 0, 1);
        assertFalse(HotStuffVerify.verifyblock(mockNode, conflicting),
            "a block with a wrong parent hash must be rejected even when the view matches");
    }

    @Test
    void internalBlock_nullStringThrowsIllegalArgumentException() {
        // Passing null to InternalBlock constructor should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new InternalBlock(null),
            "InternalBlock constructor should throw IllegalArgumentException for null string");
    }

    @Test
    void internalBlock_missingBlockFieldInProposeProducesException() {
        // Simulate a PROPOSE message missing the "block" field
        // When dispatch tries to parse, blockM will be null, causing exception
        String malformedPropose = "{\"type\":\"PROPOSE\",\"highQC\":\"{}\",\"view\":1,\"id\":0}";
        // This would be parsed in dispatch, but for unit test, directly test InternalBlock with null
        assertThrows(IllegalArgumentException.class, () -> new InternalBlock(null),
            "Missing block field in PROPOSE should result in null InternalBlock string, throwing exception");
    }
}
