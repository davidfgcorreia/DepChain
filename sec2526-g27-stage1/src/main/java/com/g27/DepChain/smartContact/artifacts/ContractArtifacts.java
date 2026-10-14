package com.g27.DepChain.smartContact.artifacts;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads compiled contract artifacts from resources/artifacts/contracts.json
 * at runtime so Java code can access ABI and init bytecode without
 * requiring hardcoded bytecode constants.
 */
public final class ContractArtifacts {

    private ContractArtifacts() {}

    private static final JsonObject ARTIFACTS;

    public static String IST_COIN_BYTECODE = "";

    static {
        JsonObject artifacts = null;
        try (InputStream in = ContractArtifacts.class.getClassLoader().getResourceAsStream("artifacts/contracts.json")) {
            if (in != null) {
                artifacts = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            }
        } catch (Exception ignored) {}

        ARTIFACTS = (artifacts != null) ? artifacts : new JsonObject();

        System.out.println("[ContractArtifacts] available artifact keys: " + ARTIFACTS.keySet());

        // Prefer the per-contract JSON produced by the compile script when present,
        // since it reflects the latest compiled source. Fall back to the merged
        // contracts.json entry otherwise.
        String istFromSingle = null;
        try (InputStream in2 = ContractArtifacts.class.getClassLoader().getResourceAsStream("artifacts/ISTCoin.json")) {
            if (in2 != null) {
                JsonObject root = new Gson().fromJson(new InputStreamReader(in2, StandardCharsets.UTF_8), JsonObject.class);
                if (root != null && root.has("contracts")) {
                    JsonObject contracts = root.getAsJsonObject("contracts");
                    for (Map.Entry<String, JsonElement> e : contracts.entrySet()) {
                        String k = e.getKey();
                        if (k.endsWith(":ISTCoin")) {
                            JsonObject decl = contracts.getAsJsonObject(k);
                            if (decl != null && decl.has("bin")) {
                                String raw = decl.get("bin").getAsString().trim();
                                if (!raw.isEmpty()) istFromSingle = raw.startsWith("0x") ? raw : "0x" + raw;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (istFromSingle != null && !istFromSingle.isEmpty()) {
            IST_COIN_BYTECODE = istFromSingle;
            System.out.println("[ContractArtifacts] chosen key ist=artifacts/ISTCoin.json");
        } else {
            String istKey = findKeyFor("ISTCoin");
            System.out.println("[ContractArtifacts] chosen key ist=" + istKey);
            IST_COIN_BYTECODE = extractBytecode(istKey);
        }

        System.out.println("[ContractArtifacts] IST_COIN_BYTECODE length=" + (IST_COIN_BYTECODE==null?0:IST_COIN_BYTECODE.length()));
    }

    private static String findKeyFor(String name) {
        // Prefer exact match with non-empty bytecode
        for (Map.Entry<String, JsonElement> e : ARTIFACTS.entrySet()) {
            String k = e.getKey();
            if (k.equals(name) && !extractBytecode(k).isEmpty()) return k;
        }
        // Prefer keys that end with _Name and have non-empty bytecode
        for (Map.Entry<String, JsonElement> e : ARTIFACTS.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_" + name) && !extractBytecode(k).isEmpty()) return k;
        }
        // Prefer any key that contains name and has non-empty bytecode
        for (Map.Entry<String, JsonElement> e : ARTIFACTS.entrySet()) {
            String k = e.getKey();
            if (k.contains(name) && !extractBytecode(k).isEmpty()) return k;
        }
        // Fallbacks: return exact or endsWith or contains even if bytecode empty
        for (Map.Entry<String, JsonElement> e : ARTIFACTS.entrySet()) {
            String k = e.getKey();
            if (k.equals(name) || k.endsWith("_" + name) || k.contains(name)) return k;
        }
        return null;
    }

    private static String extractBytecode(String key) {
        if (key == null) return "";
        try {
            JsonObject obj = ARTIFACTS.getAsJsonObject(key);
            if (obj == null) return "";
            JsonElement be = obj.get("bytecode");
            if (be == null || be.isJsonNull()) return "";
            String raw = be.getAsString().trim();
            if (raw.isEmpty()) return "";
            return raw.startsWith("0x") ? raw : "0x" + raw;
        } catch (Exception ex) {
            return "";
        }
    }

    public static String getBytecode(String contractKey) {
        return extractBytecode(contractKey);
    }

    public static JsonElement getAbi(String contractKey) {
        if (contractKey == null) return null;
        return ARTIFACTS.get(contractKey) != null ? ARTIFACTS.getAsJsonObject(contractKey).get("abi") : null;
    }

}
