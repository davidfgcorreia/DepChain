package com.g27.DepChain.Client;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

public class ClientConfig {

  // A helper class that contains information about the Client, as well as The
  // replicas and the memberKeys.
  public static class Result {
    public final List<ReplicaInfo> replicaInfos = new ArrayList<>();
    public final Map<Integer, PublicKey> memberKeys = new HashMap<>();
    public KeyPair clientKeyPair = null;
    public ECKeyPair clientEcKeyPair = null;
    public int chosenClientId = -1;
    public int clientPort = 6000;
  }

  /**
   * Reads the config file and builds a class Result with information from those
   * files.
   *
   * @param clienId the client whose config will be loaded.
   * @return A class Result containing the config of the client, as well as
   *         information about memberKeys and Replicas
   */
  public static Result load(int clientId) throws Exception {
    Result r = new Result();

    String publicContent = new String(Files.readAllBytes(Paths.get("config/public_keys.json")));
    JSONObject publicobj = new JSONObject(publicContent);
    JSONObject keysObj = publicobj.optJSONObject("keys");

    // Parse replicas
    String configContent = new String(Files.readAllBytes(Paths.get("config/MainConfig.json")));
    JSONObject config = new JSONObject(configContent);
    JSONArray replicas = config.getJSONArray("replicas");

    // populate memberKeys
    if (keysObj != null) {
      try {
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        for (String idStr : keysObj.keySet()) {
          int id = Integer.parseInt(idStr);
          String b64 = keysObj.getString(idStr);
          byte[] bytes = java.util.Base64.getDecoder().decode(b64);
          java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(bytes);
          PublicKey pub = kf.generatePublic(spec);
          r.memberKeys.put(id, pub);
        }
      } catch (Exception ex) {
        System.err.println("Failed to parse config/public_keys.json: " + ex.getMessage());
      }
    }

    // load client keys
    try {
      org.json.JSONArray clientsArr = publicobj.optJSONArray("clients");
      if (clientsArr == null || clientsArr.length() == 0) {
        throw new IllegalStateException("config/public_keys.json must contain a non-empty 'clients' array");
      }

      // Find the client object that has the explicit id == clientId
      JSONObject chosen = null;
      int chosenArrayIndex = -1;
      for (int i = 0; i < clientsArr.length(); i++) {
        try {
          JSONObject cand = clientsArr.getJSONObject(i);
          if (cand.has("id") && cand.getInt("id") == clientId) {
            chosen = cand;
            chosenArrayIndex = i;
            break;
          }
        } catch (Throwable ignore) {
        }
      }
      if (chosen == null) {
        throw new IllegalStateException("Client id " + clientId + " not found in config/public_keys.json");
      }

      String pubB64 = chosen.optString("public", null);
      String privB64 = chosen.optString("private", null);
      if (pubB64 == null || privB64 == null) {
        throw new IllegalStateException(
            "Client entry for id " + clientId + " must contain 'public' and 'private' fields");
      }
      byte[] pubBytes = java.util.Base64.getDecoder().decode(pubB64);
      byte[] privBytes = java.util.Base64.getDecoder().decode(privB64);
      java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
      java.security.spec.X509EncodedKeySpec pubSpec = new java.security.spec.X509EncodedKeySpec(pubBytes);
      java.security.spec.PKCS8EncodedKeySpec privSpec = new java.security.spec.PKCS8EncodedKeySpec(privBytes);
      PublicKey clientPub = kf.generatePublic(pubSpec);
      PrivateKey clientPriv = kf.generatePrivate(privSpec);
      r.clientKeyPair = new KeyPair(clientPub, clientPriv);

      String ecdsaPrivHex = chosen.optString("ecdsaPrivate", null);
      if (ecdsaPrivHex != null && !ecdsaPrivHex.isBlank()) {
        try {
          String hex = ecdsaPrivHex.startsWith("0x") ? ecdsaPrivHex.substring(2) : ecdsaPrivHex;
          // left-pad private hex to 64 chars (32 bytes) if necessary
          if (hex.length() < 64)
            hex = "0".repeat(64 - hex.length()) + hex;
          java.math.BigInteger priv = new java.math.BigInteger(hex, 16);
          r.clientEcKeyPair = ECKeyPair.create(priv);
        } catch (Throwable ex) {
          throw new IllegalStateException("Invalid ecdsaPrivate for client " + clientId + ": " + ex.getMessage(), ex);
        }
      }

      r.chosenClientId = chosen.optInt("id", clientId);
      // compute clientPort from the client's position in the clients array (first
      // client -> 6000)
      r.clientPort = 6000 + (Math.max(0, chosenArrayIndex) * 100);
    } catch (Exception ex) {
      System.err.println("Failed to load client keypair: " + ex.getMessage());
    }

    // replica infos
    for (int i = 0; i < replicas.length(); i++) {
      JSONObject rep = replicas.getJSONObject(i);
      int rid = rep.getInt("id");
      r.replicaInfos.add(new ReplicaInfo(rid, InetAddress.getByName("localhost"), rep.getInt("port")));
    }

    return r;
  }
}
