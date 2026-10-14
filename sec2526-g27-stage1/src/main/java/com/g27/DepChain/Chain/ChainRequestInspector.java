package com.g27.DepChain.Chain;

import org.json.JSONObject;

import com.g27.DepChain.HotStuff.HotStuffNode;

/**
 * Chain-side helper used by block verification to ensure that included
 * requests are either present in the node's pending queue or are valid
 * signed client requests.
 */
public class ChainRequestInspector {

  /**
   * Verify a block-level entry. The input may be either an inner transaction
   * object (contains `txType`/`sender`) or a full client envelope (contains
   * `address`, `value`, `signature`).
   *
   * - If the block contains an inner tx (no envelope), accept it only if a
   *   matching envelope exists in the pending queue and that envelope passes
   *   full validation.
   * - If the block contains a full envelope (address + value + signature),
   *   validate it on the fly. If `clientId` is missing, resolve it via the
   *   node's address-to-id mapping; fail if unknown.
   *
   * @param node     the local HotStuff replica node (provides pending queue and address maps)
   * @param entryRaw the raw JSON string of the block entry to verify
   * @return a ValidationResult indicating success or the reason for failure
   */
  public static RequestValidator.ValidationResult verifyOrAcceptFromPending(HotStuffNode node, String entryRaw) {
    try {
      if (entryRaw == null) return RequestValidator.ValidationResult.fail("null entry");
      String trimmed = entryRaw.trim();
      if (trimmed.isEmpty()) return RequestValidator.ValidationResult.fail("empty entry");

      JSONObject parsed = new JSONObject(trimmed);

      // Case 2 — full client envelope present in the block (address + value + signature)
      if (parsed.has("address") && parsed.has("value") && parsed.has("signature")) {
        String address = parsed.optString("address", null);
        if (address == null) return RequestValidator.ValidationResult.fail("missing address in envelope");
        Integer found = node.getClientIdForAddress(address);
        if (found == null) return RequestValidator.ValidationResult.fail("unknown client id for address in envelope");
        int clientId = found;

        String value = parsed.optString("value", null);
        String signature = parsed.optString("signature", null);
        String reqHash = parsed.optString("requestHash", null);
        return RequestValidator.validateClientRequest(
          node.getClientEcdsaAddresses(),
          node.getClientEcdsaPublicBytes(),
          node.getClientNonces(),
          clientId,
          value,
          signature,
          reqHash,
          address
        );
      }

      // Case 1 — inner tx (no signature/clientId): look for matching pending envelope
      if (parsed.has("txType") || parsed.has("sender")) {
        try {
          for (String rawEnv : node.getClientRequests()) {
            try {
              JSONObject env = new JSONObject(rawEnv);
              String v = env.optString("value", null);
              if (v == null) continue;
              boolean match = false;
              // If envelope has a requestHash, check it first (SHA-256 expected format 0x...)
              String envReqHash = env.optString("requestHash", null);
              if (envReqHash != null) {
                try {
                  java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                  byte[] hashed = md.digest(parsed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  StringBuilder sb = new StringBuilder(); sb.append("0x");
                  for (byte b : hashed) sb.append(String.format("%02x", b));
                  if (sb.toString().equalsIgnoreCase(envReqHash)) {
                    match = true;
                  }
                } catch (Throwable ignore2) {
                }
              }
              if (!match) {
                // fallback: compare inner JSON content equality (value may be serialized differently)
                try {
                  JSONObject inner = new JSONObject(v);
                  match = inner.toString().equals(parsed.toString());
                } catch (Throwable ignore) {
                  match = v.equals(parsed.toString());
                }
              }
              if (!match) continue;

              // Found matching envelope — accept
              return RequestValidator.ValidationResult.ok();
            } catch (Throwable ignore) {
            }
          }
        } catch (Throwable ignore) {
        }
        return RequestValidator.ValidationResult.fail("transaction not found in pending client requests");
      }
      // Always derive clientId from the address (clientId is not provided by envelopes)
      if (parsed.has("address") && parsed.has("value") && parsed.has("signature")) {
        String address = parsed.optString("address", null);
        if (address == null) return RequestValidator.ValidationResult.fail("missing address in envelope");
        Integer found = node.getClientIdForAddress(address);
        if (found == null) return RequestValidator.ValidationResult.fail("unknown client id for address in envelope");
        int clientId = found;

        String value = parsed.optString("value", null);
        String signature = parsed.optString("signature", null);
        String reqHash = parsed.optString("requestHash", null);
        return RequestValidator.validateClientRequest(
          node.getClientEcdsaAddresses(),
          node.getClientEcdsaPublicBytes(),
          node.getClientNonces(),
          clientId,
          value,
          signature,
          reqHash,
          address
        );
      }

      return RequestValidator.ValidationResult.fail("unrecognized entry format");
    } catch (Throwable t) {
      return RequestValidator.ValidationResult.fail("inspector error: " + t.getMessage());
    }
  }

  /**
   * Verify all transactions contained in a block data string.
   * Accepts the same entry formats supported by verifyOrAcceptFromPending.
   * Returns a ValidationResult.ok() when all entries are valid and the total
   * gas does not exceed the block limit.
   *
   * @param node      the local HotStuff replica node (provides pending queue and address maps)
   * @param blockData the raw block data string (JSON array, object, or single transaction)
   * @return a ValidationResult indicating success or the reason for failure
   */
  public static RequestValidator.ValidationResult verifyBlockTransactions(HotStuffNode node, String blockData) {
    try {
      final long BLOCK_MAX_GAS = 600_000L;
      if (blockData == null || "NO_OP".equals(blockData)) return RequestValidator.ValidationResult.ok();
      org.json.JSONArray txArray = new org.json.JSONArray();
      String s = blockData.trim();
      if (s.startsWith("[")) {
        txArray = new org.json.JSONArray(blockData);
      } else if (s.startsWith("{")) {
        org.json.JSONObject parsed = new org.json.JSONObject(blockData);
        if (parsed.has("transactions")) txArray = parsed.getJSONArray("transactions");
        else {
          txArray = new org.json.JSONArray();
          txArray.put(parsed);
        }
      } else {
        try {
          org.json.JSONObject parsed = new org.json.JSONObject(blockData);
          txArray.put(parsed);
        } catch (Throwable ignore) {
        }
      }

      long totalGas = 0L;
      java.util.Map<String, Long> lastNonceBySender = new java.util.HashMap<>();
      for (int i = 0; i < txArray.length(); i++) {
        try {
          org.json.JSONObject tx = txArray.getJSONObject(i);
          long gasLimit = tx.optLong("gasLimit", 0L);
          totalGas += gasLimit;
          if (totalGas > BLOCK_MAX_GAS) {
            return RequestValidator.ValidationResult.fail("total gas " + totalGas + " exceeds limit " + BLOCK_MAX_GAS);
          }

          // Enforce per-client nonce ordering inside the block: nonces must be strictly increasing
          String sender = tx.optString("sender", null);
          if (sender != null && tx.has("nonce")) {
            long nonce = tx.optLong("nonce", Long.MIN_VALUE);
            Long last = lastNonceBySender.get(sender);
            if (last != null) {
              if (nonce <= last) return RequestValidator.ValidationResult.fail("nonce ordering violated for sender " + sender);
            }
            lastNonceBySender.put(sender, nonce);
          }

          // Accept if the transaction is present in pending client requests or validate envelope
          RequestValidator.ValidationResult vr = verifyOrAcceptFromPending(node, tx.toString());
          if (!vr.valid) {
            // If it's not accepted as inner-tx, maybe the block entry is a full envelope; try validating as envelope
            try {
              org.json.JSONObject maybeEnv = new org.json.JSONObject(tx.toString());
              if (maybeEnv.has("address") && maybeEnv.has("value") && maybeEnv.has("signature")) {
                // compute requestHash if present inside envelope check will be handled by validateClientRequest
                Integer found = node.getClientIdForAddress(maybeEnv.optString("address", ""));
                int clientId = found == null ? -1 : found;
                String reqHash = maybeEnv.optString("requestHash", null);
                RequestValidator.ValidationResult vr2 = RequestValidator.validateClientRequest(
                    node.getClientEcdsaAddresses(), node.getClientEcdsaPublicBytes(), node.getClientNonces(),
                    clientId, maybeEnv.optString("value", null), maybeEnv.optString("signature", null), reqHash,
                    maybeEnv.optString("address", null)
                );
                if (!vr2.valid) return vr2;
              } else {
                return vr; // original failure
              }
            } catch (Throwable ignore) {
              return vr;
            }
          }
        } catch (Throwable ignore) {
        }
      }

      return RequestValidator.ValidationResult.ok();
    } catch (Throwable t) {
      return RequestValidator.ValidationResult.fail("inspector error: " + t.getMessage());
    }
  }




}
