package com.g27.DepChain.HotStuff;

import com.g27.DepChain.Chain.CommitExecutor;
import com.g27.DepChain.Chain.Execution;
import com.g27.DepChain.Chain.RequestValidator;
import com.g27.DepChain.Client.ClientConfig;
import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.smartContact.abi.AbiEncoder;
import com.g27.DepChain.smartContact.genesis.GenesisLoader;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic integration test that simulates terminal-style client requests
 * (client 4 and client 5), validates signatures, executes commits, confirms
 * state via read queries, and finishes with frontrunning-prevention assertions.
 */
class EndToEndClientFlowIntegrationTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void simulateClientFlow_withReads_and_frontrunningProtection_inBothRaceOrders() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        ClientConfig.Result c4 = ClientConfig.load(4);
        ClientConfig.Result c5 = ClientConfig.load(5);
        assertNotNull(c4.clientEcKeyPair, "Client 4 ECDSA key must be available");
        assertNotNull(c5.clientEcKeyPair, "Client 5 ECDSA key must be available");

        Address client4Addr = Address.fromHexString("0x" + Keys.getAddress(c4.clientEcKeyPair));
        Address client5Addr = Address.fromHexString("0x" + Keys.getAddress(c5.clientEcKeyPair));

        EvmExecutor evm = new EvmExecutor();
        String appliedGenesis = GenesisLoader.applyGenesisFromFile(evm);
        assertNotNull(appliedGenesis, "Genesis must be applied before integration flow");

        BareNode node = newBareNode(evm);
        wireClientIdentity(node, 4, client4Addr, c4.clientEcKeyPair);
        wireClientIdentity(node, 5, client5Addr, c5.clientEcKeyPair);

        // Leader account that receives fees in commit execution path.
        Address leader = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

        Address ist = computeContractAddress(client4Addr, 0L);

        long nonce4 = 0L;
        long nonce5 = 0L;

        BigInteger initial4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger initial5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        // 1) transfer(client4 -> client5, 100)
        JSONObject transfer = callTx(client4Addr, ist, AbiEncoder.transfer(client5Addr, BigInteger.valueOf(100L)), nonce4++);
        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr, transfer);

        BigInteger afterTransfer4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger afterTransfer5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));
        assertEquals(initial4.subtract(BigInteger.valueOf(100L)), afterTransfer4);
        assertEquals(initial5.add(BigInteger.valueOf(100L)), afterTransfer5);

        // 2) approve + increase + decrease
        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.approve(client5Addr, BigInteger.valueOf(100L)), nonce4++));
        assertEquals(BigInteger.valueOf(100L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.increaseAllowance(client5Addr, BigInteger.valueOf(30L)), nonce4++));
        assertEquals(BigInteger.valueOf(130L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.decreaseAllowance(client5Addr, BigInteger.valueOf(20L)), nonce4++));
        assertEquals(BigInteger.valueOf(110L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        // 3) transferFrom by client5
        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transferFrom(client4Addr, client5Addr, BigInteger.valueOf(40L)), nonce5++));
        assertEquals(BigInteger.valueOf(70L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        BigInteger afterTf4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger afterTf5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));
        assertEquals(afterTransfer4.subtract(BigInteger.valueOf(40L)), afterTf4);
        assertEquals(afterTransfer5.add(BigInteger.valueOf(40L)), afterTf5);

        // 4) Client 5 sends two transactions; second one uses too little gas and must fail.
        BigInteger beforePair4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger beforePair5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        // First tx succeeds.
        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transfer(client4Addr, BigInteger.valueOf(10L)), nonce5++));

        BigInteger afterFirstPair4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger afterFirstPair5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));
        assertEquals(beforePair4.add(BigInteger.valueOf(10L)), afterFirstPair4,
            "first tx in pair should succeed");
        assertEquals(beforePair5.subtract(BigInteger.valueOf(10L)), afterFirstPair5,
            "first tx in pair should succeed");

        // Second tx fails because client5 cannot afford max fee (gasLimit * gasPrice).
        long client5DepBefore = evm.world().getAccount(client5Addr).getBalance().toLong();
        long expensiveGasLimit = 200_000L;
        long expensiveGasPrice = (client5DepBefore / expensiveGasLimit) + 1L; // ensures maxFee > balance
        assertTrue(expensiveGasLimit * expensiveGasPrice > client5DepBefore,
            "constructed tx must require more DepCoin fee than client5 balance");

        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transfer(client4Addr, BigInteger.valueOf(9L)), nonce5++, expensiveGasLimit, expensiveGasPrice));

        assertEquals(afterFirstPair4, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr)),
            "second tx should not execute when fee is unaffordable");
        assertEquals(afterFirstPair5, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr)),
            "second tx should not execute when fee is unaffordable");

        // 5) Sender has enough DepCoin for max fee, but gas used exceeds gasLimit.
        // Transaction should fail, token state should remain unchanged, and full max fee is spent.
        long affordableGasLimit = 1L;
        long affordableGasPrice = 1L;
        long maxFee = affordableGasLimit * affordableGasPrice;
        long c5DepBeforeOog = evm.world().getAccount(client5Addr).getBalance().toLong();
        assertTrue(c5DepBeforeOog > maxFee,
            "client5 must have enough DepCoin to pay full maxFee in out-of-gas scenario");

        BigInteger beforeOog4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger beforeOog5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transfer(client4Addr, BigInteger.valueOf(6L)), nonce5++, affordableGasLimit, affordableGasPrice));

        long c5DepAfterOog = evm.world().getAccount(client5Addr).getBalance().toLong();
        assertEquals(c5DepBeforeOog - maxFee, c5DepAfterOog,
            "out-of-gas tx must charge full maxFee (gasLimit * gasPrice)");
        assertEquals(beforeOog4, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr)),
            "out-of-gas tx must not change recipient token balance");
        assertEquals(beforeOog5, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr)),
            "out-of-gas tx must not change sender token balance");

        // 6) transferFrom where `from` does not match sender context must fail.
        // Reset allowance to zero so this fails deterministically.
        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.approve(client5Addr, BigInteger.ZERO), nonce4++));
        assertEquals(BigInteger.ZERO, readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        BigInteger beforeBadTf4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger beforeBadTf5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        // Sender is client5, but `from` is client4 with no allowance granted.
        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transferFrom(client4Addr, client5Addr, BigInteger.valueOf(15L)), nonce5++));

        assertEquals(BigInteger.ZERO, readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)),
            "transferFrom must fail when spender has no allowance for `from`");
        assertEquals(beforeBadTf4, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr)),
            "failed transferFrom must not change owner balance");
        assertEquals(beforeBadTf5, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr)),
            "failed transferFrom must not change recipient balance");

        // 7) Frontrunning-prevention checks (race order A):
        // owner sets allowance, unsafe non-zero->non-zero approve is rejected,
        // spender drains first, then owner's decreaseAllowance executes late and fails.
        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.approve(client5Addr, BigInteger.ZERO), nonce4++));
        assertEquals(BigInteger.ZERO, readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.approve(client5Addr, BigInteger.valueOf(100L)), nonce4++));
        assertEquals(BigInteger.valueOf(100L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.approve(client5Addr, BigInteger.valueOf(50L)), nonce4++));
        assertEquals(BigInteger.valueOf(100L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)),
            "non-zero->non-zero approve must be rejected by frontrunning guard");

        BigInteger beforeDrain4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger beforeDrain5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        // Spender front-runs and spends the whole allowance.
        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transferFrom(client4Addr, client5Addr, BigInteger.valueOf(100L)), nonce5++));
        assertEquals(BigInteger.ZERO, readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)),
            "allowance should be fully drained by front-run transferFrom");

        BigInteger afterDrain4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger afterDrain5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));
        assertEquals(beforeDrain4.subtract(BigInteger.valueOf(100L)), afterDrain4);
        assertEquals(beforeDrain5.add(BigInteger.valueOf(100L)), afterDrain5);

        // Owner's decrease now executes after the drain and must fail (underflow guard).
        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.decreaseAllowance(client5Addr, BigInteger.valueOf(50L)), nonce4++));
        assertEquals(BigInteger.ZERO, readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)),
            "decreaseAllowance must fail after front-run drain because current allowance < requested decrease");

        // 8) Frontrunning-prevention checks (race order B):
        // owner decreases first, then spender tries to spend stale larger allowance -> spend fails.
        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.approve(client5Addr, BigInteger.valueOf(80L)), nonce4++));
        assertEquals(BigInteger.valueOf(80L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        submitAndCommit(node, leader, 4, c4.clientEcKeyPair, client4Addr,
            callTx(client4Addr, ist, AbiEncoder.decreaseAllowance(client5Addr, BigInteger.valueOf(50L)), nonce4++));
        assertEquals(BigInteger.valueOf(30L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)));

        BigInteger beforeLateSpend4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger beforeLateSpend5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        // Spender attempts to spend the old amount (80) after owner already decreased to 30.
        submitAndCommit(node, leader, 5, c5.clientEcKeyPair, client5Addr,
            callTx(client5Addr, ist, AbiEncoder.transferFrom(client4Addr, client5Addr, BigInteger.valueOf(80L)), nonce5++));

        // transferFrom should fail, so allowance and balances remain unchanged.
        assertEquals(BigInteger.valueOf(30L), readUint(evm, client4Addr, ist, AbiEncoder.allowance(client4Addr, client5Addr)),
            "stale larger transferFrom must fail after decreaseAllowance");
        assertEquals(beforeLateSpend4, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr)));
        assertEquals(beforeLateSpend5, readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr)));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void commitExecutor_mixedBlock_revertedTxStillAdvancesNonce_validSecondTxSucceeds() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        ClientConfig.Result c4 = ClientConfig.load(4);
        ClientConfig.Result c5 = ClientConfig.load(5);
        assertNotNull(c4.clientEcKeyPair);
        assertNotNull(c5.clientEcKeyPair);

        Address client4Addr = Address.fromHexString("0x" + Keys.getAddress(c4.clientEcKeyPair));
        Address client5Addr = Address.fromHexString("0x" + Keys.getAddress(c5.clientEcKeyPair));

        EvmExecutor evm = new EvmExecutor();
        assertNotNull(GenesisLoader.applyGenesisFromFile(evm));

        BareNode node = newBareNode(evm);
        wireClientIdentity(node, 4, client4Addr, c4.clientEcKeyPair);

        Address ist = computeContractAddress(client4Addr, 0L);
        Address leader = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

        long nonce4 = 0L;
        BigInteger initial4 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr));
        BigInteger initial5 = readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr));

        // tx1: transfer far more IST than client4 has → EVM-reverts (insufficient balance)
        // tx2: transfer 1 IST → valid, must execute and succeed
        JSONObject tx1 = callTx(client4Addr, ist,
            AbiEncoder.transfer(client5Addr, initial4.add(BigInteger.valueOf(1_000_000L))), nonce4);
        JSONObject tx2 = callTx(client4Addr, ist,
            AbiEncoder.transfer(client5Addr, BigInteger.ONE), nonce4 + 1);

        // Pre-validate both (validator checks nonce >= expected; does not advance it)
        String sv1 = tx1.toString(), ss1 = signKeccak(c4.clientEcKeyPair, sv1);
        String sh1 = Numeric.toHexString(org.web3j.crypto.Hash.sha3(sv1.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(RequestValidator.validateClientRequest(
            node.clientAddrMap, node.clientPubMap, node.clientNonceMap,
            4, sv1, ss1, sh1, client4Addr.toHexString()).valid, "tx1 must pre-validate");

        String sv2 = tx2.toString(), ss2 = signKeccak(c4.clientEcKeyPair, sv2);
        String sh2 = Numeric.toHexString(org.web3j.crypto.Hash.sha3(sv2.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(RequestValidator.validateClientRequest(
            node.clientAddrMap, node.clientPubMap, node.clientNonceMap,
            4, sv2, ss2, sh2, client4Addr.toHexString()).valid, "tx2 must pre-validate");

        // Bundle both envelopes into one block and commit
        JSONObject e1 = new JSONObject();
        e1.put("address", client4Addr.toHexString()); e1.put("value", sv1);
        e1.put("signature", ss1); e1.put("requestHash", sh1);
        JSONObject e2 = new JSONObject();
        e2.put("address", client4Addr.toHexString()); e2.put("value", sv2);
        e2.put("signature", ss2); e2.put("requestHash", sh2);
        JSONArray mixedBlock = new JSONArray();
        mixedBlock.put(e1);
        mixedBlock.put(e2);

        CommitExecutor.executeCommit(node, mixedBlock.toString(), leader);

        // tx1 EVM-reverted: its over-transfer had no effect on IST balances
        // tx2 succeeded: exactly 1 IST moved from client4 to client5
        assertEquals(initial4.subtract(BigInteger.ONE),
            readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client4Addr)),
            "tx1 revert must not affect IST; tx2 must deduct exactly 1 from client4");
        assertEquals(initial5.add(BigInteger.ONE),
            readUint(evm, client4Addr, ist, AbiEncoder.balanceOf(client5Addr)),
            "tx1 revert must not affect IST; tx2 must add exactly 1 to client5");

        // A reverted tx is still committed to the block and must consume its nonce slot.
        // Both nonce4 (tx1, reverted) and nonce4+1 (tx2, success) must be consumed.
        String c4Norm = client4Addr.toHexString().substring(2).toLowerCase();
        assertEquals(nonce4 + 2L, node.clientNonceMap.getOrDefault(c4Norm, 0L),
            "both nonces must be consumed: a reverted tx still occupies its nonce slot");
    }

    private static void wireClientIdentity(BareNode node, int clientId, Address addr, ECKeyPair ec) {
        String norm = addr.toHexString().substring(2).toLowerCase();
        byte[] pubRaw = toRaw64(ec.getPublicKey().toByteArray());

        node.clientAddrMap.put(clientId, norm);
        node.clientPubMap.put(clientId, pubRaw);
        node.clientNonceMap.put(norm, 0L);
    }

    private static JSONObject callTx(Address sender, Address to, Bytes data, long nonce) {
        return callTx(sender, to, data, nonce, 200_000L, 1L);
    }

    private static JSONObject callTx(Address sender, Address to, Bytes data, long nonce, long gasLimit, long gasPrice) {
        JSONObject tx = new JSONObject();
        tx.put("txType", "CALL");
        tx.put("sender", sender.toHexString());
        tx.put("to", to.toHexString());
        tx.put("value", 0L);
        tx.put("data", data.toHexString());
        tx.put("gasLimit", gasLimit);
        tx.put("gasPrice", gasPrice);
        tx.put("nonce", nonce);
        return tx;
    }

    private static void submitAndCommit(BareNode node,
                                        Address leader,
                                        int clientId,
                                        ECKeyPair clientKey,
                                        Address clientAddr,
                                        JSONObject tx) {
        String value = tx.toString();
        String signature = signKeccak(clientKey, value);
        String requestHash = Numeric.toHexString(org.web3j.crypto.Hash.sha3(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        RequestValidator.ValidationResult vr = RequestValidator.validateClientRequest(
            node.clientAddrMap,
            node.clientPubMap,
            node.clientNonceMap,
            clientId,
            value,
            signature,
            requestHash,
            clientAddr.toHexString());
        assertTrue(vr.valid, "Client request must validate: " + (vr.error == null ? "" : vr.error));

        JSONObject envelope = new JSONObject();
        envelope.put("address", clientAddr.toHexString());
        envelope.put("value", value);
        envelope.put("signature", signature);
        envelope.put("requestHash", requestHash);

        CommitExecutor.executeCommit(node, envelope.toString(), leader);
    }

    private static BigInteger readUint(EvmExecutor evm, Address caller, Address contract, Bytes callData) {
        JSONObject query = new JSONObject();
        query.put("sender", caller.toHexString());
        query.put("to", contract.toHexString());
        query.put("data", callData.toHexString());

        JSONObject out = Execution.executeRead(evm, query);
        assertTrue(out.optBoolean("success", false), "Read query failed: " + out.toString());
        return AbiEncoder.decodeUint256(Bytes.fromHexString(out.getString("output")));
    }

    private static String signKeccak(ECKeyPair key, String payload) {
        byte[] h = org.web3j.crypto.Hash.sha3(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Sign.SignatureData sd = Sign.signMessage(h, key, false);
        byte[] sig = new byte[65];
        System.arraycopy(sd.getR(), 0, sig, 0, 32);
        System.arraycopy(sd.getS(), 0, sig, 32, 32);
        sig[64] = sd.getV()[0];
        return Numeric.toHexString(sig);
    }

    private static Address computeContractAddress(Address sender, long nonce) {
        Bytes encoded = Bytes.concatenate(sender, Bytes.ofUnsignedLong(nonce));
        org.apache.tuweni.bytes.Bytes32 hash = org.apache.tuweni.crypto.Hash.keccak256(encoded);
        return Address.fromHexString(hash.slice(12, 20).toHexString());
    }

    private static byte[] toRaw64(byte[] pub) {
        if (pub.length == 65 && (pub[0] == 0x04 || pub[0] == 0x00)) {
            byte[] out = new byte[64];
            System.arraycopy(pub, 1, out, 0, 64);
            return out;
        }
        if (pub.length == 64) {
            return pub;
        }
        throw new IllegalArgumentException("Unexpected public key length: " + pub.length);
    }

    private static BareNode newBareNode(EvmExecutor evm) throws Exception {
        Object unsafe = getUnsafe();
        java.lang.reflect.Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        BareNode node = (BareNode) allocate.invoke(unsafe, BareNode.class);

        node.evm = evm;
        node.clientAddrMap = new ConcurrentHashMap<>();
        node.clientPubMap = new ConcurrentHashMap<>();
        node.clientNonceMap = new ConcurrentHashMap<>();
        node.requests = new ArrayList<>();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        node.crypto = new AuthConfig(kp.getPrivate(), kp.getPublic(), new HashMap<>());

        return node;
    }

    private static Object getUnsafe() throws Exception {
        java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    /**
     * Bare HotStuffNode harness: no network, real EVM/validator/commit paths.
     */
    private static final class BareNode extends HotStuffNode {
        EvmExecutor evm;
        Map<Integer, String> clientAddrMap;
        Map<Integer, byte[]> clientPubMap;
        Map<String, Long> clientNonceMap;
        List<String> requests;
        AuthConfig crypto;

        private BareNode() throws Exception {
            super((NodeConfig) null);
        }

        @Override
        public void dbg(String fn) {
            // keep test output clean
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
            return clientNonceMap;
        }

        @Override
        public synchronized void removeClientRequests(String blockdata) {
            // no-op for deterministic integration path
        }

        @Override
        public synchronized void appendCommittedBlock(String blockData, java.util.List<com.g27.DepChain.smartContact.model.Transaction> txs, String blockHash) {
            // no-op for deterministic integration path
        }

        @Override
        public synchronized void sendToClientPublic(String replyData) {
            // no-op for deterministic integration path
        }

        @Override
        public void addLog(String log, boolean isaccomplished) {
            // no-op for deterministic integration path
        }

        @Override
        public AuthConfig getCrypto() {
            return crypto;
        }

        @Override
        public Map<Integer, String> getClientEcdsaAddresses() {
            return Collections.unmodifiableMap(clientAddrMap);
        }

        @Override
        public Map<Integer, byte[]> getClientEcdsaPublicBytes() {
            return Collections.unmodifiableMap(clientPubMap);
        }

        @Override
        public Integer getClientIdForAddress(String addr) {
            if (addr == null) return null;
            String norm = addr.startsWith("0x") ? addr.substring(2).toLowerCase() : addr.toLowerCase();
            for (Map.Entry<Integer, String> e : clientAddrMap.entrySet()) {
                if (norm.equals(e.getValue())) return e.getKey();
            }
            return null;
        }
    }
}
