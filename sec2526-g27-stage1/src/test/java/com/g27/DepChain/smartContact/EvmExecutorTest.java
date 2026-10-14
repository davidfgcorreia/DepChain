package com.g27.DepChain.smartContact;

import com.g27.DepChain.smartContact.abi.AbiEncoder;
import com.g27.DepChain.smartContact.artifacts.ContractArtifacts;
import com.g27.DepChain.smartContact.runtime.DepTransaction;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.g27.DepChain.smartContact.util.PublicKeyUtils.deriveAddressFromPublicKeys;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for EvmExecutor + ISTCoin behavior.
 *
 * Test coverage:
 *   - Contract deployment
 *   - Metadata and supply views
 *   - Native DepCoin transfers
 *   - IST token operations (transfer/approve/allowance/transferFrom)
 *   - Frontrunning resistance (approve guard + decreaseAllowance underflow)
 *   - Gas parameter validation
 *   - Fee formula: min(gasPrice * gasLimit, gasPrice * gasUsed)
 */
class EvmExecutorTest {

        private static final Address ALICE = Address.fromHexString("0x2000000000000000000000000000000000000002");
        private static final Address BOB = Address.fromHexString("0x3000000000000000000000000000000000000003");

    // Large enough to cover all gas + value transfers in every test
    private static final Wei SEED_BALANCE = Wei.of(100_000_000L);

    // ── Shared state (reset before each test) ─────────────────────────────────
        private EvmExecutor evm;
        private Address istAddress;
        private Address richHolder;
        private Address poorHolder;

    @BeforeEach
    void setUp() {
        evm = new EvmExecutor();

        // Derive initial holders early so we can seed their native balances
        richHolder = deriveAddressFromPublicKeys(4);
        poorHolder = deriveAddressFromPublicKeys(5);

        // Seed EOA balances (native DepCoin)
        evm.world().createAccount(ALICE, 0, SEED_BALANCE);
        evm.world().createAccount(BOB, 0, SEED_BALANCE);
        if (richHolder != null) {
            evm.world().createAccount(richHolder, 0, SEED_BALANCE);
        }
        if (poorHolder != null) {
            evm.world().createAccount(poorHolder, 0, SEED_BALANCE);
        }

        // Deploy ISTCoin directly using ContractArtifacts + EvmExecutor
        Bytes init = Bytes.fromHexString(ContractArtifacts.IST_COIN_BYTECODE);
        DepTransaction tx = DepTransaction.builder()
                .sender(richHolder)
                .data(init)
                .gasLimit(3_000_000L)
                .gasPrice(Wei.of(1L))
                .nonce(0L)
                .build();
        EvmExecutor.ExecutionResult deployResult = evm.deployContract(tx);
        istAddress = deployResult.address();
    }

    // ── Deployment ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISTCoin deploys to a distinct non-zero address")
    void istCoinDeployed() {
        assertNotNull(istAddress);
        assertNotEquals(Address.ZERO, istAddress);
        assertNotNull(evm.world().getAccount(istAddress));
    }

    @Test
        @DisplayName("richHolder DepCoin balance is positive after deployment (gas refund applied)")
        void richHolderBalancePositiveAfterDeploy() {
        MutableAccount acc = evm.world().getAccount(richHolder);
        assertNotNull(acc);
        assertTrue(acc.getBalance().compareTo(Wei.ZERO) > 0);
    }

    // ── totalSupply ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISTCoin totalSupply() == 100,000,000 × 10^2 == 10,000,000,000")
    void totalSupply() {
        EvmExecutor.ExecutionResult result =
                call(richHolder, istAddress, AbiEncoder.totalSupply(), 100_000L);

        assertTrue(result.success(), revertMsg(result));
        assertEquals(new BigInteger("10000000000"), AbiEncoder.decodeUint256(result.output()));
    }

