package com.g27.DepChain.smartContact.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.Gson;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PublicKeyUtils {

    private PublicKeyUtils() {}

    public static Address deriveAddressFromPublicKeys(int clientId) {
        try {
            Path p = Paths.get("config", "public_keys.json");
            if (!Files.exists(p)) return null;
            String content = Files.readString(p);
            JsonObject root = new Gson().fromJson(content, JsonObject.class);
            if (root == null) return null;

            if (root.has("clients")) {
                JsonArray arr = root.getAsJsonArray("clients");
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject jo = e.getAsJsonObject();
                    if (!jo.has("id")) continue;
                    int id = jo.get("id").getAsInt();
                    if (id != clientId) continue;

                    if (jo.has("address") && !jo.get("address").isJsonNull()) {
                        String addr = jo.get("address").getAsString();
                        if (addr != null && !addr.isBlank()) {
                            return Address.fromHexString(addr);
                        }
                    }

                    if (jo.has("ecdsaPublic") && !jo.get("ecdsaPublic").isJsonNull()) {
                        String publicKey = jo.get("ecdsaPublic").getAsString();
                        if (publicKey != null && !publicKey.isBlank()) {
                            return deriveFromHexPub(publicKey);
                        }
                    }
                    return null;
                }
                return null;
            }

            if (root.has("ecdsa")) {
                JsonObject ecdsa = root.getAsJsonObject("ecdsa");
                String key = String.valueOf(clientId);
                if (!ecdsa.has(key)) return null;
                JsonObject jo = ecdsa.getAsJsonObject(key);
                if (jo == null) return null;

                if (jo.has("address") && !jo.get("address").isJsonNull()) {
                    String addr = jo.get("address").getAsString();
                    if (addr != null && !addr.isBlank()) {
                        return Address.fromHexString(addr);
                    }
                }

                if (jo.has("ecdsaPublic") && !jo.get("ecdsaPublic").isJsonNull()) {
                    String publicKey = jo.get("ecdsaPublic").getAsString();
                    if (publicKey != null && !publicKey.isBlank()) {
                        return deriveFromHexPub(publicKey);
                    }
                }
                return null;
            }

            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Address deriveFromHexPub(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) {
            h = h.substring(2);
        }

        if (h.length() == 40) {
            return Address.fromHexString("0x" + h);
        }

        byte[] pub = hexStringToBytes(h);
        if (pub == null) return null;

        if (pub.length == 65 && pub[0] == 0x04) {
            byte[] tmp = new byte[64];
            System.arraycopy(pub, 1, tmp, 0, 64);
            pub = tmp;
        }

        if (pub.length != 64) {
            return null;
        }

        Bytes b = Bytes.wrap(pub);
        Bytes32 h32 = Hash.keccak256(b);
        Bytes addrBytes = h32.slice(12, 20);
        return Address.fromHexString(addrBytes.toHexString());
    }

    private static byte[] hexStringToBytes(String s) {
        if (s == null) return null;
        String hex = s.trim();
        if (hex.length() % 2 != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
