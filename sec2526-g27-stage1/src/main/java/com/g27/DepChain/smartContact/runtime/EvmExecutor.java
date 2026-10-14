package com.g27.DepChain.smartContact.runtime;


import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.MainnetPrecompiledContracts;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.Optional;

/**
 * Wraps the Hyperledger Besu EVM library (Apache 2.0).
 *
 * Provides:
 *  - {@link #deployContract}  – runs init-bytecode and persists the contract account.
 *  - {@link #callContract}    – ABI-calls a deployed contract and returns output bytes.
 *  - {@link #transfer}        – native DepCoin transfer (no EVM execution).
 */
public class EvmExecutor {

    // ── Besu EVM instance ─────────────────────────────────────────────────────
    private final EVM evm;

    // ── In-memory world state ─────────────────────────────────────────────────
    private final SimpleWorld world;

    // ── Cancun precompiles (required by Besu 25.x EVMExecutor) ───────────────
    private final PrecompileContractRegistry precompiles;

    // ─────────────────────────────────────────────────────────────────────────

    public EvmExecutor() {
        this.evm        = MainnetEVMs.cancun(EvmConfiguration.DEFAULT);
        this.world      = new SimpleWorld();
        this.precompiles = MainnetPrecompiledContracts.cancun(evm.getGasCalculator());
    }

    public EvmExecutor(SimpleWorld existingWorld) {
        this.evm        = MainnetEVMs.cancun(EvmConfiguration.DEFAULT);
        this.world      = existingWorld;
        this.precompiles = MainnetPrecompiledContracts.cancun(evm.getGasCalculator());
    }