        @Test
        @DisplayName("IST metadata matches constructor values")
        void tokenMetadataMatchesConstructorValues() {
                String name = callStringSelector(richHolder, istAddress, Bytes.fromHexString("0x06fdde03")); // name()
                String symbol = callStringSelector(richHolder, istAddress, Bytes.fromHexString("0x95d89b41")); // symbol()
                BigInteger decimals = AbiEncoder.decodeUint256(
                                call(richHolder, istAddress, Bytes.fromHexString("0x313ce567"), 100_000L).output()); // decimals()

                assertEquals("IST Coin", name);
                assertEquals("IST", symbol);
                assertEquals(BigInteger.valueOf(2L), decimals);
        }

    // ── balanceOf ─────────────────────────────────────────────────────────────

        @Test
        @DisplayName("balanceOf(richHolder) equals total supply right after deployment")
        void balanceOfRichHolderEqualsTotalSupply() {
                EvmExecutor.ExecutionResult result = call(richHolder, istAddress, AbiEncoder.balanceOf(richHolder), 100_000L);
                assertTrue(result.success(), revertMsg(result));
                // Current deployer behaviour: deployer (richHolder) receives the full minted supply.
                assertEquals(new BigInteger("10000000000"), AbiEncoder.decodeUint256(result.output()));

                // Verify the poor holder received no allocation in this deployment path.
                EvmExecutor.ExecutionResult poorRes = call(poorHolder, istAddress, AbiEncoder.balanceOf(poorHolder), 100_000L);
                assertTrue(poorRes.success(), revertMsg(poorRes));
                assertEquals(BigInteger.ZERO, AbiEncoder.decodeUint256(poorRes.output()));
        }

    @Test
    @DisplayName("balanceOf(Alice) == 0 before any transfer")
    void aliceBalanceZeroInitially() {
        EvmExecutor.ExecutionResult result =
                call(richHolder, istAddress, AbiEncoder.balanceOf(ALICE), 100_000L);

        assertTrue(result.success(), revertMsg(result));
        assertEquals(BigInteger.ZERO, AbiEncoder.decodeUint256(result.output()));
    }

        @Test
        @DisplayName("allowance(owner, spender) starts at zero")
        void allowanceStartsAtZero() {
                EvmExecutor.ExecutionResult result =
                                call(richHolder, istAddress, AbiEncoder.allowance(richHolder, ALICE), 100_000L);

                assertTrue(result.success(), revertMsg(result));
                assertEquals(BigInteger.ZERO, AbiEncoder.decodeUint256(result.output()));
        }

    // ── transfer ──────────────────────────────────────────────────────────────

        @Test
        @DisplayName("transfer moves IST tokens from richHolder to Alice")
        void istTransferRichHolderToAlice() {
        BigInteger amount = BigInteger.valueOf(1_000L);
                // richHolder holds the initial tokens; use the funded rich holder as sender.
        EvmExecutor.ExecutionResult transfer =
                call(richHolder, istAddress, AbiEncoder.transfer(ALICE, amount), 100_000L);

        assertTrue(transfer.success(), revertMsg(transfer));

        BigInteger aliceBal = AbiEncoder.decodeUint256(
                call(ALICE, istAddress, AbiEncoder.balanceOf(ALICE), 50_000L).output());

        assertEquals(amount, aliceBal);
    }

    @Test
    @DisplayName("transfer reduces sender IST balance correctly")
    void transferReducesSenderBalance() {
        BigInteger amount      = BigInteger.valueOf(500L);
        // Use the funded rich holder as sender for transfer tests.
        call(richHolder, istAddress, AbiEncoder.transfer(ALICE, amount), 100_000L);

        BigInteger richBal = AbiEncoder.decodeUint256(
                call(richHolder, istAddress, AbiEncoder.balanceOf(richHolder), 50_000L).output());

        assertEquals(new BigInteger("9999999500"), richBal);
    }

