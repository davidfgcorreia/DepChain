package com.g27.DepChain.Client;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.g27.DepChain.Crypto.AuthConfig;
import com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink;

import org.hyperledger.besu.datatypes.Address;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;

public class ClientApp {

  private static final ConcurrentHashMap<Integer, String> responses = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> latchMap = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String> requestMap = new ConcurrentHashMap<>();
  // locally computed IST address (derived from deployer client id 4, nonce 0)
  private static volatile Address IST_ADDRESS_LOCAL = null;
  private static volatile boolean DEBUG = false;

  public static void setDebug(boolean enabled) {
    DEBUG = enabled;
  }

  public static boolean isDebug() {
    return DEBUG;
  }

  public static void debug(String msg) {
    if (DEBUG)
      System.out.println(msg);
  }

  public static void debugErr(String msg) {
    if (DEBUG)
      System.err.println(msg);
  }

  public static Address getIstAddress() {
    return IST_ADDRESS_LOCAL;
  }

  /**
   * Entry point of the client.
   * Sets up communications with the replicas via the AuthenticatedPerfectLinks.
   *
   * @param args command line arguments passed to the program.
   */
  public static void main(String[] args) throws Exception {
    // enable debug via system property (e.g. `-Ddebug=true` in Maven/POM)
    String debugProp = System.getProperty("debug");
    if (debugProp != null
        && (debugProp.equalsIgnoreCase("true") || debugProp.equals("1") || debugProp.equalsIgnoreCase("yes"))) {
      setDebug(true);
    }

    // Register BouncyCastle provider so KECCAK-256 MessageDigest is available
    Security.addProvider(new BouncyCastleProvider());

    // Require explicit client id via system property `-Dclient.id=<id>`.
    String clientIdProp = System.getProperty("client.id");
    if (clientIdProp == null || clientIdProp.isBlank()) {
      System.err.println("ERROR: client.id system property is required. Run with -Dclient.id=<id>");
      System.exit(1);
      return;
    }

    final int clientId;
    try {
      clientId = Integer.parseInt(clientIdProp);
    } catch (NumberFormatException e) {
      System.err.println("ERROR: client.id must be an integer: " + clientIdProp);
      System.exit(1);
      return;
    }

    // Load configuration and client keys (explicit client id only)
    ClientConfig.Result cfg;
    try {
      cfg = ClientConfig.load(clientId);
    } catch (Exception e) {
      System.err.println("ERROR loading client configuration: " + e.getMessage());
      System.exit(1);
      return;
    }
    int CLIENT_PORT = cfg.clientPort;
    int myId = cfg.replicaInfos.size();

    if (cfg.chosenClientId < 0)
      cfg.chosenClientId = 0;

    Map<Integer, AuthenticatedPerfectLink> links = new java.util.HashMap<>();
    for (int i = 0; i < cfg.replicaInfos.size(); i++) {
      AuthConfig auth = new AuthConfig(cfg.clientKeyPair.getPrivate(), cfg.clientKeyPair.getPublic(), cfg.memberKeys);
      auth.generateDHKeyPair();
      // Single bidirectional link per replica: local 6000..6003 <-> remote 6004..6007
      AuthenticatedPerfectLink link = new AuthenticatedPerfectLink(CLIENT_PORT + i, myId, i, auth, 0);
      links.put(i, link);
    }

    for (int i = 0; i < cfg.replicaInfos.size(); i++) {
      int targetPort = CLIENT_PORT + 4 + i; // replica client-facing port
      AuthenticatedPerfectLink link = links.get(i);
      if (link != null)
        link.establishSession(InetAddress.getByName("localhost"), targetPort);
    }

    // wait until all sessions are established (up to 30s)
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      boolean allReady = links.values().stream().allMatch(AuthenticatedPerfectLink::isSessionEstablished);
      if (allReady)
        break;
      Thread.sleep(200);
    }

    // start listener and interactive client
    ClientListener.startListener(links, responses, latchMap, requestMap, CLIENT_PORT);

    // derive deployer (client id 4) and compute IST contract address (nonce=0)
    try {
      Address deployer = deriveDeployerAddressFromClientId(4);
      if (deployer != null) {
        IST_ADDRESS_LOCAL = computeContractAddress(deployer, 0L);
        debug("[Client] Derived IST contract address: "
            + (IST_ADDRESS_LOCAL != null ? IST_ADDRESS_LOCAL.toHexString() : "(null)"));
      } else {
        debugErr("[Client] Could not derive deployer address for client id 4; IST address unknown");
      }
    } catch (Throwable t) {
      System.err.println("[Client] Failed to derive IST address: " + t.getMessage());
    }

