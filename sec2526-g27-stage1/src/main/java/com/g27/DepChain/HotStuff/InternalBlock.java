package com.g27.DepChain.HotStuff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class InternalBlock {
  private final String blockData;
  private final List<String> parentHashs = new ArrayList<>();
  private final List<QC> qcsList = new ArrayList<>();
  private final List<Integer> viewNumbers = new ArrayList<>();
  private final int blockNumber;

  public InternalBlock(String blockData, QC qc, String parentHash, int viewNumber, int blockNumber) {
    this.blockData = blockData;
    this.qcsList.add(qc);
    this.parentHashs.add(parentHash);
    this.viewNumbers.add(viewNumber);
    this.blockNumber = blockNumber;
  }

  /**
   * this constructor accepts a JSON string and creates a InternalBlock based on
   * the information.
   */
  public InternalBlock(String s) {
    if (s == null) {
      throw new IllegalArgumentException("Cannot parse null InternalBlock string");
    }
    String trimmed = s.trim();
    JSONObject obj = new JSONObject(trimmed);
    // delegate to JSONObject constructor
    String blockData = obj.optString("blockData", null);
    JSONArray parentHashesArray = obj.has("parentHashes") ? obj.getJSONArray("parentHashes") : null;
    String qcsObj = obj.has("qcs") ? obj.optString("qcs", null) : null;
    if (qcsObj != null) {
      JSONArray qcsArray = new JSONArray(qcsObj);
      for (int i = 0; i < qcsArray.length(); i++) {
        this.qcsList.add(new QC(qcsArray.getString(i)));
      }
    }
    if (parentHashesArray != null) {
      for (int i = 0; i < parentHashesArray.length(); i++) {
        this.parentHashs.add(parentHashesArray.getString(i));
      }
    }
    this.blockData = blockData;
    if (obj.has("views")) {
      JSONArray viewsArray = obj.getJSONArray("views");
      for (int i = 0; i < viewsArray.length(); i++) {
        this.viewNumbers.add(viewsArray.getInt(i));
      }
    }
    this.blockNumber = obj.optInt("blockNumber", -1);
  }

  public String getBlockData() {
    return blockData;
  }

  public List<QC> getQcs() {
    return qcsList;
  }

  public List<String> getParentHashes() {
    return parentHashs;
  }

  public List<Integer> getViewNumbers() {
    return viewNumbers;
  }

  public int getBlockNumber() {
    return blockNumber;
  }

  @Override
  public String toString() {
    return toJson().toString();
  }

  /**
   * Converts a InternalBlock to a JSON.
   */
  public JSONObject toJson() {
    JSONObject obj = new JSONObject();
    obj.put("blockData", blockData);
    if (parentHashs != null && !parentHashs.isEmpty()) {
      obj.put("parentHashes", new JSONArray(parentHashs));
    }
    obj.put("views", new JSONArray(viewNumbers));
    obj.put("blockNumber", blockNumber);
    if (qcsList != null && !qcsList.isEmpty()) {
      JSONArray qcsArray = new JSONArray();
      for (QC qc : qcsList) {
        qcsArray.put(qc.toString());
      }
      obj.put("qcs", qcsArray);
    } else {
      obj.put("qcs", JSONObject.NULL);
    }
    return obj;
  }

  /**
   * Computes and returns the block hash for a given phase.
   */
  public String getHash(int phase) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String blockDatahash = blockData != null
          ? Base64.getEncoder().encodeToString(digest.digest(blockData.getBytes(StandardCharsets.UTF_8)))
          : "null";
      String content = blockDatahash + "|" + parentHashs.get(phase) + "|" + viewNumbers.get(phase) + "|"
          + qcsList.get(phase).toString();
      String hash = Base64.getEncoder().encodeToString(digest.digest(content.getBytes(StandardCharsets.UTF_8)));

      return hash;
    } catch (Exception e) {
      throw new RuntimeException("Failed to compute block hash", e);
    }
  }

}