        @Test
        @DisplayName("transfer reverts when sender has insufficient IST balance")
        void transferRevertsWhenAmountExceedsSenderBalance() {
                // ALICE starts with 0 IST in this setup.
                EvmExecutor.ExecutionResult result =
                                call(ALICE, istAddress, AbiEncoder.transfer(BOB, BigInteger.ONE), 100_000L);

                assertFalse(result.success(), "transfer from zero IST balance must fail");
        }

        @Test
        @DisplayName("transfer to zero address reverts")
        void transferToZeroAddressReverts() {
                EvmExecutor.ExecutionResult result =
                                call(richHolder, istAddress, AbiEncoder.transfer(Address.ZERO, BigInteger.ONE), 100_000L);

                assertFalse(result.success(), "transfer to zero address must fail");
        }

    // ── Native DepCoin transfer ───────────────────────────────────────────────

    @Test
    @DisplayName("Native DepCoin transfer moves value and charges 21,000 gas")
    void nativeDepCoinTransfer() {
        Wei amount = Wei.of(1_000L);

        DepTransaction tx = DepTransaction.builder()
                .sender(ALICE)
                .to(BOB)
                .value(amount)
                .data(Bytes.EMPTY)
                .gasLimit(21_000L)
                .gasPrice(Wei.of(1L))
                .nonce(0L)
                .build();

        EvmExecutor.ExecutionResult result = evm.transfer(tx);

        assertTrue(result.success());
        assertEquals(21_000L, result.gasUsed());

        Wei bobBalance = evm.world().getAccount(BOB).getBalance();
        assertEquals(SEED_BALANCE.add(amount), bobBalance);
    }

    @Test
    @DisplayName("Native transfer fails when gasLimit < 21,000")
    void nativeTransferFailsBelowIntrinsicGas() {
        DepTransaction tx = DepTransaction.builder()
                .sender(ALICE)
                .to(BOB)
                .value(Wei.of(1L))
                .data(Bytes.EMPTY)
                .gasLimit(20_999L)
                .gasPrice(Wei.of(1L))
                .nonce(0L)
                .build();

        EvmExecutor.ExecutionResult result = evm.transfer(tx);
        assertFalse(result.success());
    }

    @Test
    @DisplayName("Native transfer throws when sender has insufficient DepCoin balance")
    void nativeTransferInsufficientBalance() {
        DepTransaction tx = DepTransaction.builder()
                .sender(ALICE)
                .to(BOB)
                .value(Wei.of(Long.MAX_VALUE))
                .data(Bytes.EMPTY)
                .gasLimit(21_000L)
                .gasPrice(Wei.of(1L))
                .nonce(0L)
                .build();

        assertThrows(IllegalStateException.class, () -> evm.transfer(tx));
    }

        // AccessControl contract removed; Access control specific tests removed.

    // ── Frontrunning resistance ───────────────────────────────────────────────

    @Test
    @DisplayName("approve(N→M) reverts when current allowance != 0 (frontrunning guard)")
    void approveNonZeroToNonZeroReverts() {
        // First approval: 0 → 100 (allowed)
        call(richHolder, istAddress,
                AbiEncoder.approve(ALICE, BigInteger.valueOf(100L)), 100_000L);

        // Second direct approval: 100 → 50 (must revert — use decreaseAllowance instead)
        EvmExecutor.ExecutionResult result =
                call(richHolder, istAddress,
                        AbiEncoder.approve(ALICE, BigInteger.valueOf(50L)), 100_000L);

        assertFalse(result.success(),
                "approve(N→M) where both N,M > 0 must revert (frontrunning protection)");
    }

    @Test
    @DisplayName("approve(N→0) succeeds — revoking allowance is always safe")
    void approveToZeroSucceeds() {
        call(richHolder, istAddress,
                AbiEncoder.approve(ALICE, BigInteger.valueOf(100L)), 100_000L);

        EvmExecutor.ExecutionResult result =
                call(richHolder, istAddress,
                        AbiEncoder.approve(ALICE, BigInteger.ZERO), 100_000L);

        assertTrue(result.success(), revertMsg(result));
    }

