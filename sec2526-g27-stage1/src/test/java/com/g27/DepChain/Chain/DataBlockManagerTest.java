package com.g27.DepChain.Chain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * App-layer unit tests for DataBlockManager ordering, validation, and block assembly.
 *
 * These tests intentionally avoid node/runtime initialization and focus only on
 * deterministic queue and batching behavior.
 */
public class DataBlockManagerTest {

  private static final AtomicInteger NEXT_NODE_ID = new AtomicInteger(10_000);
  private int nodeId;

  @BeforeEach
  public void setup() {
    // DataBlockManager keeps static state; use a fresh node id per test.
    nodeId = NEXT_NODE_ID.getAndIncrement();
  }

  private String makeEnvelope(JSONObject tx) {
    JSONObject env = new JSONObject();
    env.put("type", "REQUEST");
    env.put("value", tx.toString());
    return env.toString();
  }

  /** SHA-256 utility for stable requestHash values used in tests. */
  private String computeHash(String content) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      sb.append("0x");
      for (byte b : hashed) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private JSONObject tx(String sender, long nonce, long gasLimit, long gasPrice) {
    return new JSONObject()
        .put("sender", sender)
        .put("txType", "TRANSFER")
        .put("nonce", nonce)
        .put("gasLimit", gasLimit)
        .put("gasPrice", gasPrice);
  }

  @Test
  public void differentClients_higherGasPriceIsOrderedFirst() {
    JSONObject txLow = tx("0x1", 1L, 10_000L, 1L);
    JSONObject txHigh = tx("0x2", 1L, 1_000L, 9L);

    String envLow = makeEnvelope(txLow);
    String envHigh = makeEnvelope(txHigh);

    // Add low first, then high; ordering must still prioritize highest gas price.
    DataBlockManager.addClientRequestToQueue(nodeId, envLow, computeHash(txLow.toString()));
    DataBlockManager.addClientRequestToQueue(nodeId, envHigh, computeHash(txHigh.toString()));

    // Keep tx values unchanged; raise cap so both fit and ordering is what we validate.
    List<String> selected = DataBlockManager.pollTopRequestsForGas(nodeId, 11_000L);
    assertEquals(2, selected.size());
    assertEquals(envHigh, selected.get(0));
    assertEquals(envLow, selected.get(1));
  }

  @Test
  public void sameClient_nonceOrderingOverridesGasPrice() {
    JSONObject highNonceHighPrice = tx("0xabc", 2L, 1_000L, 100L);
    JSONObject lowNonceLowPrice = tx("0xabc", 1L, 1_000L, 1L);

    String envHighNonce = makeEnvelope(highNonceHighPrice);
    String envLowNonce = makeEnvelope(lowNonceLowPrice);

    // Add nonce=2 first, then nonce=1. Same sender must be selected by nonce order.
    DataBlockManager.addClientRequestToQueue(nodeId, envHighNonce, computeHash(highNonceHighPrice.toString()));
    DataBlockManager.addClientRequestToQueue(nodeId, envLowNonce, computeHash(lowNonceLowPrice.toString()));

    String blockData = DataBlockManager.buildAndRegisterDataBlock(nodeId, "0x00");
    JSONObject out = new JSONObject(blockData);
    JSONArray txs = out.getJSONArray("transactions");
    assertEquals(2, txs.length());

    JSONObject tx0 = new JSONObject(txs.getJSONObject(0).getString("value"));
    JSONObject tx1 = new JSONObject(txs.getJSONObject(1).getString("value"));
    assertEquals(1L, tx0.getLong("nonce"));
    assertEquals(2L, tx1.getLong("nonce"));
  }

  @Test
  public void enqueueClientRequest_acceptsValidTransaction() {
    JSONObject validTx = tx("0x11", 1L, 120_000L, 2L);
    String env = makeEnvelope(validTx);

    DataBlockManager.enqueueClientRequest(nodeId, env, computeHash(validTx.toString()));

    List<String> queued = DataBlockManager.getClientRequests(nodeId);
    assertEquals(1, queued.size());
    assertEquals(env, queued.get(0));
  }

  @Test
  public void enqueueClientRequest_rejectsInvalidCases() {
    JSONObject validTx = tx("0x22", 1L, 100_000L, 1L);
    String validEnv = makeEnvelope(validTx);

    String missingValueEnv = new JSONObject().put("type", "REQUEST").toString();
    String malformedValueEnv = new JSONObject().put("type", "REQUEST").put("value", "not-json").toString();
    String badGasPriceEnv = makeEnvelope(tx("0x22", 1L, 100_000L, 0L));
    String gasLimitZeroEnv = makeEnvelope(tx("0x22", 1L, 0L, 1L));
    String gasLimitTooHighEnv = makeEnvelope(tx("0x22", 1L, 200_001L, 1L));

    java.io.PrintStream originalErr = System.err;
    try {
      System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
      DataBlockManager.enqueueClientRequest(nodeId, missingValueEnv, computeHash(missingValueEnv));
      DataBlockManager.enqueueClientRequest(nodeId, malformedValueEnv, computeHash(malformedValueEnv));
      DataBlockManager.enqueueClientRequest(nodeId, badGasPriceEnv, computeHash(badGasPriceEnv));
      DataBlockManager.enqueueClientRequest(nodeId, gasLimitZeroEnv, computeHash(gasLimitZeroEnv));
      DataBlockManager.enqueueClientRequest(nodeId, gasLimitTooHighEnv, computeHash(gasLimitTooHighEnv));
    } finally {
      System.setErr(originalErr);
    }

    // Only valid one should be accepted.
    DataBlockManager.enqueueClientRequest(nodeId, validEnv, computeHash(validTx.toString()));
    List<String> queued = DataBlockManager.getClientRequests(nodeId);
    assertEquals(1, queued.size());
    assertEquals(validEnv, queued.get(0));
  }

