package com.g27.DepChain.Chain;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Chain-side helper to register aggregated data blocks for consensus and
 * remove corresponding client requests from the pending queue.
 */
public class DataBlockManager {

  /**
   * Comparator for the priority queue: same sender ordered by nonce ascending,
   * different senders ordered by gas price descending, with sequence number as stable tiebreak.
   *
   * @param a the first request
   * @param b the second request
   * @return negative if a should come before b, positive if b before a
   */
  private static int compareClientReq(ClientReq a, ClientReq b) {
    // Same sender must preserve nonce progression.
    if (a.sender != null && b.sender != null && a.sender.equals(b.sender)) {
      int byNonce = Long.compare(a.nonce, b.nonce);
      if (byNonce != 0) return byNonce;
    }
    // Different senders are ordered by gas price (higher first).
    int byGasPrice = Long.compare(b.gasPrice, a.gasPrice);
    if (byGasPrice != 0) return byGasPrice;
    // Stable fallback.
    return Long.compare(a.seq, b.seq);
  }

  // Per-node priority queues for pending client requests.
  // Keyed by node id to avoid holding strong HotStuffNode refs.
  private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.PriorityBlockingQueue<ClientReq>> nodeQueues =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong> nodeSeqs =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static class ClientReq {
    final String raw;
    final long fee;
    final long seq;
    final String sender;
    final long nonce;
    final long gasPrice;
    final long gasLimit;
    final String hash;

    ClientReq(String raw, long fee, long seq, String sender, long nonce, long gasPrice, long gasLimit, String hash) {
      this.raw = raw;
      this.fee = fee;
      this.seq = seq;
      this.sender = sender;
      this.nonce = nonce;
      this.gasPrice = gasPrice;
      this.gasLimit = gasLimit;
      this.hash = hash;
    }
  }

  /**
   * Add a parsed request into the per-node priority queue.
   * Extracts sender, nonce, gasPrice and gasLimit from the request JSON and computes the fee
   * (gasLimit * gasPrice, capped at Long.MAX_VALUE on overflow).
   *
   * @param nodeId      the id of the node owning the queue
   * @param requestJson the raw JSON string of the client request envelope
   * @param hash        the request hash, or null
   */
  public static void addClientRequestToQueue(int nodeId, String requestJson, String hash) {
    long seq = nodeSeqs.computeIfAbsent(nodeId, id -> new java.util.concurrent.atomic.AtomicLong(0)).getAndIncrement();
    // parse once here and store parsed fields on the ClientReq to avoid repeated JSON work
    String sender = null;
    long nonce = 0L;
    long gasPrice = 0L;
    long gasLimit = 0L;
    try {
      org.json.JSONObject obj = new org.json.JSONObject(requestJson);
      String v = obj.optString("value", null);
      if (v != null) {
        org.json.JSONObject tx = new org.json.JSONObject(v);
          // Only accept the 'sender' field as provided by clients/tests
          sender = tx.optString("sender", null);
        if (sender != null && sender.isBlank()) sender = null;
        nonce = tx.optLong("nonce", 0L);
        gasPrice = tx.optLong("gasPrice", 0L);
        gasLimit = tx.optLong("gasLimit", 0L);
      }
    } catch (Throwable ignore) {
    }

    java.util.concurrent.PriorityBlockingQueue<ClientReq> q = nodeQueues.computeIfAbsent(nodeId,
        id -> new java.util.concurrent.PriorityBlockingQueue<>(11, DataBlockManager::compareClientReq));

    // compute canonical fee here (ignore passed-in duplicate to avoid mismatch)
    long computedFee;
    try {
      computedFee = Math.multiplyExact(gasLimit, gasPrice);
    } catch (ArithmeticException ae) {
      computedFee = Long.MAX_VALUE;
    }
    q.offer(new ClientReq(requestJson, computedFee, seq, sender, nonce, gasPrice, gasLimit, hash));
  }

