package com.g27.DepChain.smartContact.genesis;

import com.g27.DepChain.smartContact.artifacts.ContractArtifacts;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.crypto.Hash;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Generates a standalone genesis JSON file containing initial account balances
 * and the transactions (including the contract init bytecode) that should
 * be executed at genesis by a chain runner.
 */
public final class GenesisGenerator {

    private GenesisGenerator() {}

    public static String generateGenesisJson() {
        Map<Integer, Address> clients = loadClientAddresses();
        // Seed every account present in config/public_keys.json with 100,000,000 Wei
        Wei clientBalance = Wei.of(100_000_000L);

        // Ensure client with id 4 exists and will be used as the deployer for the contract
        Address deployer = clients.get(4);
        if (deployer == null) {
            System.err.println("[GenesisGenerator] client id 4 not found in config/public_keys.json; cannot create deploy tx");
            return null;
        }

        try {
            // Ensure BouncyCastle provider is available for KECCAK-256 operations
            try {
                if (java.security.Security.getProvider("BC") == null) {
                    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
                }
            } catch (Throwable ignore) {}

            JSONObject genesis = new JSONObject();
            genesis.put("block_hash", JSONObject.NULL);
            genesis.put("previous_block_hash", JSONObject.NULL);

            // transactions: include a single deploy tx from client id 4 with init bytecode
            JSONArray txs = new JSONArray();
            JSONObject deployTx = new JSONObject();
            deployTx.put("from", deployer.toHexString());
            deployTx.put("to", JSONObject.NULL);
            String initCode = ContractArtifacts.IST_COIN_BYTECODE;
            deployTx.put("input", initCode == null ? "0x" : initCode);
            txs.put(deployTx);
            genesis.put("transactions", txs);

            JSONObject state = new JSONObject();
            // For every client in the config, add a state entry with 100,000,000 Wei
            for (Map.Entry<Integer, Address> e : clients.entrySet()) {
                Address a = e.getValue();
                if (a == null) continue;
                JSONObject cobj = new JSONObject();
                cobj.put("balance", clientBalance.toString());
                cobj.put("nonce", 0);
                state.put(a.toHexString(), cobj);
            }

            genesis.put("state", state);

            // canonicalize and compute block hash
            StringBuilder canon = new StringBuilder();
            canon.append(txs.toString());
            List<String> keys = new ArrayList<>();
            for (String k : state.keySet()) keys.add(k);
            Collections.sort(keys);
            for (String k : keys) {
                canon.append(k).append(state.get(k).toString());
            }
            Bytes digest = Hash.keccak256(Bytes.wrap(canon.toString().getBytes(StandardCharsets.UTF_8)));
            String hx = digest.toHexString();
            String blockHashHex = hx.startsWith("0x") ? hx : ("0x" + hx);
            genesis.put("block_hash", blockHashHex);

            // write to resources/genesis.json
            try {
                Path out = Paths.get("src", "main", "resources", "genesis.json");
                if (out.getParent() != null && !Files.exists(out.getParent())) Files.createDirectories(out.getParent());
                Files.writeString(out, genesis.toString(2));
                System.out.printf("[GenesisGenerator] wrote genesis JSON to %s%n", out.toString());
            } catch (Exception e) {
                System.out.println(genesis.toString(2));
            }
            return genesis.toString();
        } catch (Exception ex) {
            System.err.println("[GenesisGenerator] failed: " + ex.getMessage());
            return null;
        }
    }

    private static java.util.Map<Integer, Address> loadClientAddresses() {
        java.util.Map<Integer, Address> out = new java.util.HashMap<>();
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("config", "public_keys.json");
            if (!java.nio.file.Files.exists(p)) return out;
            String content = java.nio.file.Files.readString(p);
            com.google.gson.JsonObject root = new com.google.gson.Gson().fromJson(content, com.google.gson.JsonObject.class);
            if (root == null) return out;
            if (root.has("ecdsa") && root.getAsJsonObject("ecdsa") != null) {
                com.google.gson.JsonObject ecdsa = root.getAsJsonObject("ecdsa");
                for (String key : ecdsa.keySet()) {
                    try {
                        int id = Integer.parseInt(key);
                        com.google.gson.JsonObject info = ecdsa.getAsJsonObject(key);
                        if (info == null) continue;
                        if (info.has("address")) {
                            String addrStr = info.get("address").getAsString();
                            if (addrStr != null) {
                                Address addr = Address.fromHexString(addrStr);
                                out.put(id, addr);
                                continue;
                            }
                        }
                    } catch (NumberFormatException nfe) {
                    }
                }
                System.out.print(out);
                return out;
            }
            return out;
        } catch (Exception ex) {
            // ignore — return what we have
        }
        return out;
    }
}