    ClientInteraction.startInteraction(links, cfg.replicaInfos, cfg.clientEcKeyPair, cfg.chosenClientId, CLIENT_PORT,
        responses, latchMap, requestMap);
  }

  /**
   * Public functions for ClientInteraction to render menu
   *
   * @param sender the address of the current client
   */
  public static void printMenu(Address sender) {
    System.out.println();
    System.out.println("═══════════════════════════════════════════");
    System.out.println("  DepChain Client  —  IST Coin (ERC-20)");
    System.out.println("═══════════════════════════════════════════");
    System.out.println("  Sender  : " + sender.toHexString());
    String istStr = (IST_ADDRESS_LOCAL != null) ? IST_ADDRESS_LOCAL.toHexString() : "(unknown)";
    System.out.println("  IST     : " + istStr);
    System.out.println("  Gas     : limit=" + com.g27.DepChain.Client.ClientTransactionBuilder.getDefaultGasLimit()
        + " price=" + com.g27.DepChain.Client.ClientTransactionBuilder.getDefaultGasPrice());
    System.out.println("───────────────────────────────────────────");
    System.out.println("  IST Coin (ERC-20):");
    System.out.println("    transfer <to> <amount>");
    System.out.println("    transfer-from <from> <to> <amount>");
    System.out.println("    approve <spender> <amount>");
    System.out.println("    increase-allowance <spender> <delta>");
    System.out.println("    decrease-allowance <spender> <delta>");
    System.out.println("    balance [address]");
    System.out.println("    allowance <owner> <spender>");
    System.out.println("    total-supply");
    System.out.println("  Native DepCoin:");
    System.out.println("    depcoin <to> <wei>");
    System.out.println("  Settings:");
    System.out.println("    addr <hex>   – change sender address");
    System.out.println("    nonce <n>    – set nonce manually");
    System.out.println("    gaslimit <n> – set default gas limit for transactions");
    System.out.println("    gasprice <n> – set default gas price for transactions");
    System.out.println("    help | q");
    System.out.println("═══════════════════════════════════════════");
    System.out.println();
  }

  /**
   * Checks the config and public_keys.json files to find the deployer address
   * from a client id.
   *
   * @param clientId The Id of a a client
   * @return the adress for the clientId provided, or null if not found.
   */
  private static Address deriveDeployerAddressFromClientId(int clientId) throws Exception {
    java.nio.file.Path p = Paths.get("config", "public_keys.json");
    if (!Files.exists(p))
      return null;
    String content = Files.readString(p);
    JSONObject root = new JSONObject(content);
    if (!root.has("clients"))
      return null;
    JSONArray clients = root.getJSONArray("clients");
    for (int i = 0; i < clients.length(); i++) {
      try {
        JSONObject jo = clients.getJSONObject(i);
        if (!jo.has("id"))
          continue;
        int id = jo.getInt("id");
        if (id != clientId)
          continue;
        if (jo.has("address")) {
          try {
            return Address.fromHexString(jo.getString("address"));
          } catch (Exception ignore) {
          }
        }
        if (!jo.has("ecdsaPublic"))
          return null;
        String pubHex = jo.getString("ecdsaPublic").trim();
        if (pubHex.startsWith("0x") || pubHex.startsWith("0X"))
          pubHex = pubHex.substring(2);
        if (pubHex.length() == 130 && pubHex.startsWith("04"))
          pubHex = pubHex.substring(2);
        if (pubHex.length() != 128)
          return null;
        byte[] pubBytes = Numeric.hexStringToByteArray("0x" + pubHex);
        byte[] h = Hash.sha3(pubBytes);
        byte[] addrBytes = Arrays.copyOfRange(h, h.length - 20, h.length);
        return Address.fromHexString(Numeric.toHexString(addrBytes));
      } catch (Throwable ignore) {
      }
    }
    return null;
  }

  /**
   * Finds and returns the ECDSA public key for a given replica.
   *
   * @param replicaId the Id of the replica.
   * @return the raw uncompressed ECDSA public key bytes for a replica id, or null
   *         if not found
   */
  public static byte[] getReplicaEcdsaPublicBytes(int replicaId) {
    try {
      java.nio.file.Path p = Paths.get("config", "public_keys.json");
      if (!Files.exists(p))
        return null;
      String content = Files.readString(p);
      JSONObject root = new JSONObject(content);
      if (!root.has("ecdsa"))
        return null;
      JSONObject ecdsa = root.getJSONObject("ecdsa");
      String key = Integer.toString(replicaId);
      if (!ecdsa.has(key))
        return null;
      JSONObject jo = ecdsa.getJSONObject(key);
      if (!jo.has("ecdsaPublic"))
        return null;
      String pubHex = jo.getString("ecdsaPublic").trim();
      if (pubHex.startsWith("0x") || pubHex.startsWith("0X"))
        pubHex = pubHex.substring(2);
      if (pubHex.length() == 130 && pubHex.startsWith("04"))
        pubHex = pubHex.substring(2);
      if (pubHex.length() != 128)
        return null;
      return Numeric.hexStringToByteArray("0x" + pubHex);
    } catch (Throwable t) {
      debugErr("Failed to read replica public key: " + t.getMessage());
      return null;
    }
  }

  /**
   * Verify a replica response payload's ECDSA signature.
   * The payload must be a JSON object containing a `signature` field (hex
   * r||s||v).
   * The signature is checked against the stored replica ECDSA public key in
   * config/public_keys.json (ecdsa.<id>.ecdsaPublic).
   *
   * @param payload   the payload that contains the signature (if correct).
   * @param replicaId the id of the replica.
   * @return true if the signature is correct, false othewise.
   *
   */
  public static boolean verifyReplicaResponseSignature(String payload, int replicaId) {
    try {
      if (payload == null)
        return false;
      org.json.JSONObject msg = new org.json.JSONObject(payload);
      String sig = msg.optString("signature", null);
      if (sig == null) {
        debugErr("[Client] Missing signature in replica response");
        return false;
      }

      org.json.JSONObject copy = new org.json.JSONObject(msg.toString());
      copy.remove("signature");
      String signedStr = copy.toString();

      byte[] sigBytes;
      try {
        String sh = (sig.startsWith("0x") || sig.startsWith("0X")) ? sig : ("0x" + sig);
        sigBytes = Numeric.hexStringToByteArray(sh);
      } catch (Throwable t) {
        debugErr("[Client] Invalid signature hex from replica " + replicaId + ": " + t.getMessage());
        return false;
      }
      if (sigBytes == null || sigBytes.length != 65) {
        debugErr("[Client] Invalid signature length from replica " + replicaId + " (len="
            + (sigBytes == null ? 0 : sigBytes.length) + ")");
        return false;
      }

      byte[] rBytes = Arrays.copyOfRange(sigBytes, 0, 32);
      byte[] sBytes = Arrays.copyOfRange(sigBytes, 32, 64);

      byte[] h = Hash.sha3(signedStr.getBytes(StandardCharsets.UTF_8));

      byte[] pubxy = getReplicaEcdsaPublicBytes(replicaId);
      if (pubxy == null || pubxy.length != 64) {
        debugErr("[Client] No stored public key for replica " + replicaId);
        return false;
      }

      try {
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(pubxy, 0, 32));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(pubxy, 32, 64));
        org.bouncycastle.jce.spec.ECParameterSpec bcSpec = org.bouncycastle.jce.ECNamedCurveTable
            .getParameterSpec("secp256k1");
        org.bouncycastle.math.ec.ECCurve curve = bcSpec.getCurve();
        org.bouncycastle.math.ec.ECPoint q = curve.createPoint(x, y);
        org.bouncycastle.crypto.params.ECDomainParameters domain = new org.bouncycastle.crypto.params.ECDomainParameters(
            curve, bcSpec.getG(), bcSpec.getN(), bcSpec.getH());
        org.bouncycastle.crypto.params.ECPublicKeyParameters pubParams = new org.bouncycastle.crypto.params.ECPublicKeyParameters(
            q, domain);
        org.bouncycastle.crypto.signers.ECDSASigner signer = new org.bouncycastle.crypto.signers.ECDSASigner();
        signer.init(false, pubParams);
        BigInteger rBig = new BigInteger(1, rBytes);
        BigInteger sBig = new BigInteger(1, sBytes);
        boolean verified = signer.verifySignature(h, rBig, sBig);
        if (!verified)
          debugErr("[Client] Replica " + replicaId + " signature failed verification");
        return verified;
      } catch (Throwable t) {
        debugErr("[Client] Error verifying replica signature: " + t.getMessage());
        return false;
      }
    } catch (Throwable t) {
      debugErr("[Client] Malformed replica response payload: " + t.getMessage());
      return false;
    }
  }

  /**
   * Resolve an input token to an Address.
   *
   * @param token Accepts:
   *              - numeric client id (e.g. "4") -> looks up
   *              `config/public_keys.json` clients
   *              - hex address (with or without 0x)
   * @return The address, or null if not found.
   */
  public static Address resolveAddress(String token) throws Exception {
    if (token == null)
      return null;
    token = token.trim();
    // numeric id
    if (token.matches("\\d+")) {
      int id = Integer.parseInt(token);
      return deriveDeployerAddressFromClientId(id);
    }
    // hex address
    String hex = token;
    if (!hex.startsWith("0x") && !hex.startsWith("0X"))
      hex = "0x" + hex;
    try {
      return Address.fromHexString(hex);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid address token: " + token);
    }
  }

  /**
   * Returns an address from the hash of the sender address + nonce.
   *
   * @param sender The address of the Sender
   * @param nonce  The nonce of the current communication
   * @return the address, or null if not found.
   */
  private static Address computeContractAddress(Address sender, long nonce) {
    try {
      byte[] senderBytes = Numeric.hexStringToByteArray(sender.toHexString());
      ByteBuffer bb = ByteBuffer.allocate(8);
      bb.putLong(nonce);
      byte[] nonceBytes = bb.array();
      byte[] concat = new byte[senderBytes.length + nonceBytes.length];
      System.arraycopy(senderBytes, 0, concat, 0, senderBytes.length);
      System.arraycopy(nonceBytes, 0, concat, senderBytes.length, nonceBytes.length);
      byte[] h = Hash.sha3(concat);
      byte[] addrBytes = Arrays.copyOfRange(h, h.length - 20, h.length);
      return Address.fromHexString(Numeric.toHexString(addrBytes));
    } catch (Throwable t) {
      return null;
    }
  }
}
