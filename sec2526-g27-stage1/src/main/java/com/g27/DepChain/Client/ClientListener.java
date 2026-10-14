package com.g27.DepChain.Client;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;

import com.g27.DepChain.LayeredChannels.AuthenticatedPerfectLink;
import com.g27.DepChain.LayeredChannels.Message;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientListener {

  /**
   * Extracts and converts the result field of a normalized replica response
   * into a human-readable format.
   *
   * @param normalized the normalized JSON response.
   * @return the decoded numeric result as a string, or the raw JSON string if
   *         parsing fails
   */

  private static String toUserFriendlyResult(JSONObject normalized) {
    try {
      JSONObject resObj = normalized.optJSONObject("result");
      if (resObj != null) {
        String outputHex = resObj.optString("output", null);
        if (outputHex != null) {
          String hex = outputHex.startsWith("0x") || outputHex.startsWith("0X") ? outputHex.substring(2) : outputHex;
          if (!hex.isBlank()) {
            java.math.BigInteger v = new java.math.BigInteger(hex, 16);
            return v.toString();
          }
        }
      }

      // Legacy write-response shape: value contains JSON with evmResult.output
      String val = normalized.optString("value", null);
      if (val != null) {
        try {
          JSONObject vj = new JSONObject(val);
          JSONObject evm = vj.optJSONObject("evmResult");
          if (evm != null) {
            String outputHex = evm.optString("output", null);
            if (outputHex != null) {
              String hex = outputHex.startsWith("0x") || outputHex.startsWith("0X") ? outputHex.substring(2)
                  : outputHex;
              if (!hex.isBlank()) {
                java.math.BigInteger v = new java.math.BigInteger(hex, 16);
                return v.toString();
              }
            }
          }
        } catch (Throwable ignore) {
        }
      }
    } catch (Throwable ignore) {
    }
    return normalized.toString();
  }

  /**
   * Extracts a user-friendly representation of the original request
   * from a normalized replica response.
   *
   * @param normalized the normalized JSON response
   * @return a string representation of the original request, or a fallback if
   *         unavailable
   */
  private static String toUserFriendlyRequest(JSONObject normalized) {
    try {
      // Read responses carry the original query
      String query = normalized.optString("query", null);
      if (query != null && !query.isBlank())
        return query;

      // Write responses often carry value JSON with tx field
      String val = normalized.optString("value", null);
      if (val != null && !val.isBlank()) {
        try {
          JSONObject vj = new JSONObject(val);
          JSONObject tx = vj.optJSONObject("tx");
          if (tx != null)
            return tx.toString();
        } catch (Throwable ignore) {
        }
        return val;
      }
    } catch (Throwable ignore) {
    }
    return "(unknown-request)";
  }

  /**
   * Starts an asynchronous listener thread that continuously receives and
   * processes responses from replicas.
   *
   * @param recvLinks  map of replica identifiers to AuthenticatedPerfectLink
   *                   instances used to receive messages from replicas
   * @param responses  shared map storing raw responses per replica (used for
   *                   backward compatibility)
   * @param latchMap   map of request hashes to CountDownLatch objects used to
   *                   signal quorum progress
   * @param requestMap map associating request hashes with the original user
   *                   command for display purposes
   * @param clientPort base port used to identify the local communication
   *                   endpoints for each replica
   */
  public static void startListener(Map<Integer, AuthenticatedPerfectLink> recvLinks,
      ConcurrentHashMap<Integer, String> responses,
      ConcurrentHashMap<String, CountDownLatch> latchMap,
      ConcurrentHashMap<String, String> requestMap,
      int clientPort) {

    final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Integer>> voteCounts = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> totalCounts = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, Boolean> printed = new java.util.concurrent.ConcurrentHashMap<>();

    Thread listener = new Thread(() -> {
      while (true) {
        for (java.util.Map.Entry<Integer, AuthenticatedPerfectLink> e : recvLinks.entrySet()) {
          int rid = e.getKey();
          AuthenticatedPerfectLink link = e.getValue();
          try {
            if (!link.isSessionEstablished())
              continue;
            List<Message> messages = link.receive();
            if (messages == null || messages.isEmpty())
              continue;
            for (Message m : messages) {
              String payload = new String(m.getData(), StandardCharsets.UTF_8);
              ClientApp.debug(
                  "[Client] received on local port " + (clientPort + rid) + " from " + m.getPort() + " -> " + payload);
              boolean accepted = ClientApp.verifyReplicaResponseSignature(payload, rid);
              if (accepted) {
                // Store for backward compatibility
                responses.put(clientPort + rid, payload);
                // Print response asynchronously so the client input loop is non-blocking
                try {
                  JSONObject json = new JSONObject(payload);
                  ClientApp.debug("[Client] Async response from replica " + rid + " -> " + payload);
                  // If response contains the requestHash, try to count down that latch
                  String rh = json.optString("requestHash", null);
                  if (rh == null) {
                    // try inside value payload (older format)
                    String val = json.optString("value", null);
                    if (val != null) {
                      try {
                        JSONObject inner = new JSONObject(val);
                        rh = inner.optString("requestHash", null);
                      } catch (Throwable ignore) {
                        rh = null;
                      }
                    }
                  }
                  if (rh != null) {
                    CountDownLatch l = latchMap.get(rh);
                    if (l != null)
                      l.countDown();

                    try {
                      // Normalize response for counting: remove signature and requestHash
                      JSONObject normCopy = new JSONObject(json.toString());
                      normCopy.remove("signature");
                      normCopy.remove("requestHash");
                      String normStr = normCopy.toString();
                      java.util.concurrent.ConcurrentHashMap<String, Integer> counter = voteCounts.computeIfAbsent(rh,
                          k -> new java.util.concurrent.ConcurrentHashMap<>());
                      int c = counter.merge(normStr, 1, Integer::sum);
                      int n = Math.max(1, recvLinks.size());
                      int f = Math.max(0, (n - 1) / 3);
                      int matchThreshold = f + 1;
                      int inconsistentThreshold = (2 * f) + 1;
                      int total = totalCounts.computeIfAbsent(rh, k -> new java.util.concurrent.atomic.AtomicInteger(0))
                          .incrementAndGet();

                      // Decide as soon as we have f+1 equal responses (works for both reads and
                      // writes).
                      if (c >= matchThreshold && printed.putIfAbsent(rh, true) == null) {
                        String userRequest = requestMap != null ? requestMap.get(rh) : null;
                        if (userRequest == null || userRequest.isBlank()) {
                          userRequest = toUserFriendlyRequest(normCopy);
                        }
                        String userResult = toUserFriendlyResult(normCopy);
                        System.out.println("[Client] Final result: request=" + userRequest + " output=" + userResult);
                        if (requestMap != null)
                          requestMap.remove(rh);
                      }

                      // If we reached 2f+1 total replies without deciding, mark inconsistent.
                      if (total >= inconsistentThreshold && printed.putIfAbsent(rh, true) == null) {
                        String userRequest = requestMap != null ? requestMap.get(rh) : null;
                        if (userRequest == null || userRequest.isBlank()) {
                          userRequest = toUserFriendlyRequest(normCopy);
                        }
                        System.out.println("[Client] Inconsistent replies for request=" + userRequest
                            + " after " + inconsistentThreshold + "+ responses.");
                        if (requestMap != null)
                          requestMap.remove(rh);
                      }
                    } catch (Throwable ignore) {
                    }
                  }
                  // Per-replica output intentionally suppressed; we print only final quorum
                  // result.
                } catch (Exception exc) {
                  ClientApp.debugErr(
                      "[Client] Failed to process response payload from replica " + rid + ": " + exc.getMessage());
                }
              } else {
                ClientApp.debugErr("[Client] Ignoring unverified response from replica " + rid);
              }
            }
          } catch (Exception ex) {
            if (com.g27.DepChain.Client.ClientApp.isDebug())
              ex.printStackTrace();
          }
        }
        try {
          Thread.sleep(50);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }, "client-per-link-listener");
    listener.setDaemon(true);
    listener.start();
  }
}