    /**
     * Perform a native DepCoin transfer between accounts (no EVM execution).
     * Charges the sender the max fee immediately and finalises gas accounting.
     */
    public ExecutionResult transfer(DepTransaction tx) {
        if (tx.isContractCreation()) throw new IllegalArgumentException("transfer requires a recipient");
        // intrinsic gas for a native transfer (same as Ethereum's 21_000)
        final long INTRINSIC_TRANSFER_GAS = 21_000L;
        if (tx.getGasLimit() < INTRINSIC_TRANSFER_GAS) {
            // do not charge the sender if intrinsic gas requirement not met
            return new ExecutionResult(false, tx.getTo().orElseThrow(), tx.getGasLimit(), Bytes.EMPTY, Optional.empty());
        }

        deductMaxFee(tx);
        Address to = tx.getTo().orElseThrow(() -> new IllegalArgumentException("transfer requires to"));
        MutableAccount sender = world.getAccount(tx.getSender());
        MutableAccount receiver = world.getAccount(to);
        if (sender == null) throw new IllegalStateException("Sender account not found");
        if (receiver == null) {
            // create recipient with zero balance if missing
            world.createAccount(to, 0, Wei.ZERO);
            receiver = world.getAccount(to);
        }

        long gasCharged = INTRINSIC_TRANSFER_GAS;

        // perform value transfer (naive, no overflow checks beyond Wei)
        long senderBal = sender.getBalance().toLong();
        long val = tx.getValue().toLong();
        if (senderBal < val) throw new IllegalStateException("insufficient balance for transfer");
        sender.setBalance(Wei.of(senderBal - val));
        receiver.setBalance(Wei.of(receiver.getBalance().toLong() + val));

        finaliseGas(tx, gasCharged);

        return new ExecutionResult(true, to, gasCharged, Bytes.EMPTY, Optional.empty());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public SimpleWorld world() { return world; }

    /**
     * Deploy a smart contract (tx.to == null).
     * The init bytecode is executed; its RETURN value becomes the runtime code.
     */
    public ExecutionResult deployContract(DepTransaction tx) {
        if (!tx.isContractCreation()) {
            throw new IllegalArgumentException("deployContract requires a creation transaction");
        }

        deductMaxFee(tx);

        Address contractAddress = generateContractAddress(tx.getSender(), tx.getNonce());

        // Pre-create the account so the EVM can write storage into it
        if (world.getAccount(contractAddress) == null) {
            world.createAccount(contractAddress, 0, Wei.ZERO);
        }

        // Capture the runtime code from getOutputData() via a tracer.
        final Bytes[] capturedOutput = { Bytes.EMPTY };
        final long[] capturedRemainingGas = { -1L };
        final boolean[] deployReverted = { false };

        EVMExecutor.evm(evm)
                .worldUpdater(world)
                .commitWorldState(true)
                .precompileContractRegistry(precompiles)
                .messageFrameType(MessageFrame.Type.CONTRACT_CREATION)
                .sender(tx.getSender())
                .receiver(contractAddress)
                .contract(contractAddress)
                .gas(tx.getGasLimit())
                .callData(Bytes.EMPTY)
                .code(tx.getData())
                .tracer(new OperationTracer() {
                    @Override
                    public void traceContextExit(MessageFrame frame) {
                        Bytes out = frame.getOutputData();
                        if (out != null && !out.isEmpty()) {
                            capturedOutput[0] = out;
                        }

                        // try to capture remaining gas from frame via reflection
                        String[] remCandidates = new String[]{"getRemainingGas","getGasRemaining","getRemainingGasUnits","getGasLeft","getRemainingGasAmount"};
                        for (String name : remCandidates) {
                            try {
                                java.lang.reflect.Method m = frame.getClass().getMethod(name);
                                Object val = m.invoke(frame);
                                if (val instanceof Number) { capturedRemainingGas[0] = ((Number) val).longValue(); break; }
                                if (val instanceof Bytes) { capturedRemainingGas[0] = ((Bytes) val).toLong(); break; }
                                if (val != null) { try { capturedRemainingGas[0] = Long.parseLong(val.toString()); break; } catch (Throwable ignore) {} }
                            } catch (NoSuchMethodException ignored) {}
                            catch (Throwable ignore) {}
                        }

                        // detect obvious revert/halt via frame inspection
                        String[] candidates = new String[]{"getExceptionalHaltReason","getRevertReason","getExceptionalHalt","getHaltReason","getState","getStatus","isRevert"};
                        for (String name : candidates) {
                            try {
                                java.lang.reflect.Method mm = frame.getClass().getMethod(name);
                                Object val = mm.invoke(frame);
                                if (val == null) continue;
                                String s = val.toString();
                                if (s != null && !s.isEmpty()) {
                                    String up = s.toUpperCase();
                                    if (up.contains("REVERT") || up.contains("HALT") || up.contains("EXCEPTION") || up.contains("INVALID") || up.contains("FAIL")) {
                                        deployReverted[0] = true;
                                        break;
                                    }
                                }
                                try { java.lang.reflect.Method nameM = val.getClass().getMethod("name"); Object nm = nameM.invoke(val); if (nm != null) { String up = nm.toString().toUpperCase(); if (up.contains("REVERT")||up.contains("HALT")||up.contains("FAIL")) { deployReverted[0] = true; break; } } } catch (Throwable ignore) {}
                            } catch (NoSuchMethodException ignored) { }
                            catch (Throwable ignore) { }
                        }

                    }
                })
                .execute();

        Bytes runtimeCode = capturedOutput[0];
        boolean success = !runtimeCode.isEmpty() && !deployReverted[0];

        if (success) {
            MutableAccount contract = world.getAccount(contractAddress);
            if (contract != null) {
                contract.setCode(runtimeCode);
            }
        }

        long gasCharged;
        if (capturedRemainingGas[0] >= 0) {
            gasCharged = Math.max(0L, tx.getGasLimit() - capturedRemainingGas[0]);
        } else {
            gasCharged = tx.getGasLimit();
        }

        finaliseGas(tx, gasCharged);

        Optional<Bytes> revertReason = deployReverted[0] ? Optional.of(Bytes.EMPTY) : Optional.empty();
        return new ExecutionResult(success, contractAddress, gasCharged, capturedOutput[0], revertReason);
    }

    /**
     * Execute a message call to an existing contract account.
     */
    public ExecutionResult callContract(DepTransaction tx) {
        if (tx.isContractCreation()) throw new IllegalArgumentException("callContract requires a recipient");

        deductMaxFee(tx);

        Address to = tx.getTo().orElseThrow(() -> new IllegalArgumentException("callContract requires to"));

        final Bytes[] capturedOutput = { Bytes.EMPTY };
        final Optional<Bytes>[] capturedRevert = new Optional[]{ Optional.empty() };
        final boolean[] success = { true };
        final long[] capturedRemainingGas = { -1L };

        // Prefer to capture the EVMExecutor execution result (if present) via reflection
        try {
            Object builder = org.hyperledger.besu.evm.fluent.EVMExecutor.evm(evm);
            Class<?> cls = builder.getClass();

            java.lang.reflect.Method m;

            // helper: find a method by name accepting a compatible single parameter
            java.util.function.BiFunction<String, Object, java.lang.reflect.Method> findMethod = (name, arg) -> {
                for (java.lang.reflect.Method mm : cls.getMethods()) {
                    if (!mm.getName().equals(name)) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length != 1) continue;
                    if (arg == null || ps[0].isAssignableFrom(arg.getClass())) return mm;
                    // handle primitive types
                    if (ps[0].isPrimitive()) {
                        Class<?> boxed = ps[0] == boolean.class ? Boolean.class : ps[0] == long.class ? Long.class : ps[0] == int.class ? Integer.class : null;
                        if (boxed != null && boxed.isAssignableFrom(arg.getClass())) return mm;
                    }
                }
                return (java.lang.reflect.Method) null;
            };

            m = findMethod.apply("worldUpdater", world); if (m == null) throw new NoSuchMethodException("worldUpdater"); builder = m.invoke(builder, world);
            m = findMethod.apply("commitWorldState", true); if (m == null) throw new NoSuchMethodException("commitWorldState"); builder = m.invoke(builder, true);
            m = findMethod.apply("precompileContractRegistry", precompiles); if (m == null) throw new NoSuchMethodException("precompileContractRegistry"); builder = m.invoke(builder, precompiles);
            m = findMethod.apply("messageFrameType", MessageFrame.Type.MESSAGE_CALL); if (m == null) throw new NoSuchMethodException("messageFrameType"); builder = m.invoke(builder, MessageFrame.Type.MESSAGE_CALL);
            m = findMethod.apply("sender", tx.getSender()); if (m == null) throw new NoSuchMethodException("sender"); builder = m.invoke(builder, tx.getSender());
            m = findMethod.apply("receiver", to); if (m == null) throw new NoSuchMethodException("receiver"); builder = m.invoke(builder, to);
            m = findMethod.apply("contract", to); if (m == null) throw new NoSuchMethodException("contract"); builder = m.invoke(builder, to);
            m = findMethod.apply("gas", tx.getGasLimit()); if (m == null) throw new NoSuchMethodException("gas"); builder = m.invoke(builder, tx.getGasLimit());
            m = findMethod.apply("callData", tx.getData()); if (m == null) throw new NoSuchMethodException("callData"); builder = m.invoke(builder, tx.getData());
            Bytes codeBytes = (world.getAccount(to) != null) ? world.getAccount(to).getCode() : Bytes.EMPTY;
            m = findMethod.apply("code", codeBytes); if (m == null) throw new NoSuchMethodException("code"); builder = m.invoke(builder, codeBytes);

            OperationTracer tracer = new OperationTracer() {
                @Override
                public void traceContextExit(MessageFrame frame) {
                    Bytes out = frame.getOutputData();
                    if (out != null && !out.isEmpty()) capturedOutput[0] = out;

                    // try to capture remaining gas from frame via reflection
                    String[] remCandidates = new String[]{"getRemainingGas","getGasRemaining","getRemainingGasUnits","getGasLeft","getRemainingGasAmount"};
                    for (String name : remCandidates) {
                        try {
                            java.lang.reflect.Method m = frame.getClass().getMethod(name);
                            Object val = m.invoke(frame);
                            if (val instanceof Number) { capturedRemainingGas[0] = ((Number) val).longValue(); break; }
                            if (val instanceof Bytes) { capturedRemainingGas[0] = ((Bytes) val).toLong(); break; }
                            if (val != null) { try { capturedRemainingGas[0] = Long.parseLong(val.toString()); break; } catch (Throwable ignore) {} }
                        } catch (NoSuchMethodException ignored) {}
                        catch (Throwable ignore) {}
                    }

                    // Inspect MessageFrame via reflection for revert/exception indicators
                    String[] candidates = new String[]{
                            "getExceptionalHaltReason",
                            "getRevertReason",
                            "getExceptionalHalt",
                            "getHaltReason",
                            "getState",
                            "getStatus",
                            "isRevert"
                    };
                    for (String name : candidates) {
                        try {
                            java.lang.reflect.Method mm = frame.getClass().getMethod(name);
                            Object val = mm.invoke(frame);
                            if (val == null) continue;
                            String s = val.toString();
                            if (s != null && !s.isEmpty()) {
                                String up = s.toUpperCase();
                                if (up.contains("REVERT") || up.contains("HALT") || up.contains("EXCEPTION") || up.contains("INVALID") || up.contains("FAIL")) {
                                    success[0] = false;
                                    if (out != null && !out.isEmpty()) capturedRevert[0] = Optional.of(out);
                                    break;
                                }
                            }
                            // enums: try name()
                            try {
                                java.lang.reflect.Method nameM = val.getClass().getMethod("name");
                                Object nm = nameM.invoke(val);
                                if (nm != null) {
                                    String up = nm.toString().toUpperCase();
                                    if (up.contains("REVERT") || up.contains("HALT") || up.contains("FAIL")) {
                                        success[0] = false;
                                        if (out != null && !out.isEmpty()) capturedRevert[0] = Optional.of(out);
                                        break;
                                    }
                                }
                            } catch (Throwable ignore) {}
                        } catch (NoSuchMethodException ignored) {
                            // try next
                        } catch (Throwable ignore) {
                            // ignore reflection issues
                        }
                    }
                }
            };
            m = cls.getMethod("tracer", OperationTracer.class); builder = m.invoke(builder, tracer);

            java.lang.reflect.Method exec = cls.getMethod("execute");
            Object evmResult = exec.invoke(builder);

            if (evmResult != null) {
                // try to extract remaining gas or gas-used indicators from the execution result
                long evmRemaining = -1L;
                String[] evmRemCandidates = new String[]{"getRemainingGas","getGasRemaining","getRemaining","getRemainingGasUnits","getGasLeft","getRemainingGasAmount","getGasLeftAmount"};
                for (String name : evmRemCandidates) {
                    try {
                        java.lang.reflect.Method mm = evmResult.getClass().getMethod(name);
                        Object val = mm.invoke(evmResult);
                        if (val == null) continue;
                        if (val instanceof Number) { evmRemaining = ((Number) val).longValue(); break; }
                        if (val instanceof Bytes) { evmRemaining = ((Bytes) val).toLong(); break; }
                        try { evmRemaining = Long.parseLong(val.toString()); break; } catch (Throwable ignore) {}
                    } catch (NoSuchMethodException ignored) {}
                    catch (Throwable ignore) {}
                }
                Class<?> rcls = evmResult.getClass();
                // Try common success indicators
                String[] successCandidates = new String[]{"isSuccessful","isSuccess","successful","succeeded","isOk"};
                for (String name : successCandidates) {
                    try {
                        java.lang.reflect.Method mm = rcls.getMethod(name);
                        Object val = mm.invoke(evmResult);
                        if (val instanceof Boolean) { success[0] = (Boolean) val; break; }
                    } catch (NoSuchMethodException ignored) {}
                }

                // Try revert/exception reasons
                String[] revertCandidates = new String[]{"getRevertReason","getExceptionalHaltReason","getHaltReason","getRevert","getException","getError"};
                for (String name : revertCandidates) {
                    try {
                        java.lang.reflect.Method mm = rcls.getMethod(name);
                        Object val = mm.invoke(evmResult);
                        if (val != null) {
                            String s = val.toString();
                            if (!s.isEmpty()) {
                                success[0] = false;
                                if (val instanceof Bytes) capturedRevert[0] = Optional.of((Bytes) val);
                                else capturedRevert[0] = Optional.of(Bytes.wrap(s.getBytes()));
                                break;
                            }
                        }
                    } catch (NoSuchMethodException ignored) {}
                }

                // Try output getters
                String[] outCandidates = new String[]{"getOutput","getReturnData","getOutputData","getResult"};
                for (String name : outCandidates) {
                    try {
                        java.lang.reflect.Method mm = rcls.getMethod(name);
                        Object val = mm.invoke(evmResult);
                        if (val instanceof Bytes) { capturedOutput[0] = (Bytes) val; break; }
                        if (val != null) { capturedOutput[0] = Bytes.wrap(val.toString().getBytes()); break; }
                    } catch (NoSuchMethodException ignored) {}
                }

                // As a last resort, inspect all zero-arg methods for enum/status strings indicating failure
                for (java.lang.reflect.Method mm : rcls.getMethods()) {
                    if (mm.getParameterCount() != 0) continue;
                    try {
                        Object val = mm.invoke(evmResult);
                        if (val == null) continue;
                        // If enum-like, check name()
                        if (val.getClass().isEnum()) {
                            String nm = ((Enum<?>) val).name();
                            String up = nm.toUpperCase();
                            if (up.contains("REVERT") || up.contains("HALT") || up.contains("FAIL") || up.contains("EXCEPTION")) {
                                success[0] = false;
                                break;
                            }
                        }
                        String s = val.toString();
                        if (s != null && !s.isEmpty()) {
                            String up = s.toUpperCase();
                            if (up.contains("REVERT") || up.contains("HALT") || up.contains("FAIL") || up.contains("EXCEPTION")) {
                                success[0] = false;
                                if (val instanceof Bytes) capturedRevert[0] = Optional.of((Bytes) val);
                                else capturedRevert[0] = Optional.of(Bytes.wrap(s.getBytes()));
                                break;
                            }
                        }
                    } catch (Throwable ignore) {}
                }

                // If we found remaining gas on evmResult, store it into capturedRemainingGas for later accounting
                try {
                    java.lang.reflect.Method rem = evmResult.getClass().getMethod("getRemainingGas");
                    Object v = rem.invoke(evmResult);
                    if (v instanceof Number) capturedRemainingGas[0] = ((Number) v).longValue();
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ex) {
            // If reflection fails, fall back to tracer-only approach (already captured trace outputs)
            ex.printStackTrace();
            // attempt a simple execute-as-before so state is advanced in any case
            EVMExecutor.evm(evm)
                    .worldUpdater(world)
                    .commitWorldState(true)
                    .precompileContractRegistry(precompiles)
                    .messageFrameType(MessageFrame.Type.MESSAGE_CALL)
                    .sender(tx.getSender())
                    .receiver(to)
                    .contract(to)
                    .gas(tx.getGasLimit())
                    .callData(tx.getData())
                    .code((world.getAccount(to) != null) ? world.getAccount(to).getCode() : Bytes.EMPTY)
                    .tracer(new OperationTracer() {
                        @Override
                        public void traceContextExit(MessageFrame frame) {
                            Bytes out = frame.getOutputData();
                            if (out != null && !out.isEmpty()) capturedOutput[0] = out;
                        }
                    })
                    .execute();
        }

            long gasCharged;
            if (capturedRemainingGas[0] >= 0) {
                gasCharged = Math.max(0L, tx.getGasLimit() - capturedRemainingGas[0]);
            } else {
                gasCharged = tx.getGasLimit();
            }

            finaliseGas(tx, gasCharged);

            return new ExecutionResult(success[0], to, gasCharged, capturedOutput[0], capturedRevert[0]);
    }

    private void deductMaxFee(DepTransaction tx) {
        MutableAccount sender = world.getAccount(tx.getSender());
        if (sender == null) throw new IllegalStateException("Sender account not found");
        // Pre-charge max fee by subtracting it from sender immediately
        long maxFee = tx.getGasLimit();
        Wei fee = tx.maxFee();
        sender.setBalance(Wei.of(sender.getBalance().toLong() - fee.toLong()));
    }

    private void finaliseGas(DepTransaction tx, long gasUsed) {
        MutableAccount sender = world.getAccount(tx.getSender());
        if (sender == null) return;
        Wei actual = tx.actualFee(gasUsed);
        // Actual fee already effectively deducted in deductMaxFee; refund difference
        Wei maxFee = tx.maxFee();
        Wei refund = Wei.of(maxFee.toLong() - actual.toLong());
        sender.setBalance(Wei.of(sender.getBalance().toLong() + refund.toLong()));
    }

    private Address generateContractAddress(Address sender, long nonce) {
        Bytes encoded = Bytes.concatenate(sender, Bytes.ofUnsignedLong(nonce));
        Bytes32 hash = Hash.keccak256(encoded);
        return Address.fromHexString(hash.slice(12, 20).toHexString());
    }

    public static final class ExecutionResult {
        private final boolean success;
        private final Address address;
        private final long gasUsed;
        private final Bytes output;
        private final Optional<Bytes> revertReason;

        public ExecutionResult(boolean success, Address address, long gasUsed, Bytes output, Optional<Bytes> revertReason) {
            this.success = success; this.address = address; this.gasUsed = gasUsed; this.output = output; this.revertReason = revertReason;
        }

        public boolean success() { return success; }
        public Address address() { return address; }
        public long gasUsed() { return gasUsed; }
        public Bytes output() { return output; }
        public Optional<Bytes> revertReason() { return revertReason; }
    }
}
