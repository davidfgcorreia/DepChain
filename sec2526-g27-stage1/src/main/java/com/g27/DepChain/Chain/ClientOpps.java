package com.g27.DepChain.Chain;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import com.g27.DepChain.HotStuff.HotStuffNode;
import com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink;
import com.g27.DepChain.LayeredChannels.Message;
import com.g27.DepChain.Crypto.AuthConfig;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * Manages the authenticated links between replica nodes and clients.
 * Handles opening connections, sending replies, removing processed requests,
 * and signing outgoing response payloads with the node's ECDSA key.
 */
public class ClientOpps {

  /**
   * Open a client connection for the default client index (index 0).
   *
   * @param node the local HotStuff replica node
   */
    public static void openClientConnection(HotStuffNode node) {
    openClientConnection(node, 0);
  }

  /**
   * Open client connection for a chosen client index. Allows multiple client
   * groups differentiated by 100-port blocks. The final port computed is:
   * 6000 + (max(0, chosenArrayIndex) * 100) + 4 + nodeId.
   *
   * @param node             the local HotStuff replica node
   * @param chosenArrayIndex the client group index (0-based); negative values are treated as 0
   */
  public static void openClientConnection(HotStuffNode node, int chosenArrayIndex) {
    try {
      node.dbg("openClientCOnnection index=" + chosenArrayIndex);
      AuthConfig cfg = node.getCrypto();
      int idx = Math.max(0, chosenArrayIndex);
      int finalPort = 6000 + (idx * 100) + 4 + node.getId();
      int clientId = node.getN() + idx;
      AuthenticatedPerfectLink link = new AuthenticatedPerfectLink(finalPort, node.getId(), clientId, cfg, 0);
      node.addClientLink(finalPort, link);
      node.setClientPort(finalPort);
      node.dbg("client link opened on port=" + finalPort);
    } catch (Exception e) {
      System.err.println("Failed to open client connection: " + e.getMessage());
    }
  }

  /**
   * Open client links for indices [0..count-1]. If count is <= 0, opens index 0.
   *
   * @param node  the local HotStuff replica node
   * @param count number of client groups to open links for
   */
  public static void openAllClientConnections(HotStuffNode node, int count) {
    int c = Math.max(1, count);
    for (int i = 0; i < c; i++) {
      openClientConnection(node, i);
    }
  }


  /**
   * Send a committed block reply to the appropriate client.
   * Handles both aggregated (txs + evmResults) and legacy single-envelope formats.
   * Signs the reply payload with the node's ECDSA key before sending.
   *
   * @param node            the local HotStuff replica node
   * @param Transactiondata the serialized block data or response envelope to send
   */
    public static void sendtoClient(HotStuffNode node, String Transactiondata) {
    if (Transactiondata == null) {
      System.err.println("[" + node.getId() + "] No block data to send to client");
      return;
    }

    JSONObject blockDataObj = null;
    try {
      blockDataObj = new JSONObject(Transactiondata);
    } catch (Exception ex) {
      return;
    }

    // New aggregated format: {"txs":[...],"evmResults":[...]}
    if (blockDataObj.has("txs") && blockDataObj.has("evmResults")) {
      JSONArray txs = blockDataObj.optJSONArray("txs");
      JSONArray evmResults = blockDataObj.optJSONArray("evmResults");
      if (txs != null && evmResults != null) {
        int len = Math.min(txs.length(), evmResults.length());
        for (int i = 0; i < len; i++) {
          try {
            JSONObject txEnv = txs.optJSONObject(i);
            JSONObject evm = evmResults.optJSONObject(i);
            if (txEnv == null || evm == null) continue;

            String addr = txEnv.optString("address", null);
            if (addr == null || addr.isBlank()) continue;
            int sendPort = deriveClientSendPort(node, addr);
            if (sendPort == 0) continue;

            String reqHash = txEnv.optString("requestHash", null);
            if (reqHash == null || reqHash.isBlank()) {
              reqHash = computeTxHashFromValue(txEnv.optString("value", null));
            }

            JSONObject reply = new JSONObject();
            reply.put("type", "RESPONSE");
            reply.put("status", "committed");
            reply.put("address", addr);
            reply.put("result", evm);
            if (reqHash != null) {
              reply.put("requestHash", reqHash);
              reply.put("transactionHash", reqHash);
            }

            String sigHex = signPayload(node, reply.toString());
            if (sigHex != null) reply.put("signature", sigHex);

            AuthenticatedPerfectLink sendLink = node.getClientLinkForPort(sendPort);
            if (sendLink != null) {
              sendLink.send(new Message(reply.toString().getBytes(StandardCharsets.UTF_8),
                  InetAddress.getByName("localhost"), sendPort - 4));
            }
          } catch (Throwable ignore) {
          }
        }
        return;
      }
    }

    // Legacy single-envelope format
    String bdAddr = blockDataObj.optString("address", null);
    int bdPort = deriveClientSendPort(node, bdAddr);
    if (bdAddr == null || bdAddr.isBlank() || bdPort == 0) {
      System.err.println("[" + node.getId() + "] Block contains invalid client address/port, skipping send");
      return;
    }
    JSONObject reply = new JSONObject();
    reply.put("type", "RESPONSE");
    reply.put("value", blockDataObj.has("value") ? blockDataObj.opt("value") : Transactiondata);
    reply.put("status", blockDataObj.optString("status", "committed"));
    if (blockDataObj.has("requestHash")) reply.put("requestHash", blockDataObj.optString("requestHash", null));
    if (blockDataObj.has("motive")) reply.put("motive", blockDataObj.opt("motive"));
    if (blockDataObj.has("error")) reply.put("error", blockDataObj.opt("error"));

    String sigHex = signPayload(node, reply.toString());
    if (sigHex != null) reply.put("signature", sigHex);

    try {
      AuthenticatedPerfectLink sendLink = node.getClientLinkForPort(bdPort);
      if (sendLink != null) {
        sendLink.send(new Message(reply.toString().getBytes(StandardCharsets.UTF_8),
            InetAddress.getByName("localhost"), bdPort - 4));
      } else {
        System.err.println("[" + node.getId() + "] No client link available to send response to addr=" + bdAddr + " port=" + bdPort);
      }
    } catch (Exception ex) {
      System.err.println("[" + node.getId() + "] Failed to send response to client addr=" + bdAddr + " port=" + bdPort + " - " + ex.getMessage());
      ex.printStackTrace();
    }
    return;

  }