  /**
   * Expose pending client requests as a snapshot of raw JSON strings.
   *
   * @param nodeId the id of the node owning the queue
   * @return a list of raw JSON request strings currently in the queue
   */
  public static java.util.List<String> getClientRequests(int nodeId) {
    java.util.List<String> snap = new java.util.ArrayList<>();
    java.util.concurrent.PriorityBlockingQueue<ClientReq> q = nodeQueues.computeIfAbsent(nodeId,
        id -> new java.util.concurrent.PriorityBlockingQueue<>(11, DataBlockManager::compareClientReq));
    for (ClientReq r : q) snap.add(r.raw);
    return snap;
  }

  /**
   * Remove a single client request whose top-level JSON string equals the given raw string.
   *
   * @param nodeId the id of the node owning the queue
   * @param raw    the exact raw JSON string to match and remove
   * @return true if a matching entry was found and removed, false otherwise
   */
  public static boolean removeClientRequestExact(int nodeId, String raw) {
    java.util.concurrent.PriorityBlockingQueue<ClientReq> q = nodeQueues.get(nodeId);
    if (q == null) return false;
    for (ClientReq r : q) {
      if (r.raw.equals(raw)) return q.remove(r);
    }
    return false;
  }

  /**
   * Remove a single client request whose inner "value" field equals the given transaction string.
   *
   * @param nodeId the id of the node owning the queue
   * @param txStr  the transaction JSON string to match against the envelope's "value" field
   * @return true if a matching entry was found and removed, false otherwise
   */
  public static boolean removeClientRequestMatchingInnerValue(int nodeId, String txStr) {
    java.util.concurrent.PriorityBlockingQueue<ClientReq> q = nodeQueues.get(nodeId);
    if (q == null) return false;
    for (ClientReq r : q) {
      try {
        org.json.JSONObject req = new org.json.JSONObject(r.raw);
        String v = req.optString("value", null);
        if (v != null && v.equals(txStr)) return q.remove(r);
      } catch (Throwable ignore) {
      }
    }
    return false;
  }

  /**
   * Return a snapshot of raw request strings sorted by queue ordering (gas price descending, nonce ascending).
   *
   * @param nodeId the id of the node owning the queue
   * @return sorted list of raw JSON request strings
   */
  public static java.util.List<String> snapshotClientReqsSorted(int nodeId) {
    java.util.concurrent.PriorityBlockingQueue<ClientReq> q = nodeQueues.computeIfAbsent(nodeId,
        id -> new java.util.concurrent.PriorityBlockingQueue<>(11, DataBlockManager::compareClientReq));
    java.util.List<ClientReq> s = new java.util.ArrayList<>(q);
    s.sort(DataBlockManager::compareClientReq);
    java.util.List<String> raws = new java.util.ArrayList<>();
    for (ClientReq r : s) raws.add(r.raw);
    return raws;
  }


  /**
   * Select the highest-priority requests from the queue that fit within the gas budget.
   * Enforces per-sender nonce ordering so lower-nonce requests are always included first.
   *
   * @param nodeId      the id of the node owning the queue
   * @param blockMaxGas maximum total gas allowed for the selected batch
   * @return list of raw JSON request strings that fit within the gas budget
   */
  public static java.util.List<String> pollTopRequestsForGas(int nodeId, long blockMaxGas) {
    java.util.List<String> selected = new java.util.ArrayList<>();
    long totalGas = 0L;
    java.util.concurrent.PriorityBlockingQueue<ClientReq> q = nodeQueues.computeIfAbsent(nodeId,
        id -> new java.util.concurrent.PriorityBlockingQueue<>(11, DataBlockManager::compareClientReq));
    java.util.List<ClientReq> s = new java.util.ArrayList<>(q);
    s.sort(DataBlockManager::compareClientReq);
    java.util.Map<String, java.util.Set<Long>> selectedNonces = new java.util.HashMap<>();
    for (ClientReq r : s) {
      try {
        // Enforce strict per-sender nonce ordering via stored fields
        if (r.sender != null) {
          boolean hasLower = false;
          for (ClientReq x : q) {
            if (x.sender != null && x.sender.equals(r.sender) && x.nonce < r.nonce) {
              // if this lower nonce is already selected in this batch, it won't block
              java.util.Set<Long> sel = selectedNonces.get(r.sender);
              if (sel != null && sel.contains(x.nonce)) continue;
              hasLower = true;
              break;
            }
          }
          if (hasLower) {
            continue;
          }
        }
        long gasLimit = r.gasLimit != 0L ? r.gasLimit : 200_000L;
        if (totalGas + gasLimit > blockMaxGas) continue;
        totalGas += gasLimit;
        selected.add(r.raw);
        // mark nonce as selected for this sender
        if (r.sender != null) {
          selectedNonces.computeIfAbsent(r.sender, k -> new java.util.HashSet<>()).add(r.nonce);
        }
      } catch (Throwable ignore) {
      }
    }
    return selected;
  }


