package com.g27.DepChain;

import java.security.*;
import java.util.*;
import java.nio.file.*;
import java.net.InetAddress;
import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.Crypto.BLSConfig;
import com.g27.DepChain.HotStuff.*;
import org.json.JSONArray;
import org.json.JSONObject;


import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSPublicKey;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import com.g27.DepChain.smartContact.genesis.GenesisGenerator;

public class App {

    /**
     * Generate RSA key pair.
     *
     * @return the generated RSA {@link KeyPair}
     * @throws Exception if key generation fails
     */
    static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    /**
     * Loads config JSONs, generate RSA and ECDSA keys for replicas and 3 clients, writes pub keys to JSON.
     * Clients receive ECDSA keypair and derived Ethereum address from the public key.
     *
     * @param args command line arguments passed to the program
     * @throws Exception if configuration loading or key generation fails
     */
    public static void main(String[] args) throws Exception {
        String configPath = "config/MainConfig.json";
        String configContent = new String(Files.readAllBytes(Paths.get(configPath)));
        JSONObject config = new JSONObject(configContent);
        JSONArray replicas = config.getJSONArray("replicas");

        int n = replicas.length();

        // Build member info and RSA keys
        Map<Integer, NodeConfig.MemberInfo> memberInfos = new LinkedHashMap<>();
        Map<Integer, PublicKey> memberKeys = new HashMap<>();
        Map<Integer, KeyPair> keyPairs = new HashMap<>();

        for (int i = 0; i < n; i++) {
            JSONObject replica = replicas.getJSONObject(i);
            int replicaId = replica.getInt("id");
            int port = replica.getInt("port");

            memberInfos.put(replicaId, new NodeConfig.MemberInfo(InetAddress.getByName("localhost"), port));

            KeyPair kp = generateKeyPair();
            keyPairs.put(replicaId, kp);
            memberKeys.put(replicaId, kp.getPublic());
        }

        // generate ECDSA keypairs for replicas (needed both for key publishing and node signing)
        java.util.List<ECKeyPair> replicaEcs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            replicaEcs.add(Keys.createEcKeyPair());
        }

