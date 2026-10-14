package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

final class PersistedBlockFormatter {

  private PersistedBlockFormatter() {
  }

  /**
   * Transforms the blockData provided and returns it in a more readable form.
   */
  static String normalize(String blockData, List<String> persistedBlocks) {
    String previousHash = getLastPersistedBlockHash(persistedBlocks);
    JSONArray txs = new JSONArray();
    JSONObject out = new JSONObject();

    try {
      JSONObject parsed = new JSONObject(blockData);

      // Keep genesis-like payloads as-is.
      if (parsed.has("transactions") && parsed.has("previous_block_hash") && parsed.has("block_hash")) {
        return parsed.toString();
      }

      if (parsed.has("transactions") && parsed.opt("transactions") instanceof JSONArray) {
        txs = parsed.getJSONArray("transactions");
      } else {
        txs.put(parsed);
      }
    } catch (Throwable ignore) {
      // For non-JSON app payloads (e.g., testing strings), persist as a one-item tx
      // array.
      txs.put(blockData);
    }

    out.put("transactions", txs);
    out.put("previous_block_hash", previousHash == null ? JSONObject.NULL : previousHash);
    out.put("block_hash", hashTransactions(txs));
    return out.toString();
  }

  private static String getLastPersistedBlockHash(List<String> persistedBlocks) {
    if (persistedBlocks == null || persistedBlocks.isEmpty()) {
      return null;
    }
    try {
      JSONObject last = new JSONObject(persistedBlocks.get(persistedBlocks.size() - 1));
      if (last.has("block_hash") && !last.isNull("block_hash")) {
        return last.optString("block_hash", null);
      }
    } catch (Throwable ignore) {
    }
    return null;
  }

  private static String hashTransactions(JSONArray txs) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(txs.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder("0x");
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return JSONObject.NULL.toString();
    }
  }
}
