package com.g27.DepChain.Chain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.g27.DepChain.HotStuff.HotStuffNode;
import com.g27.DepChain.smartContact.model.Transaction;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CommitExecutor {

  /**
   * This method is called by replicas upon receiving a DECIDE message from the
   * leader, indicating that a block has been committed. It executes the block's
   * transactions, generates replies for clients, and appends the committed block
   * to the node's state.
   *
   * @param node      the local HotStuff replica node
   * @param blockData the raw block data string to commit
   * @param leader    the Ethereum address of the current leader (for EVM context)
   */
  public static void executeCommit(HotStuffNode node, String blockData, org.hyperledger.besu.datatypes.Address leader) {
    node.dbg("executeCommit (chain) blockData=" + blockData);

    String replyData = blockData;
    List<Transaction> txs = new ArrayList<>();

    if (blockData != null && !"Genesis".equals(blockData)) {
      try {
        EvmExecutor evm = node.getEvmExecutor();

        node.dbg("findEnvelopeForTx: pending clientRequests count=" + node.getClientRequests().size());

        java.util.function.Function<JSONObject, JSONObject> findEnvelopeForTx = (tx) -> {
          try {
            java.util.List<String> reqs = node.getClientRequests();
            node.dbg("findEnvelopeForTx: searching " + reqs.size() + " requests for tx=" + tx.toString());
            for (String raw : reqs) {
              try {
                JSONObject req = new JSONObject(raw);
                String v = req.optString("value", null);
                if (v != null) {
                  try {
                    JSONObject inner = new JSONObject(v);
                    if (inner.toString().equals(tx.toString())) {
                      node.dbg("findEnvelopeForTx: found match");
                      return req;
                    }
                  } catch (Throwable ignore) {
                    if (v.equals(tx.toString()))
                      return req;
                  }
                }
              } catch (Throwable ignore) {
              }
            }
            node.dbg("findEnvelopeForTx: no match found");
          } catch (Throwable ignore) {
          }
          return null;
        };

        // Normalize block data into an array of tx objects
        JSONArray txArray = new JSONArray();
        boolean originalSingle = false;
        try {
          String s = blockData.trim();
          if (s.startsWith("[")) {
            txArray = new JSONArray(blockData);
          } else if (s.startsWith("{")) {
            JSONObject parsed = new JSONObject(blockData);
            if (parsed.has("transactions")) {
              txArray = parsed.getJSONArray("transactions");
            } else {
              txArray = new JSONArray();
              txArray.put(parsed);
              originalSingle = true;
            }
          } else {
            // fallback: try parse as single JSON object
            try {
              JSONObject parsed = new JSONObject(blockData);
              txArray.put(parsed);
              originalSingle = true;
            } catch (Throwable ignore) {
              txArray = new JSONArray();
            }
          }
        } catch (Throwable e) {
          node.dbg("Failed to parse blockData: " + e.getMessage());
        }

        JSONArray evmResults = new JSONArray();

        // Process each transaction in the block data.
        for (int i = 0; i < txArray.length(); i++) {
          try {
            JSONObject entry = txArray.getJSONObject(i);
            JSONObject executionTx = null;
            JSONObject envelope = null;

            if (entry.has("txType") || entry.has("sender")) {
              executionTx = entry;
            } else if (entry.has("address") && entry.has("value")) {
              envelope = entry;
              String value = envelope.optString("value", null);
              if (value != null) {
                try {
                  executionTx = new JSONObject(value);
                } catch (Throwable ignore) {
                }
              }
            }

            if (executionTx == null)
              continue;

            // Execute the transaction on the EVM and collect results
            JSONObject evmResult = Execution.executeEvmTransaction(evm, executionTx, leader);
            node.dbg("EVM execution: success=" + evmResult.optBoolean("success") + " gasUsed="
                + evmResult.optLong("gasUsed"));
            evmResults.put(evmResult);

            long gasUsed = evmResult.optLong("gasUsed", 0L);
            txs.add(new Transaction(executionTx.optString("sender", ""), executionTx.optString("to", ""),
                executionTx.optLong("value", 0L),
                executionTx.optLong("gasLimit", 0L), gasUsed));

            // Advance committed nonce so replays are rejected
            try {
              String sender = executionTx.optString("sender", null);
              long nonce = executionTx.optLong("nonce", -1L);
              if (sender != null && nonce >= 0) {
                String norm = sender.startsWith("0x") ? sender.substring(2).toLowerCase() : sender.toLowerCase();
                node.getClientNonces().merge(norm, nonce + 1, Math::max);
              }
            } catch (Throwable ignore) {
            }

            // Build and send per-request reply (address-only envelopes)
            try {
              JSONObject replyPayload = new JSONObject();
              replyPayload.put("tx", executionTx);
              replyPayload.put("evmResult", evmResult);

              JSONObject requestEnvelope = envelope != null ? envelope : findEnvelopeForTx.apply(executionTx);
              JSONObject outEnv = new JSONObject();
              if (requestEnvelope != null) {
                // read the client account address from the envelope (single field)
                outEnv.put("clientAddress", requestEnvelope.optString("address", null));
                // propagate requestHash so clients can correlate replies
                if (requestEnvelope.has("requestHash"))
                  outEnv.put("requestHash", requestEnvelope.optString("requestHash", null));
              }
              outEnv.put("type", "RESPONSE");
              outEnv.put("value", replyPayload.toString());
              outEnv.put("status", "committed");

              // sign the full envelope so the client can verify against it
              String sigHex = signPayload(node, outEnv.toString());
              if (sigHex != null)
                outEnv.put("signature", sigHex);

              // send if we have a clientAddress (server derives port)
              if (outEnv.has("clientAddress") && outEnv.optString("clientAddress", null) != null) {
                node.sendToClientPublic(outEnv.toString());
                // remove the original client request (single-request removal)
                if (envelope != null) {
                  try {
                    node.removeClientRequests(envelope.toString());
                  } catch (Throwable ignore) {
                  }
                }
              }
            } catch (Throwable ignore) {
            }
          } catch (Throwable ignore) {
          }
        }

        // Build consolidated replyData
        if (originalSingle && txArray.length() == 1) {
          JSONObject single = txArray.getJSONObject(0);
          if (evmResults.length() > 0)
            single.put("evmResult", evmResults.getJSONObject(0));
          replyData = single.toString();
        } else {
          JSONObject enriched = new JSONObject();
          enriched.put("txs", txArray);
          enriched.put("evmResults", evmResults);
          replyData = enriched.toString();
        }

      } catch (Exception e) {
        node.dbg("EVM execution error for blockData: " + e.getMessage());
      }
    }

    // Append committed block and persist using node helper
    String blockHash = "";
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] h = digest
          .digest((replyData == null ? "" : replyData).getBytes(StandardCharsets.UTF_8));
      blockHash = java.util.Base64.getEncoder().encodeToString(h);
    } catch (Throwable ignore) {
    }

    node.appendCommittedBlock(blockData, txs, blockHash);

    // remove matching client request entries at commit time
    try {
      node.removeClientRequests(blockData);
    } catch (Throwable ignore) {
    }
    node.addLog(blockData, true);
    // send aggregated reply to public channel (legacy behavior)
    node.sendToClientPublic(replyData);

  }

  /**
   * Helper method to extract client address from config JSON for signing replies.
   *
   * @param node    the local HotStuff replica node (provides the ECDSA key)
   * @param payload the payload string to sign
   * @return the hex-encoded ECDSA signature (r||s||v, 65 bytes), or {@code null} if signing fails
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
      node.dbg("Failed to sign replica reply: " + sigEx.getMessage());
      return null;
    }
  }

}