  /**
   * Build a batch from the node's pending clientRequests honoring the
   * BLOCK_MAX_GAS limit, register it as a data block and remove matched
   * clientRequests. Returns the serialized blockData or "NO_OP".
   *
   * @param nodeId            the id of the node owning the queue
   * @param previousBlockHash hash of the previous committed block, included in the output JSON
   * @return JSON string containing the selected transactions, previous block hash, and computed block hash
   */
  public static String buildAndRegisterDataBlock(int nodeId, String previousBlockHash) {
    final long BLOCK_MAX_GAS = 600_000L;
    // Atomically select and remove top requests up to gas limit
    JSONArray batch = new JSONArray();
    try {
      java.util.List<String> selected = pollTopRequestsForGas(nodeId, BLOCK_MAX_GAS);
      for (String reqRaw : selected) {
        try {
          org.json.JSONObject req = new org.json.JSONObject(reqRaw);
          // Keep the full envelope for block entries, to make verification deterministic
          // and avoid repeated transaction->queue matching issues.
          batch.put(req);
        } catch (Throwable ignore) {
        }
      }
    } catch (Throwable ignore) {
    }


    // Return a JSON object string containing the selected transactions,
    // the previous block hash and a computed block_hash over the transactions
    JSONObject out = new JSONObject();
    out.put("transactions", batch);
    out.put("previous_block_hash", previousBlockHash == null ? JSONObject.NULL : previousBlockHash);

    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      String txString = batch.toString();
      byte[] hashed = digest.digest(txString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      // convert to 0x-prefixed hex
      StringBuilder sb = new StringBuilder();
      sb.append("0x");
      for (byte b : hashed) {
        sb.append(String.format("%02x", b));
      }
      out.put("block_hash", sb.toString());
    } catch (Throwable ignore) {
      out.put("block_hash", JSONObject.NULL);
    }

    return out.toString();
  }


  /**
   * Validate and enqueue a client request into the node's pending queue.
   * Rejects requests with missing value payload, malformed tx JSON,
   * gasPrice below 1, or gasLimit outside the allowed range (0, MAX_TX_GAS].
   *
   * @param nodeId      the id of the node owning the queue
   * @param requestJson the raw JSON string of the client request envelope
   * @param hash        the request hash, or null
   */
  public static void enqueueClientRequest(int nodeId, String requestJson, String hash) {
    final long MAX_TX_GAS = 200_000L; // per-transaction cap
    try {
      org.json.JSONObject obj = new org.json.JSONObject(requestJson);
      // the value field contains the tx JSON string for REQUEST
      if (!obj.has("value")) {
        System.err.println("[" + nodeId + "] Rejected client REQUEST: missing value payload");
        return;
      }
      org.json.JSONObject tx;
      try {
        tx = new org.json.JSONObject(obj.getString("value"));
      } catch (Throwable e) {
        System.err.println("[" + nodeId + "] Rejected client REQUEST: malformed tx JSON");
        return;
      }

      long gasLimit = tx.optLong("gasLimit", 0L);
      long gasPrice = tx.optLong("gasPrice", 0L);

      if (gasPrice < 1L) {
        System.err.println("[" + nodeId + "] Rejected client REQUEST: gasPrice must be >= 1");
        return;
      }
      if (gasLimit <= 0L || gasLimit > MAX_TX_GAS) {
        System.err.println("[" + nodeId + "] Rejected client REQUEST: gasLimit must be >0 and <= " + MAX_TX_GAS);
        return;
      }

      // Add into node's priority queue (now managed by DataBlockManager)
      addClientRequestToQueue(nodeId, requestJson, hash);
    } catch (Throwable t) {
      System.err.println("[" + nodeId + "] Failed to parse/enqueue client request: " + t.getMessage());
    }
  }
}