  /**
   * Derive the port to use when sending a reply to a client, based on the client's Ethereum address.
   *
   * @param node    the local HotStuff replica node
   * @param address the client's Ethereum address (hex, with or without 0x prefix)
   * @return the computed send port, or 0 if the address cannot be resolved to a known client
   */
  private static int deriveClientSendPort(HotStuffNode node, String address) {
    if (address == null) return 0;
    String provided = address;
    String norm = provided.startsWith("0x") || provided.startsWith("0X") ? provided.substring(2) : provided;
    norm = norm.toLowerCase();
    Integer foundId = null;
    try {
      Integer cid = node.getClientIdForAddress(norm);
      if (cid != null) foundId = cid;
    } catch (Throwable ignore) {}
    if (foundId == null) return 0;
    int idx = foundId - node.getN();
    int base = 6000 + (Math.max(0, idx) * 100);
    return base + 4 + node.getId();
  }

  /**
   * Compute a keccak-256 hash of the given value string, returned as a 0x-prefixed hex string.
   *
   * @param value the string to hash
   * @return hex-encoded hash, or null if value is null or hashing fails
   */
  private static String computeTxHashFromValue(String value) {
    try {
      if (value == null) return null;
      byte[] h = Hash.sha3(value.getBytes(StandardCharsets.UTF_8));
      return Numeric.toHexString(h);
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * Sign a payload string with the node's ECDSA key pair.
   *
   * @param node    the local HotStuff replica node (provides the ECDSA key)
   * @param payload the payload string to sign
   * @return hex-encoded ECDSA signature (r||s||v, 65 bytes), or null if signing fails
   */
  private static String signPayload(HotStuffNode node, String payload) {
    try {
      ECKeyPair ecdsaKey = node.getCrypto().getEcdsaKeyPair();
      if (ecdsaKey == null) return null;
      byte[] hash = Hash.sha3(payload.getBytes(StandardCharsets.UTF_8));
      Sign.SignatureData sd = Sign.signMessage(hash, ecdsaKey, false);
      byte[] sigBytes = new byte[65];
      System.arraycopy(sd.getR(), 0, sigBytes, 0, 32);
      System.arraycopy(sd.getS(), 0, sigBytes, 32, 32);
      sigBytes[64] = sd.getV()[0];
      return Numeric.toHexString(sigBytes);
    } catch (Throwable sigEx) {
      node.dbg("Failed to sign client response: " + sigEx.getMessage());
      return null;
    }
  }

  /**
   * Remove client requests from the pending queue that match the committed block data.
   * Handles JSON arrays, single envelopes, and raw string formats.
   *
   * @param node      the local HotStuff replica node
   * @param blockdata the committed block data string used to identify requests to remove
   */
  public static void removeClientRequests(HotStuffNode node, String blockdata) {
    if (blockdata == null) return;

    try {
      String s = blockdata.trim();
      // If an array was mistakenly passed, consider only the first element
      if (s.startsWith("[")) {
        JSONArray arr = new JSONArray(blockdata);
        if (arr.length() > 0) {
          String txStr = arr.getJSONObject(0).toString();
          try { DataBlockManager.removeClientRequestMatchingInnerValue(node.getId(), txStr); } catch (Throwable ignore) {}
          return;
        }
        return;
      }

      // Single object or envelope
      if (s.startsWith("{")) {
        JSONObject parsed = new JSONObject(blockdata);
        // Prefer exact envelope removal; this is the new canonical mode.
        try {
          if (DataBlockManager.removeClientRequestExact(node.getId(), parsed.toString())) {
            return;
          }
        } catch (Throwable ignore) {
        }

        // Fallback: if this is an envelope with a `value` containing plain tx object,
        // try matching by inner tx in queue (legacy compatibility).
        if (parsed.has("value")) {
          String v = parsed.optString("value", null);
          if (v != null) {
            String iv = v.trim();
            if (iv.startsWith("[")) {
              JSONArray arr = new JSONArray(iv);
              if (arr.length() > 0) {
                String txStr = arr.getJSONObject(0).toString();
                try {
                  DataBlockManager.removeClientRequestMatchingInnerValue(node.getId(), txStr);
                } catch (Throwable ignore) {
                }
                return;
              }
            } else {
              try {
                JSONObject inner = new JSONObject(iv);
                try {
                  DataBlockManager.removeClientRequestMatchingInnerValue(node.getId(), inner.toString());
                } catch (Throwable ignore) {
                }
                return;
              } catch (Throwable ignore) {
                try {
                  DataBlockManager.removeClientRequestMatchingInnerValue(node.getId(), iv);
                } catch (Throwable ignore2) {
                }
                return;
              }
            }
          }
        }

        return;
      }

      // Fallback: treat as raw string and try exact removal
      try { DataBlockManager.removeClientRequestExact(node.getId(), blockdata); } catch (Throwable ignore) {}
    } catch (Throwable ignoreOuter) {
    }
  }

}
