package com.g27.DepChain.smartContact;

import com.g27.DepChain.Chain.CommitExecutor;
import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.HotStuff.HotStuffNode;
import com.g27.DepChain.HotStuff.NodeConfig;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import org.json.JSONObject;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Keys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TransferFeeTest {

    @Test
    public void commitExecutorTransferChargesClientAndCreditsLeaderFee() throws Exception {
        EvmExecutor evm = new EvmExecutor();
        StubNode node = newBareNode();
        node.evm = evm;
        node.id = 1;
        node.n = 4;
        node.nonces = new ConcurrentHashMap<>();
        node.requests = new ArrayList<>();
        node.crypto = buildAuthConfigWithEcdsa();

        Address sender = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        Address leader = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
        String clientAddress = "0xdddddddddddddddddddddddddddddddddddddddd";

        // Seed sender with some DepCoin
        long initial = 1_000_000L;
        evm.world().createAccount(sender, 0, Wei.of(initial));
        evm.world().createAccount(receiver, 0, Wei.ZERO);

        long value = 100L;
        long intrinsic = 21_000L;
        long gasLimit = 50_000L; // intentionally larger than intrinsic to exercise refunds
        long gasPrice = 2L; // wei per gas

        long senderBefore = evm.world().getAccount(sender).getBalance().toLong();
        long receiverBefore = evm.world().getAccount(receiver).getBalance().toLong();
        long leaderBefore = evm.world().getAccount(leader) == null ? 0L : evm.world().getAccount(leader).getBalance().toLong();

        JSONObject tx = new JSONObject();
        tx.put("txType", "TRANSFER");
        tx.put("sender", sender.toHexString());
        tx.put("to", receiver.toHexString());
        tx.put("value", value);
        tx.put("gasLimit", gasLimit);
        tx.put("gasPrice", gasPrice);
        tx.put("nonce", 0L);

        JSONObject envelope = new JSONObject();
        envelope.put("address", clientAddress);
        envelope.put("value", tx.toString());
        envelope.put("requestHash", "0x01");

        String blockData = envelope.toString();

        CommitExecutor.executeCommit(node, blockData, leader);

        long actualFee = Math.min(gasLimit, intrinsic) * gasPrice; // gasPrice * gasUsed
        long expectedFinal = senderBefore - value - actualFee;

        long senderAfter = evm.world().getAccount(sender).getBalance().toLong();
        long receiverAfter = evm.world().getAccount(receiver).getBalance().toLong();
        long leaderAfter = evm.world().getAccount(leader).getBalance().toLong();

        assertEquals(expectedFinal, senderAfter, "sender final balance should equal initial - value - actualFee");
        assertEquals(receiverBefore + value, receiverAfter, "receiver should get transferred value");
        assertEquals(leaderBefore + actualFee, leaderAfter, "leader should receive exact gas fee");
    }

    private static AuthConfig buildAuthConfigWithEcdsa() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();
        AuthConfig cfg = new AuthConfig(kp.getPrivate(), kp.getPublic(), new HashMap<>());
        cfg.setEcdsaKeyPair(Keys.createEcKeyPair());
        return cfg;
    }

    private static StubNode newBareNode() throws Exception {
        java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        java.lang.reflect.Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (StubNode) allocate.invoke(unsafe, StubNode.class);
    }

    private static final class StubNode extends HotStuffNode {
        EvmExecutor evm;
        List<String> requests;
        Map<String, Long> nonces;
        AuthConfig crypto;
        int id;
        int n;

        private StubNode() throws Exception {
            super((NodeConfig) null);
        }

        @Override
        public void dbg(String fn) {
            // No-op in tests to keep output clean.
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public int getN() {
            return n;
        }

        @Override
        public EvmExecutor getEvmExecutor() {
            return evm;
        }

        @Override
        public List<String> getClientRequests() {
            return requests;
        }

        @Override
        public Map<String, Long> getClientNonces() {
            return nonces;
        }

        @Override
        public AuthConfig getCrypto() {
            return crypto;
        }

        @Override
        public synchronized void appendCommittedBlock(String blockData, java.util.List<com.g27.DepChain.smartContact.model.Transaction> txs, String blockHash) {
            // No-op for this unit test.
        }

        @Override
        public synchronized void removeClientRequests(String blockdata) {
            // No-op for this unit test.
        }

        @Override
        public void addLog(String log, boolean isaccomplished) {
            // No-op for this unit test.
        }

        @Override
        public synchronized void sendToClientPublic(String replyData) {
            // No-op for this unit test.
        }
    }
}