        @Test
        @DisplayName("approve to zero address reverts")
        void approveToZeroAddressReverts() {
                EvmExecutor.ExecutionResult result =
                                call(richHolder, istAddress, AbiEncoder.approve(Address.ZERO, BigInteger.ONE), 100_000L);

                assertFalse(result.success(), "approve to zero address must fail");
        }

    @Test
    @DisplayName("decreaseAllowance reverts if frontrunner already drained the allowance")
    void decreaseAllowanceRevertsAfterFrontrunnerDrains() {
        // richHolder gives ALICE an allowance of 100
        call(richHolder, istAddress,
                AbiEncoder.approve(ALICE, BigInteger.valueOf(100L)), 100_000L);

        // ALICE (acting as frontrunner) drains all 100 via transferFrom
        call(ALICE, istAddress,
                AbiEncoder.transferFrom(richHolder, ALICE, BigInteger.valueOf(100L)), 100_000L);

        // Rich holder now tries to decreaseAllowance by 50 — must revert (0 - 50 underflows)
        EvmExecutor.ExecutionResult result =
                call(richHolder, istAddress,
                        AbiEncoder.decreaseAllowance(ALICE, BigInteger.valueOf(50L)), 100_000L);

        assertFalse(result.success(),
                "decreaseAllowance must revert when allowance already drained by frontrunner");
    }

    @Test
    @DisplayName("increaseAllowance + decreaseAllowance adjust atomically")
    void increaseAndDecreaseAllowanceAtomic() {
        call(richHolder, istAddress,
                AbiEncoder.increaseAllowance(ALICE, BigInteger.valueOf(200L)), 100_000L);

        call(richHolder, istAddress,
                AbiEncoder.decreaseAllowance(ALICE, BigInteger.valueOf(50L)), 100_000L);

        BigInteger allowance = AbiEncoder.decodeUint256(
                call(richHolder, istAddress,
                        AbiEncoder.allowance(richHolder, ALICE), 50_000L).output());

        assertEquals(BigInteger.valueOf(150L), allowance);
    }

    @Test
    @DisplayName("transferFrom respects allowance and deducts it correctly")
    void transferFromDeductsAllowance() {
        BigInteger amount = BigInteger.valueOf(300L);
        // Rich holder gives ALICE allowance of 300
        call(richHolder, istAddress, AbiEncoder.approve(ALICE, amount), 100_000L);

        // ALICE spends 200 on behalf of rich holder
        call(ALICE, istAddress,
                AbiEncoder.transferFrom(richHolder, BOB, BigInteger.valueOf(200L)), 100_000L);

        // Remaining allowance must be 100
        BigInteger remaining = AbiEncoder.decodeUint256(
                call(richHolder, istAddress,
                        AbiEncoder.allowance(richHolder, ALICE), 50_000L).output());

        assertEquals(BigInteger.valueOf(100L), remaining);
    }

        @Test
        @DisplayName("transferFrom fails when there is no allowance")
        void transferFromWithoutAllowanceReverts() {
                EvmExecutor.ExecutionResult result =
                                call(ALICE, istAddress, AbiEncoder.transferFrom(richHolder, BOB, BigInteger.ONE), 100_000L);

                assertFalse(result.success(), "transferFrom without allowance must fail");
        }

        @Test
        @DisplayName("transferFrom fails when amount exceeds allowance")
        void transferFromExceedingAllowanceReverts() {
                call(richHolder, istAddress, AbiEncoder.approve(ALICE, BigInteger.valueOf(100L)), 100_000L);

                EvmExecutor.ExecutionResult result =
                                call(ALICE, istAddress, AbiEncoder.transferFrom(richHolder, BOB, BigInteger.valueOf(101L)), 100_000L);

                assertFalse(result.success(), "transferFrom above approved amount must fail");
        }