  @Test
  public void pollTopRequestsForGas_respectsProvidedBlockGasLimit() {
    JSONObject txA = tx("0xA", 1L, 300_000L, 5L);
    JSONObject txB = tx("0xB", 1L, 200_000L, 4L);
    String envA = makeEnvelope(txA);
    String envB = makeEnvelope(txB);

    DataBlockManager.addClientRequestToQueue(nodeId, envA, computeHash(txA.toString()));
    DataBlockManager.addClientRequestToQueue(nodeId, envB, computeHash(txB.toString()));

    // 300k fits, adding 200k would exceed 400k.
    List<String> selected = DataBlockManager.pollTopRequestsForGas(nodeId, 400_000L);
    assertEquals(1, selected.size());
    assertEquals(envA, selected.get(0));
  }

  @Test
  public void pollTopRequestsForGas_usesDefaultGasLimitWhenMissing() {
    JSONObject txMissingGas = new JSONObject()
        .put("sender", "0xD")
        .put("txType", "TRANSFER")
        .put("nonce", 1)
        .put("gasPrice", 3);

    String env = makeEnvelope(txMissingGas);
    DataBlockManager.addClientRequestToQueue(nodeId, env, computeHash(txMissingGas.toString()));

    // Missing gasLimit should be treated as default 200k by selector.
    List<String> selected = DataBlockManager.pollTopRequestsForGas(nodeId, 199_999L);
    assertEquals(0, selected.size());
    selected = DataBlockManager.pollTopRequestsForGas(nodeId, 200_000L);
    assertEquals(1, selected.size());
  }

  @Test
  public void buildAndRegisterDataBlock_fieldsAndDeterministicHash() throws Exception {
    JSONObject tx1 = tx("0x1", 1L, 1_000L, 1L);
    JSONObject tx2 = tx("0x2", 1L, 2_000L, 1L);
    String env1 = makeEnvelope(tx1);
    String env2 = makeEnvelope(tx2);
    DataBlockManager.addClientRequestToQueue(nodeId, env1, computeHash(tx1.toString()));
    DataBlockManager.addClientRequestToQueue(nodeId, env2, computeHash(tx2.toString()));

    String parentHash = "0xdeadbeef";
    String data = DataBlockManager.buildAndRegisterDataBlock(nodeId, parentHash);
    assertNotNull(data);

    JSONObject out = new JSONObject(data);
    assertTrue(out.has("transactions"));
    assertTrue(out.has("previous_block_hash"));
    assertTrue(out.has("block_hash"));
    assertEquals(parentHash, out.getString("previous_block_hash"));

    JSONArray txs = out.getJSONArray("transactions");
    assertEquals(2, txs.length());

    String txString = txs.toString();
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] hashed = digest.digest(txString.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    sb.append("0x");
    for (byte b : hashed) {
      sb.append(String.format("%02x", b));
    }
    String expected = sb.toString();
    assertEquals(expected, out.optString("block_hash"));
  }

  @Test
  public void buildAndRegisterDataBlock_respectsDefaultMaxGasPerBlock() {
    // BLOCK_MAX_GAS inside DataBlockManager is 600_000.
    JSONObject txA = tx("0xA", 1L, 300_000L, 10L);
    JSONObject txB = tx("0xB", 1L, 300_000L, 9L);
    JSONObject txC = tx("0xC", 1L, 100_000L, 8L);

    String envA = makeEnvelope(txA);
    String envB = makeEnvelope(txB);
    String envC = makeEnvelope(txC);

    DataBlockManager.addClientRequestToQueue(nodeId, envA, computeHash(txA.toString()));
    DataBlockManager.addClientRequestToQueue(nodeId, envB, computeHash(txB.toString()));
    DataBlockManager.addClientRequestToQueue(nodeId, envC, computeHash(txC.toString()));

    String blockData = DataBlockManager.buildAndRegisterDataBlock(nodeId, "0xparent");
    JSONObject out = new JSONObject(blockData);
    JSONArray txs = out.getJSONArray("transactions");

    // Should include exactly A and B (300k + 300k) and skip C due to 600k cap.
    assertEquals(2, txs.length());
    assertEquals(envA, txs.getJSONObject(0).toString());
    assertEquals(envB, txs.getJSONObject(1).toString());
  }

  @Test
  public void removeExactAndMatchingInnerValue_workAsExpected() {
    JSONObject tx = tx("0x9", 1L, 1_000L, 1L);
    String env = makeEnvelope(tx);

    DataBlockManager.addClientRequestToQueue(nodeId, env, computeHash(tx.toString()));
    assertTrue(DataBlockManager.removeClientRequestExact(nodeId, env));

    DataBlockManager.addClientRequestToQueue(nodeId, env, computeHash(tx.toString()));
    assertTrue(DataBlockManager.removeClientRequestMatchingInnerValue(nodeId, tx.toString()));
  }

  @Test
  public void buildAndRegisterDataBlock_handlesNullParentHash() {
    JSONObject tx = tx("0x777", 1L, 1_000L, 1L);
    String env = makeEnvelope(tx);
    DataBlockManager.addClientRequestToQueue(nodeId, env, computeHash(tx.toString()));

    String blockData = DataBlockManager.buildAndRegisterDataBlock(nodeId, null);
    JSONObject out = new JSONObject(blockData);
    assertTrue(out.isNull("previous_block_hash"));
  }
}
