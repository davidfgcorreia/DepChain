package com.g27.DepChain.smartContact.genesis;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.g27.DepChain.smartContact.util.PublicKeyUtils.deriveAddressFromPublicKeys;


/**
 * Simple genesis initializer used by test harnesses and demo scripts.
 *
 * Responsibilities:
 *  - Seed well-known accounts with initial native balances
 *  - Deploy deterministic contracts (ISTCoin) at genesis
 */
public final class GenesisLoader {

    private GenesisLoader() {}



    /**
     * Apply a previously generated genesis JSON file to the provided EVM
     * executor world state. This will create accounts with the provided
     * balances and nonces, and attempt to set contract code if present.
     */
    public static String applyGenesisFromFile(EvmExecutor evm) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("src", "main", "resources", "genesis.json");
            if (!java.nio.file.Files.exists(p)) {
                System.err.println("[GenesisLoader] genesis.json not found at " + p.toString());
                return null;
            }
            String content = java.nio.file.Files.readString(p);
            JSONObject genesis = new JSONObject(content);

            JSONObject state = genesis.optJSONObject("state");
            // canonical deployer: derive deterministically from top-level 'ecdsa' (client id 4)
            Address deployer = deriveAddressFromPublicKeys(4);
            try { if (deployer != null && evm.world().getAccount(deployer) == null) evm.world().createAccount(deployer, 0, Wei.of(100_000_000L)); } catch (Throwable ignore) {}
            if (state != null) {
                for (String addrHex : state.keySet()) {
                    try {
                        JSONObject entry = state.getJSONObject(addrHex);
                        String balStr = entry.optString("balance", "0");
                        Wei bal = Wei.ZERO;
                        try {
                            if (balStr != null && (balStr.startsWith("0x") || balStr.startsWith("0X"))) {
                                bal = Wei.of(new java.math.BigInteger(balStr.substring(2), 16));
                            } else {
                                try {
                                    long lv = Long.parseLong(balStr);
                                    bal = Wei.of(lv);
                                } catch (Exception ex) {
                                    try { bal = Wei.of(new java.math.BigInteger(balStr)); } catch (Exception ex2) { bal = Wei.ZERO; }
                                }
                            }
                        } catch (Exception ex) {
                            bal = Wei.ZERO;
                        }
                        long nonce = entry.optLong("nonce", 0L);
                        Address addr = Address.fromHexString(addrHex);
                        try { evm.world().createAccount(addr, nonce, bal); } catch (Throwable ignore) {}

                        if (entry.has("code")) {
                            String codeHex = entry.optString("code", "0x");
                            if (codeHex != null && !codeHex.equals("0x")) {
                                try {
                                    org.apache.tuweni.bytes.Bytes code = org.apache.tuweni.bytes.Bytes.fromHexString(codeHex);
                                    org.hyperledger.besu.evm.account.MutableAccount ma = evm.world().getAccount(addr);
                                    if (ma != null) ma.setCode(code);
                                } catch (Throwable ignore) {}
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("[GenesisLoader] failed to apply state entry: " + ex.getMessage());
                    }
                }
            }

            JSONArray txs = genesis.optJSONArray("transactions");

            // Execute transactions found in the genesis file (in-order)
            if (txs != null) {
                for (int i = 0; i < txs.length(); i++) {
                    try {
                        JSONObject t = txs.getJSONObject(i);
                        String fromHex = t.optString("from", null);
                        String toHex = t.has("to") && !t.isNull("to") ? t.optString("to", null) : null;
                        String input = t.optString("input", "0x");
                        String valueStr = t.optString("value", null);

                        if (fromHex == null) { System.err.println("[GenesisLoader] tx missing 'from' field, skipping"); continue; }
                        Address sender = Address.fromHexString(fromHex);

                        // Ensure sender account exists
                        try { if (evm.world().getAccount(sender) == null) evm.world().createAccount(sender, 0, Wei.ZERO); } catch (Throwable ignore) {}
                        org.hyperledger.besu.evm.account.MutableAccount senderAcct = evm.world().getAccount(sender);
                        long nonce = (senderAcct == null) ? 0L : senderAcct.getNonce();

                        org.apache.tuweni.bytes.Bytes data = org.apache.tuweni.bytes.Bytes.EMPTY;
                        try { if (input != null && !input.equals("0x")) data = org.apache.tuweni.bytes.Bytes.fromHexString(input); } catch (Exception ignore) {}

                        org.hyperledger.besu.datatypes.Wei value = org.hyperledger.besu.datatypes.Wei.ZERO;
                        if (valueStr != null) {
                            try {
                                if (valueStr.startsWith("0x") || valueStr.startsWith("0X")) {
                                    value = org.hyperledger.besu.datatypes.Wei.of(new java.math.BigInteger(valueStr.substring(2), 16));
                                } else {
                                    value = org.hyperledger.besu.datatypes.Wei.of(new java.math.BigInteger(valueStr));
                                }
                            } catch (Exception ignore) { value = org.hyperledger.besu.datatypes.Wei.ZERO; }
                        }

                        // sensible defaults
                        long gasLimit = 3_000_000L;
                        org.hyperledger.besu.datatypes.Wei gasPrice = org.hyperledger.besu.datatypes.Wei.of(1L);

                        com.g27.DepChain.smartContact.runtime.DepTransaction.Builder db = com.g27.DepChain.smartContact.runtime.DepTransaction
                                .builder()
                                .sender(sender)
                                .data(data)
                                .value(value)
                                .gasLimit(gasLimit)
                                .gasPrice(gasPrice)
                                .nonce(nonce);

                        if (toHex == null) {
                            // contract creation
                            com.g27.DepChain.smartContact.runtime.DepTransaction tx = db.build();
                            com.g27.DepChain.smartContact.runtime.EvmExecutor.ExecutionResult res = evm.deployContract(tx);
                        } else {
                                System.err.println("[GenesisLoader] failed to execute call tx: ");
                        }

                        // increment sender nonce in world state to reflect applied tx
                        try {
                            org.hyperledger.besu.evm.account.MutableAccount sa = evm.world().getAccount(sender);
                            if (sa != null) sa.setNonce(nonce + 1);
                        } catch (Throwable ignore) {}

                    } catch (Exception ex) {
                        System.err.println("[GenesisLoader] failed to execute genesis tx: " + ex.getMessage());
                        ex.printStackTrace(System.err);
                    }
                }
            }
            return genesis.toString();
        } catch (Exception ex) {
            System.err.println("[GenesisLoader] failed to apply genesis file: " + ex.getMessage());
            return null;
        }
    }
}
