package com.g27.DepChain.HotStuff;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class QC {
  private final String blockDataHash;
  private final String dataHash;
  private final String signature;
  private final Set<Integer> voters = new HashSet<>();
  private final String requestType;
  private final int viewNumber;

  public QC(String blockDataHash, String dataHash, String signature, Set<Integer> voters, String requestType,
      int viewNumber) {
    this.blockDataHash = blockDataHash;
    this.dataHash = dataHash;
    this.signature = signature;
    this.voters.addAll(voters);
    this.requestType = requestType;
    this.viewNumber = viewNumber;
  }

  public String getBlockDataHash() {
    return blockDataHash;
  }

  public String getDataHash() {
    return dataHash;
  }

  public String getSignature() {
    return signature;
  }

  public String getRequestType() {
    return requestType;
  }

  public int getViewNumber() {
    return viewNumber;
  }

  public Set<Integer> getVoters() {
    return voters;
  }

  @Override
  public String toString() {
    JSONObject obj = new JSONObject();
    if (blockDataHash != null)
      obj.put("blockHash", blockDataHash);
    if (dataHash != null)
      obj.put("dataHash", dataHash);
    if (signature != null)
      obj.put("sig", signature);
    if (requestType != null)
      obj.put("type", requestType);
    obj.put("view", viewNumber);
    obj.put("voters", new JSONArray(voters));
    return obj.toString();
  }

  /**
   * This constructor accepts a JSON string and creates a QC based on the
   * information in there.
   */
  public QC(String s) {
    // Accept explicit null marker
    if (s == null || "null".equals(s)) {
      this.blockDataHash = null;
      this.dataHash = null;
      this.signature = null;
      this.requestType = null;
      this.viewNumber = -1;
      this.voters.clear();
      return;
    }

    JSONObject obj;
    try {
      obj = new JSONObject(s);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid QC JSON content: " + s, ex);
    }

    String blockDataHash = obj.has("blockHash") ? obj.optString("blockHash", null) : null;
    String dataHash = obj.has("dataHash") ? obj.optString("dataHash", null) : null;
    String signature = obj.has("sig") ? obj.optString("sig", null) : null;
    String requestType = obj.has("type") ? obj.optString("type", null) : null;
    int viewNumber = obj.has("view") ? obj.optInt("view", -1) : -1;
    JSONArray votersArray = obj.has("voters") ? obj.getJSONArray("voters") : null;
    this.blockDataHash = blockDataHash;
    this.dataHash = dataHash;
    this.signature = signature;
    this.requestType = requestType;
    this.viewNumber = viewNumber;
    if (votersArray != null) {
      for (int i = 0; i < votersArray.length(); i++) {
        this.voters.add(votersArray.getInt(i));
      }
    }
  }

  public boolean equalBlock(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof QC))
      return false;
    QC other = (QC) o;
    return Objects.equals(this.blockDataHash, other.blockDataHash)
        && Objects.equals(this.dataHash, other.dataHash)
        && this.viewNumber == other.viewNumber &&
        Objects.equals(this.requestType, other.requestType) &&
        Objects.equals(this.signature, other.signature) &&
        Objects.equals(this.voters, other.voters);
  }
}
