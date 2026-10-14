package com.g27.DepChain.SmartContract;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import com.g27.DepChain.smartContact.artifacts.ContractArtifacts;
import com.g27.DepChain.smartContact.abi.AbiEncoder;
import com.g27.DepChain.smartContact.runtime.DepTransaction;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static com.g27.DepChain.smartContact.util.PublicKeyUtils.deriveAddressFromPublicKeys;

class FrontrunningPreventionTest {

    private static final Address ALICE    = Address.fromHexString("0x2000000000000000000000000000000000000002");
    private static final Address BOB      = Address.fromHexString("0x3000000000000000000000000000000000000003");

    private static final Wei SEED_BALANCE = Wei.of(100_000_000L);

    private EvmExecutor evm;
    private Address istAddress;
    private Address richHolder;
    private Address tokenOwner;

    @BeforeEach
    void setup() {
        evm = new EvmExecutor();

        richHolder = deriveAddressFromPublicKeys(4);
        if (richHolder != null) evm.world().createAccount(richHolder, 0, SEED_BALANCE);
        evm.world().createAccount(ALICE, 0, SEED_BALANCE);
        evm.world().createAccount(BOB, 0, SEED_BALANCE);

        // Deploy using the derived rich holder as deployer (deployer == richHolder)
        org.apache.tuweni.bytes.Bytes init = org.apache.tuweni.bytes.Bytes.fromHexString(ContractArtifacts.IST_COIN_BYTECODE);
        com.g27.DepChain.smartContact.runtime.DepTransaction tx = com.g27.DepChain.smartContact.runtime.DepTransaction.builder()
            .sender(richHolder)
            .data(init)
            .gasLimit(3_000_000L)
            .gasPrice(Wei.of(1L))
            .nonce(0L)
            .build();
        com.g27.DepChain.smartContact.runtime.EvmExecutor.ExecutionResult dr = evm.deployContract(tx);
        istAddress = dr.address();

        // Use the derived rich holder as the canonical token owner.
        tokenOwner = richHolder;
    }

    @Test
    @DisplayName("approve non-zero → non-zero must revert (frontrunning guard)")
    void approveNonZeroToNonZeroReverts() {
        EvmExecutor.ExecutionResult r1 = call(tokenOwner, istAddress, AbiEncoder.approve(ALICE, BigInteger.valueOf(100L)), 100_000L);
        assertTrue(r1.success(), "initial approve should succeed");

        EvmExecutor.ExecutionResult r2 = call(tokenOwner, istAddress, AbiEncoder.approve(ALICE, BigInteger.valueOf(50L)), 100_000L);
        assertFalse(r2.success(), "approve(N→M) where both N,M > 0 must revert (frontrunning protection)");
    }

    @Test
    @DisplayName("drain by frontrunner then decreaseAllowance reverts")
    void drainThenDecreaseReverts() {
        // Approve from the actual token owner (the derived rich holder when present)
        call(tokenOwner, istAddress, AbiEncoder.approve(ALICE, BigInteger.valueOf(100L)), 100_000L);

        EvmExecutor.ExecutionResult drain = call(ALICE, istAddress, AbiEncoder.transferFrom(tokenOwner, BOB, BigInteger.valueOf(100L)), 100_000L);
        assertTrue(drain.success(), "frontrunner should be able to transferFrom up to allowance");

        EvmExecutor.ExecutionResult dec = call(tokenOwner, istAddress, AbiEncoder.decreaseAllowance(ALICE, BigInteger.valueOf(50L)), 100_000L);
        assertFalse(dec.success(), "decreaseAllowance must revert when allowance already drained by frontrunner");
    }

    // deriveAddressFromPublicKeys moved to PublicKeyUtils

    private EvmExecutor.ExecutionResult call(Address sender, Address contract, Bytes callData, long gasLimit) {
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
}
