package com.g27.DepChain.HotStuff;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.bls.BLSSignature;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import com.g27.DepChain.LayeredChannels.Message;

public class HotStuffDispatch {

  /**
   * Decides what to do based on the type and content inside of the raw message.
   * Works for messages received from replicas or clients.
   */
  public static void dispatch(HotStuffNode node, String raw) throws Exception {
    node.dbg("dispatch rawLen=" + (raw == null ? 0 : raw.length()));
    System.out.println("[" + node.id + "] Received message: " + raw);
    String trimmed = raw == null ? "" : raw.trim();

    if (trimmed.startsWith("{")) {
      JSONObject msg = new JSONObject(trimmed);
      String type = msg.optString("type", "");

      switch (type) {
        case "CATCH_UP": {
          String high = msg.optString("highQC", null);
          QC highQC = new QC(high);
          int lastblockgood = msg.optInt("lastblockgood", -1);
          int replicaId = msg.optInt("id", -1);
          HotStuffView.catchupReply(node, highQC, lastblockgood, replicaId);
          break;
        }
        case "CATCH_UP_REPLY": {
          Object blocks = msg.opt("missingBlocks");
          JSONArray missingBlocks = (blocks instanceof JSONArray) ? (JSONArray) blocks : new JSONArray();
          HotStuffView.catchupReplyHandler(node, missingBlocks);
          break;
        }
        case "PROPOSE": {
          String blockM = msg.optString("block", null);
          InternalBlock block = new InternalBlock(blockM);
          String highQCStr = msg.optString("highQC", null);
          QC highQC = new QC(highQCStr);
          int view = msg.optInt("view", -1);
          int sender = msg.optInt("id", -1);
          node.dbg("dispatch -> PROPOSE from=" + sender + " view=" + view);
          HotStuffPhases.handlePropose(node, block, highQC, view, sender);
          break;
        }
        case "QCM1": {
          int view = msg.optInt("view", -1);
          int senderId = msg.optInt("id", -1);
          BLSSignature blsSignature = BLSSignature
              .fromBytesCompressed(Bytes.fromHexString(msg.optString("blsSignature", null)));
          node.dbg("dispatch -> QCM1 from=" + senderId + " view=" + view);
          HotStuffPhases.handleQCM1(node, view, senderId, blsSignature);
          break;
        }
        case "PRE_COMMIT": {
          int view = msg.optInt("view", -1);
          int sender = msg.optInt("id", -1);
          int blockNumber = msg.optInt("blockNumber", -1);
          String propose = msg.optString("proposeQC", null);
          QC proposeQC = new QC(propose);
          node.dbg("dispatch -> PRE_COMMIT from=" + sender + " view=" + view);
          HotStuffPhases.handlePreCommit(node, view, sender, proposeQC, blockNumber);
          break;
        }
        case "QCM2": {
          int view = msg.optInt("view", -1);
          int senderId = msg.optInt("id", -1);
          BLSSignature blsSignature = BLSSignature
              .fromBytesCompressed(Bytes.fromHexString(msg.optString("blsSignature", null)));
          node.dbg("dispatch -> QCM2 from=" + senderId + " view=" + view);
          HotStuffPhases.handleQCM2(node, view, senderId, blsSignature);
          break;
        }
        case "NEW_VIEW": {
          int view = msg.optInt("view", -1);
          int replicaId = msg.optInt("id", -1);
          Object high = msg.opt("highQC");
          String highQCstr = (high instanceof JSONObject) ? ((JSONObject) high).toString()
              : (high != null ? high.toString() : "null");
          node.dbg("dispatch -> NEW_VIEW from=" + replicaId + " view=" + view);
          HotStuffView.handleNewViewReply(node, view, replicaId, highQCstr);
          break;
        }
        case "COMMIT": {
          int view = msg.optInt("view", -1);
          int sender = msg.optInt("id", -1);
          int blockNumber = msg.optInt("blockNumber", -1);
          String preCommit = msg.optString("preCommitQC", null);
          QC preCommitQC = new QC(preCommit);
          node.dbg("dispatch -> COMMIT from=" + sender + " view=" + view);
          HotStuffPhases.handleCommit(node, view, sender, preCommitQC, blockNumber);
          break;
        }
        case "QCM3": {
          int view = msg.optInt("view", -1);
          int senderId = msg.optInt("id", -1);
          BLSSignature blsSignature = BLSSignature
              .fromBytesCompressed(Bytes.fromHexString(msg.optString("blsSignature", null)));
          node.dbg("dispatch -> QCM3 from=" + senderId + " view=" + view);
          HotStuffPhases.handleQCM3(node, view, senderId, blsSignature);
          break;
        }
        case "DECIDE": {
          int view = msg.optInt("view", -1);
          int sender = msg.optInt("id", -1);
          int blockNumber = msg.optInt("blockNumber", -1);
          String commit = msg.optString("commitQC", null);
          QC commitQC = new QC(commit);
          node.dbg("dispatch -> DECIDE from=" + sender + " view=" + view);
          HotStuffPhases.handleDecide(node, view, sender, commitQC, blockNumber);
          break;
        }
        case "REQUEST": {
          System.out.println("Recebeu operacao");
          handleRequest(node, msg);
          break;
        }
        case "READ": {
          handleRead(node, msg);
          break;
        }
        default:
          break;
      }
      return;
    }
  }