    // ── Gas validation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("gasPrice == 0 is rejected at build time")
    void zeroGasPriceRejected() {
        assertThrows(IllegalStateException.class, () ->
                DepTransaction.builder()
                        .sender(ALICE).to(BOB)
                        .value(Wei.ZERO).data(Bytes.EMPTY)
                        .gasLimit(21_000L).gasPrice(Wei.ZERO)
                        .nonce(0L).build());
    }

    @Test
    @DisplayName("gasLimit == 0 is rejected at build time")
    void zeroGasLimitRejected() {
        assertThrows(IllegalStateException.class, () ->
                DepTransaction.builder()
                        .sender(ALICE).to(BOB)
                        .value(Wei.ZERO).data(Bytes.EMPTY)
                        .gasLimit(0L).gasPrice(Wei.of(1L))
                        .nonce(0L).build());
    }

    // ── Fee formula ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("actualFee == gasPrice * gasUsed when gasUsed < gasLimit")
    void actualFeeUsesGasUsedWhenUnderLimit() {
        DepTransaction tx = DepTransaction.builder()
                .sender(ALICE).to(BOB)
                .value(Wei.ZERO).data(Bytes.EMPTY)
                .gasLimit(100_000L).gasPrice(Wei.of(2L))
                .nonce(0L).build();

        // gasUsed=30_000 < gasLimit=100_000 → fee = 2 * 30_000
        assertEquals(Wei.of(60_000L), tx.actualFee(30_000L));
    }

    @Test
    @DisplayName("actualFee is capped at gasPrice * gasLimit when gasUsed > gasLimit")
    void actualFeeCappedAtGasLimit() {
        DepTransaction tx = DepTransaction.builder()
                .sender(ALICE).to(BOB)
                .value(Wei.ZERO).data(Bytes.EMPTY)
                .gasLimit(100_000L).gasPrice(Wei.of(2L))
                .nonce(0L).build();

        // gasUsed=200_000 > gasLimit=100_000 → fee capped at 2 * 100_000
        assertEquals(Wei.of(200_000L), tx.actualFee(200_000L));
    }

    @Test
    @DisplayName("maxFee == gasPrice * gasLimit")
    void maxFeeFormula() {
        DepTransaction tx = DepTransaction.builder()
                .sender(ALICE).to(BOB)
                .value(Wei.ZERO).data(Bytes.EMPTY)
                .gasLimit(50_000L).gasPrice(Wei.of(3L))
                .nonce(0L).build();

        assertEquals(Wei.of(150_000L), tx.maxFee());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

        // deriveAddressFromPublicKeys moved to PublicKeyUtils

    /**
     * Build and execute a contract call, auto-incrementing the sender's nonce.
     */
    private EvmExecutor.ExecutionResult call(
            Address sender, Address contract, Bytes callData, long gasLimit) {

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

        // Increment nonce to prevent replay across calls in the same test
        if (acc != null) acc.setNonce(nonce + 1);

        return result;
    }

    /**
     * Extract a human-readable revert reason for use in assertion failure messages.
     */
    private String revertMsg(EvmExecutor.ExecutionResult result) {
        return result.revertReason()
                .map(b -> new String(b.toArrayUnsafe()))
                .orElse("execution failed with no revert reason");
    }

        private String callStringSelector(Address sender, Address contract, Bytes selector) {
                EvmExecutor.ExecutionResult result = call(sender, contract, selector, 100_000L);
                assertTrue(result.success(), revertMsg(result));
                return decodeAbiString(result.output());
        }

        private String decodeAbiString(Bytes returnData) {
                byte[] all = returnData.toArrayUnsafe();
                if (all.length < 96) {
                        throw new IllegalArgumentException("Return data too short for ABI string: " + all.length);
                }

                int offset = new BigInteger(1, Arrays.copyOfRange(all, 0, 32)).intValueExact();
                int lenStart = offset;
                int dataLen = new BigInteger(1, Arrays.copyOfRange(all, lenStart, lenStart + 32)).intValueExact();
                int dataStart = lenStart + 32;
                return new String(all, dataStart, dataLen, StandardCharsets.UTF_8);
        }
}
