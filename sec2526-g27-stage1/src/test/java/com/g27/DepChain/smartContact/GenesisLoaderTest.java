package com.g27.DepChain.smartContact.genesis;

import com.g27.DepChain.smartContact.abi.AbiEncoder;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import com.g27.DepChain.smartContact.runtime.DepTransaction;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static com.g27.DepChain.smartContact.util.PublicKeyUtils.deriveAddressFromPublicKeys;

class GenesisLoaderTest {

    @Test
    void loadDefaultGenesisDeploysContractsAndSeedsDeployer() {
        EvmExecutor evm = new EvmExecutor();

        // Generate a genesis JSON file and then apply it to the EVM world
        String generated = GenesisGenerator.generateGenesisJson();
        assertNotNull(generated, "GenesisGenerator should produce a genesis JSON string");

        String applied = GenesisLoader.applyGenesisFromFile(evm);
        assertNotNull(applied, "GenesisLoader.applyGenesisFromFile should return the applied genesis string");

        // Derived client addresses should be seeded by the generator
        Address client4 = deriveAddressFromPublicKeys(4);
        Address client5 = deriveAddressFromPublicKeys(5);
        Address client6 = deriveAddressFromPublicKeys(6);

        assertNotNull(client4, "client 4 must be present in config/public_keys.json");
        assertNotNull(evm.world().getAccount(client4));
        assertNotNull(client5, "client 5 must be present in config/public_keys.json");
        assertNotNull(client6, "client 6 must be present in config/public_keys.json");

        org.json.JSONObject g = new org.json.JSONObject(generated);
        org.json.JSONArray txs = g.optJSONArray("transactions");
        assertNotNull(txs, "genesis must include deployment transactions");
        assertTrue(txs.length() > 0, "genesis must include at least one deployment transaction");

        org.json.JSONObject deployTx = txs.getJSONObject(0);
        String fromHex = deployTx.optString("from", null);
        assertNotNull(fromHex, "first genesis tx must include deployer address");

        Address deployer = Address.fromHexString(fromHex);
        assertEquals(client4, deployer, "client 4 should be the canonical genesis deployer");

        Address ist = computeContractAddress(deployer, 0L);
        MutableAccount istAccount = evm.world().getAccount(ist);
        assertNotNull(istAccount, "IST contract account must exist after genesis load");
        assertNotNull(istAccount.getCode(), "IST contract code must be set");
        assertFalse(istAccount.getCode().isEmpty(), "IST contract code must be non-empty");

        BigInteger decimals = callUintView(evm, deployer, ist, Bytes.fromHexString("0x313ce567")); // decimals()
        assertEquals(BigInteger.valueOf(2L), decimals, "IST decimals must be 2");

        String name = callStringView(evm, deployer, ist, Bytes.fromHexString("0x06fdde03")); // name()
        String symbol = callStringView(evm, deployer, ist, Bytes.fromHexString("0x95d89b41")); // symbol()
        assertEquals("IST Coin", name, "IST name mismatch");
        assertEquals("IST", symbol, "IST symbol mismatch");

        BigInteger totalSupply = callUintView(evm, deployer, ist, AbiEncoder.totalSupply());
        assertEquals(new BigInteger("10000000000"), totalSupply, "IST totalSupply mismatch");

        BigInteger deployerIst = callUintView(evm, deployer, ist, AbiEncoder.balanceOf(deployer));
        BigInteger client5Ist = callUintView(evm, deployer, ist, AbiEncoder.balanceOf(client5));
        BigInteger client6Ist = callUintView(evm, deployer, ist, AbiEncoder.balanceOf(client6));

        assertEquals(totalSupply, deployerIst, "deployer must hold the full IST supply at genesis");
        assertEquals(BigInteger.ZERO, client5Ist, "client 5 should not hold IST at genesis");
        assertEquals(BigInteger.ZERO, client6Ist, "client 6 should not hold IST at genesis");
        assertEquals(totalSupply, deployerIst.add(client5Ist).add(client6Ist),
                "token conservation mismatch across genesis test holders");

        // Verify clients 4/5/6 are seeded according to generator behaviour
        assertNotNull(evm.world().getAccount(client5));
        assertEquals(Wei.of(100_000_000L), evm.world().getAccount(client5).getBalance());
        assertNotNull(evm.world().getAccount(client6));
        assertEquals(Wei.of(100_000_000L), evm.world().getAccount(client6).getBalance());

        Wei finalBal = evm.world().getAccount(client4).getBalance();
        Wei initial = Wei.of(100_000_000L);
        Wei maxFee = Wei.of(3_000_000L); // gasLimit (3_000_000) * gasPrice (1)
        assertTrue(finalBal.compareTo(Wei.ZERO) > 0);
        assertTrue(finalBal.compareTo(initial) <= 0);
        assertTrue(finalBal.compareTo(initial.subtract(maxFee)) >= 0);
        assertTrue(finalBal.compareTo(initial) < 0, "deployer used; balance must be less than initial");
    }

    private static EvmExecutor.ExecutionResult call(EvmExecutor evm, Address sender, Address contract, Bytes callData, long gasLimit) {
        MutableAccount acc = evm.world().getAccount(sender);
        long nonce = (acc == null) ? 0L : acc.getNonce();

        DepTransaction tx = DepTransaction.builder()
                .sender(sender)
                .to(contract)
                .value(Wei.ZERO)
                .data(callData)
                .gasLimit(gasLimit)
                .gasPrice(Wei.of(1L))
                .nonce(nonce)
                .build();

        EvmExecutor.ExecutionResult result = evm.callContract(tx);
        if (acc != null) acc.setNonce(nonce + 1);
        return result;
    }

    private static BigInteger callUintView(EvmExecutor evm, Address sender, Address contract, Bytes callData) {
        EvmExecutor.ExecutionResult result = call(evm, sender, contract, callData, 200_000L);
        assertTrue(result.success(), "view call failed: " + result.revertReason().map(Object::toString).orElse("no revert reason"));
        return AbiEncoder.decodeUint256(result.output());
    }

    private static String callStringView(EvmExecutor evm, Address sender, Address contract, Bytes callData) {
        EvmExecutor.ExecutionResult result = call(evm, sender, contract, callData, 200_000L);
        assertTrue(result.success(), "view call failed: " + result.revertReason().map(Object::toString).orElse("no revert reason"));
        return decodeAbiString(result.output());
    }

    private static String decodeAbiString(Bytes returnData) {
        byte[] all = returnData.toArrayUnsafe();
        if (all.length < 96) {
            throw new IllegalArgumentException("Return data too short for ABI string: " + all.length);
        }

        int offset = new BigInteger(1, java.util.Arrays.copyOfRange(all, 0, 32)).intValueExact();
        int lenStart = offset;
        int dataLen = new BigInteger(1, java.util.Arrays.copyOfRange(all, lenStart, lenStart + 32)).intValueExact();
        int dataStart = lenStart + 32;
        return new String(all, dataStart, dataLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Address computeContractAddress(Address sender, long nonce) {
        Bytes encoded = Bytes.concatenate(sender, Bytes.ofUnsignedLong(nonce));
        Bytes32 hash = Hash.keccak256(encoded);
        return Address.fromHexString(hash.slice(12, 20).toHexString());
    }

}