        // write public keys for all members to config/public_keys.json (base64 X.509 encoded)
        try {
            // generate three client keypairs to include (RSA for network auth, ECDSA for EOAs)
            int numClients = 3;
            java.util.List<KeyPair> clientKps = new ArrayList<>();
            java.util.List<ECKeyPair> clientEcs = new ArrayList<>();
            for (int i = 0; i < numClients; i++) {
                clientKps.add(generateKeyPair());
                clientEcs.add(Keys.createEcKeyPair());
            }

            // assign consecutive ids for clients starting at n
            org.json.JSONObject keysObj = new org.json.JSONObject();
            for (Map.Entry<Integer, PublicKey> e : memberKeys.entrySet()) {
                String b64 = java.util.Base64.getEncoder().encodeToString(e.getValue().getEncoded());
                keysObj.put(String.valueOf(e.getKey()), b64);
            }
            for (int i = 0; i < clientKps.size(); i++) {
                int cid = n + i; // client id after replicas
                memberKeys.put(cid, clientKps.get(i).getPublic());
                String b64 = java.util.Base64.getEncoder().encodeToString(clientKps.get(i).getPublic().getEncoded());
                keysObj.put(String.valueOf(cid), b64);
            }

            // clients array contains public/private for each client (base64 PKCS8/X509)
            org.json.JSONArray clientsArr = new org.json.JSONArray();
            for (int i = 0; i < clientKps.size(); i++) {
                KeyPair kp = clientKps.get(i);
                ECKeyPair ecp = clientEcs.get(i);
                String clientPubB64 = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
                String clientPrivB64 = java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
                org.json.JSONObject cobj = new org.json.JSONObject();
                cobj.put("id", n + i);
                // RSA keys for network authentication (legacy)
                cobj.put("public", clientPubB64);
                cobj.put("private", clientPrivB64);
                // ECDSA keys for Ethereum-style EOAs (hex)
                String ecdsaPrivHex = String.format("%064x", ecp.getPrivateKey());
                String ecdsaPubHex = String.format("%0128x", ecp.getPublicKey());
                cobj.put("ecdsaPrivate", ecdsaPrivHex);
                cobj.put("ecdsaPublic", ecdsaPubHex);
                // derive Ethereum address (0x-prefixed)
                String addr = "0x" + Keys.getAddress(ecp);
                cobj.put("address", addr);
                clientsArr.put(cobj);
            }

            // Build top-level ecdsa mapping for replicas and clients (id -> { ecdsaPublic, address })
            org.json.JSONObject ecdsaMap = new org.json.JSONObject();
            // replicas: ids 0..n-1
            for (int i = 0; i < n; i++) {
                try {
                    ECKeyPair repEc = replicaEcs.get(i);
                    org.json.JSONObject ent = new org.json.JSONObject();
                    String pubHex = String.format("%0128x", repEc.getPublicKey());
                    String addr = "0x" + Keys.getAddress(repEc);
                    ent.put("ecdsaPublic", pubHex);
                    ent.put("address", addr);
                    ecdsaMap.put(String.valueOf(i), ent);
                } catch (Throwable ignore) {}
            }
            // clients: ids n..n+numClients-1
            for (int i = 0; i < clientEcs.size(); i++) {
                int cid = n + i;
                try {
                    ECKeyPair cec = clientEcs.get(i);
                    org.json.JSONObject ent = new org.json.JSONObject();
                    String pubHex = String.format("%0128x", cec.getPublicKey());
                    String addr = "0x" + Keys.getAddress(cec);
                    ent.put("ecdsaPublic", pubHex);
                    ent.put("address", addr);
                    ecdsaMap.put(String.valueOf(cid), ent);
                } catch (Throwable ignore) {}
            }

            org.json.JSONObject out = new org.json.JSONObject();
            out.put("keys", keysObj);
            out.put("clients", clientsArr);
            out.put("ecdsa", ecdsaMap);
            java.nio.file.Path outPath = Paths.get("config/public_keys.json");
            Files.createDirectories(outPath.getParent());
            Files.write(outPath, out.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Wrote public keys (+3 client keypairs) to config/public_keys.json");
            // Generate genesis JSON now that public keys are written
            try {
                String gen = GenesisGenerator.generateGenesisJson();
                if (gen != null) System.out.println("[App] Genesis generated: src/main/resources/genesis.json");
                else System.err.println("[App] Genesis generation returned null");
            } catch (Throwable gex) {
                System.err.println("[App] Genesis generation failed: " + gex.getMessage());
            }
        } catch (Exception ex) {
            System.err.println("Failed to write public keys file: " + ex.getMessage());
        }



        // Generate BLS keys for replicas (HotStuff)
        SecureRandom rng = new SecureRandom();
        List<BLSKeyPair> blsKeyPairs = new ArrayList<>();
        List<BLSPublicKey> allPubKeys = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            BLSKeyPair kp = BLSKeyPair.random(rng);
            blsKeyPairs.add(kp);
            allPubKeys.add(kp.getPublicKey());
        }

        // Create HotStuff nodes
        List<HotStuffNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONObject replica = replicas.getJSONObject(i);
            int replicaId = replica.getInt("id");
            int port = replica.getInt("port");
            int byzantineFlag = replica.getInt("byzantine");
            int connectionMode = replica.getInt("connection");
            BLSConfig blsConfig = new BLSConfig(blsKeyPairs.get(replicaId), allPubKeys);

            AuthConfig authConfig = new AuthConfig(keyPairs.get(replicaId).getPrivate(), keyPairs.get(replicaId).getPublic(), memberKeys);
            authConfig.setEcdsaKeyPair(replicaEcs.get(replicaId));
            authConfig.generateDHKeyPair();
            NodeConfig nodeConfig = new NodeConfig(replicaId, "localhost", port, byzantineFlag,
                    connectionMode, memberInfos, authConfig, blsConfig);

            nodes.add(new HotStuffNode(nodeConfig));
        }

        // Establish APL sessions between all nodes before starting them
        for (HotStuffNode node : nodes) {
            node.establish();
        }

        Thread.sleep(4000);

        System.out.println("All nodes established sessions. Starting nodes...");

        // Start all nodes
        for (HotStuffNode node : nodes) {
            node.start();
        }


    }
}