  /**
   * Helper function that handles messages received from the type:REQUEST.
   * Also makes sure the request is valid and enqueues it if it is.
   */
  private static void handleRequest(HotStuffNode node, JSONObject msg) {
    // clients send: { type, address, clientId, value, signature }
    String clientAddress = msg.optString("address", null);
    int clientId = msg.has("clientId") ? msg.optInt("clientId", -1) : -1;
    // Derive clientId from blockchain address if not provided
    if (clientId == -1 && clientAddress != null && !clientAddress.isBlank()) {
      String norm = (clientAddress.startsWith("0x") || clientAddress.startsWith("0X"))
          ? clientAddress.substring(2)
          : clientAddress;
      norm = norm.toLowerCase();
      Integer found = node.getClientIdForAddress(norm);
      clientId = (found != null) ? found : -1;
    }
    String value = msg.optString("value", null);
    String signature = msg.optString("signature", null);
    String hash = msg.optString("requestHash", null);

    com.g27.DepChain.Chain.RequestValidator.ValidationResult vr = com.g27.DepChain.Chain.RequestValidator
        .validateClientRequest(
            node.clientEcdsaAddresses,
            node.clientEcdsaPublicBytes,
            node.clientNonces,
            clientId,
            value,
            signature,
            hash,
            clientAddress);
    if (!vr.valid) {
      System.err.println("[" + node.id + "] Rejected client REQUEST: " + vr.error);
      try {
        if (clientAddress != null && !clientAddress.isBlank()) {
          JSONObject failReply = new JSONObject();
          // prefer `address` key (client convention) and keep legacy `clientAddress` too
          failReply.put("clientAddress", clientAddress);
          failReply.put("value", value);
          failReply.put("status", "failed validating");
          failReply.put("motive", vr.error == null ? "unknown validation error" : vr.error);
          failReply.put("requestHash", hash);
          node.sendToClientPublic(failReply.toString());
        }
      } catch (Throwable t) {
        System.err.println("[" + node.id + "] Failed to notify client about validation rejection: " + t.getMessage());
      }
      return;
    }

    JSONObject obj = new JSONObject();
    // enqueue using the client's envelope naming
    obj.put("address", clientAddress);
    obj.put("value", value);
    obj.put("signature", signature);
    obj.put("requestHash", hash);
    node.enqueueClientRequest(obj.toString(), hash);
    System.out.println("[" + node.id + "] Accepted client request id=" + clientId + " value=" + value);
    System.out.println("[" + node.getId() + "] Total pending client requests: " + node.getClientRequests().size());
    System.out.println("[" + node.getId() + "] Current requests: " + node.getClientRequests());
  }

  /**
   * Helper function that handles messages received from type:READ.
   * Also makes sure the request is valid before enqueuing it.
   */
  private static void handleRead(HotStuffNode node, JSONObject msg) {
    // Read-only EVM query — answered immediately, bypasses consensus.
    String clientAddress = msg.optString("address", null);
    int clientId = msg.optInt("clientId", -1);
    String queryStr = msg.optString("value", null);
    String signature = msg.optString("signature", null);
    String hash = msg.optString("requestHash", null);
    // Delegate simple READ verification to application-layer validator
    boolean verified = com.g27.DepChain.Chain.RequestValidator.validateReadRequest(
        node.clientEcdsaAddresses,
        node.clientEcdsaPublicBytes,
        node.clientNonces,
        clientId,
        signature,
        queryStr);
    if (!verified) {
      System.err.println("[" + node.id + "] Rejected READ: ECDSA signature verification failed or missing");
      return;
    }
    if (queryStr != null) {
      try {
        JSONObject query = new JSONObject(queryStr);
        JSONObject result = node.executeRead(query);

        // Do not update nonce for READ operations since they don't change state

        JSONObject reply = new JSONObject();
        reply.put("type", "READ_RESPONSE");
        reply.put("query", queryStr);
        reply.put("result", result);
        reply.put("requestHash", hash);

        // Sign READ_RESPONSE payload so clients can verify replica authenticity
        String sigHex = signPayload(node, reply.toString());
        if (sigHex != null) {
          reply.put("signature", sigHex);
        }

        if (clientAddress != null && !clientAddress.isBlank()) {
          String norm = (clientAddress.startsWith("0x") || clientAddress.startsWith("0X"))
              ? clientAddress.substring(2)
              : clientAddress;
          norm = norm.toLowerCase();
          Integer foundId = node.getClientIdForAddress(norm);
          if (foundId != null) {
            int idx = foundId - node.getN();
            int base = 6000 + (Math.max(0, idx) * 100);
            int sendPort = base + 4 + node.getId();
            com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink sendLink = node.getClientLinkForPort(sendPort);
            if (sendLink != null) {
              sendLink.send(new Message(
                  reply.toString().getBytes(StandardCharsets.UTF_8),
                  InetAddress.getByName("localhost"),
                  sendPort - 4));
              node.dbg("Sent READ_RESPONSE to clientId=" + clientId + " at port=" + (sendPort - 4) + " reply: "
                  + reply.toString());
            }
          }
        }
      } catch (Exception e) {
        System.err.println("[" + node.id + "] READ dispatch error: " + e.getMessage());
      }
    }
  }

  /**
   * Helper function that signs a payload with the node's ECDSA key.
   */
  private static String signPayload(HotStuffNode node, String payload) {
    try {
      ECKeyPair ecdsaKey = node.getCrypto().getEcdsaKeyPair();
      if (ecdsaKey == null)
        return null;
      byte[] hash = Hash.sha3(payload.getBytes(StandardCharsets.UTF_8));
      Sign.SignatureData sd = Sign.signMessage(hash, ecdsaKey, false);
      byte[] sigBytes = new byte[65];
      System.arraycopy(sd.getR(), 0, sigBytes, 0, 32);
      System.arraycopy(sd.getS(), 0, sigBytes, 32, 32);
      sigBytes[64] = sd.getV()[0];
      return Numeric.toHexString(sigBytes);
    } catch (Throwable sigEx) {
      node.dbg("Failed to sign READ_RESPONSE: " + sigEx.getMessage());
      return null;
    }
  }

}
