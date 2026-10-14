package com.g27.DepChain.Chain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;

/**
 * App-layer signature and data verification tests.
 *
 * Scope (not HotStuff consensus and without node/socket initialization):
 * - Client request signature + payload validation.
 * - Read-request validation checks.
 * - Mock block payload transaction checks (nonce ordering + gas cap + envelope validity).
 * - Replica response signing and client-side verification compatibility.
 */
public class ChainBlockVerificationTest {

  private static final int CLIENT_ID = 0;
  private static final String CLIENT_ADDR_NORM = "abc";

  private Map<Integer, String> clientAddresses;
  private Map<Integer, byte[]> clientPubBytes;
  private Map<String, Long> clientNonces;

  private ECKeyPair clientKeyPair;
  private ECKeyPair replicaKeyPair;

  @BeforeEach
  public void setup() throws Exception {
    clientKeyPair = Keys.createEcKeyPair();
    replicaKeyPair = Keys.createEcKeyPair();

    byte[] clientPubRaw = toRaw64(clientKeyPair.getPublicKey().toByteArray());

    clientAddresses = new HashMap<>();
    clientPubBytes = new HashMap<>();
    clientNonces = new HashMap<>();

    clientAddresses.put(CLIENT_ID, CLIENT_ADDR_NORM);
    clientPubBytes.put(CLIENT_ID, clientPubRaw);
    clientNonces.put(CLIENT_ADDR_NORM, 0L);
  }

  private String signKeccakString(ECKeyPair kp, String value) {
    byte[] msg = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Sign.SignatureData sd = Sign.signMessage(Hash.sha3(msg), kp, false);
    byte[] s = new byte[65];
    System.arraycopy(sd.getR(), 0, s, 0, 32);
    System.arraycopy(sd.getS(), 0, s, 32, 32);
    s[64] = sd.getV()[0];
    return Numeric.toHexString(s);
  }

  private String sha256Hex(String s) throws Exception {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    sb.append("0x");
    for (byte b : h) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static byte[] toRaw64(byte[] pub) {
    if (pub.length == 65 && (pub[0] == 0x04 || pub[0] == 0x00)) {
      byte[] cropped = new byte[64];
      System.arraycopy(pub, 1, cropped, 0, 64);
      return cropped;
    }
    if (pub.length == 64) return pub;
    throw new IllegalArgumentException("Expected 64-byte raw pubkey, got " + pub.length);
  }

  private String makeSignedRequestEnvelope(JSONObject tx, String reqHash) {
    JSONObject req = new JSONObject();
    req.put("type", "REQUEST");
    req.put("address", "0x" + CLIENT_ADDR_NORM);
    req.put("value", tx.toString());
    req.put("signature", signKeccakString(clientKeyPair, tx.toString()));
    req.put("requestHash", reqHash);
    return req.toString();
  }

  private JSONObject tx(String senderHex, long nonce, long gasLimit, long gasPrice) {
    JSONObject tx = new JSONObject();
    tx.put("txType", "TRANSFER");
    tx.put("sender", senderHex);
    tx.put("gasLimit", gasLimit);
    tx.put("gasPrice", gasPrice);
    tx.put("nonce", nonce);
    return tx;
  }

  private RequestValidator.ValidationResult validateEnvelope(JSONObject envelope) {
    String addr = envelope.optString("address", null);
    Integer cid = (addr == null) ? null : CLIENT_ID;
    int clientId = (cid == null) ? -1 : cid;
    return RequestValidator.validateClientRequest(
        clientAddresses,
        clientPubBytes,
        clientNonces,
        clientId,
        envelope.optString("value", null),
        envelope.optString("signature", null),
        envelope.optString("requestHash", null),
        addr);
  }

  private boolean verifyWithRawPublicKey(byte[] raw64, String signedPayloadWithoutSignature, String signatureHex) {
    try {
      byte[] sigBytes = Numeric.hexStringToByteArray(signatureHex);
      byte[] r = java.util.Arrays.copyOfRange(sigBytes, 0, 32);
      byte[] s = java.util.Arrays.copyOfRange(sigBytes, 32, 64);

      byte[] h = Hash.sha3(signedPayloadWithoutSignature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      java.math.BigInteger x = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(raw64, 0, 32));
      java.math.BigInteger y = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(raw64, 32, 64));
      ECParameterSpec bcSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
      org.bouncycastle.math.ec.ECCurve curve = bcSpec.getCurve();
      ECPoint q = curve.createPoint(x, y);
      ECDomainParameters domain = new ECDomainParameters(curve, bcSpec.getG(), bcSpec.getN(), bcSpec.getH());
      ECPublicKeyParameters pubParams = new ECPublicKeyParameters(q, domain);
      ECDSASigner signer = new ECDSASigner();
      signer.init(false, pubParams);
      return signer.verifySignature(h, new java.math.BigInteger(1, r), new java.math.BigInteger(1, s));
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Verifies a mock block represented as an array of signed envelopes,
   * using only app-layer validation rules.
   */
  private RequestValidator.ValidationResult verifyMockBlockPayload(JSONArray entries) {
    final long blockMaxGas = 600_000L;
    long totalGas = 0L;
    Map<String, Long> lastNonceBySender = new HashMap<>();

    for (int i = 0; i < entries.length(); i++) {
      JSONObject env = entries.getJSONObject(i);
      RequestValidator.ValidationResult vr = validateEnvelope(env);
      if (!vr.valid) {
        return vr;
      }

      JSONObject tx = new JSONObject(env.getString("value"));
      String sender = tx.optString("sender", "").toLowerCase();
      long nonce = tx.optLong("nonce", -1L);
      long gasLimit = tx.optLong("gasLimit", 0L);

      Long last = lastNonceBySender.get(sender);
      if (last != null && nonce <= last) {
        return RequestValidator.ValidationResult.fail("nonce ordering violated for sender " + sender);
      }
      lastNonceBySender.put(sender, nonce);

      totalGas += gasLimit;
      if (totalGas > blockMaxGas) {
        return RequestValidator.ValidationResult.fail("total gas exceeds block max");
      }
    }

    return RequestValidator.ValidationResult.ok();
  }

  private String signReplicaPayload(ECKeyPair kp, String payload) {
    byte[] hash = Hash.sha3(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Sign.SignatureData sd = Sign.signMessage(hash, kp, false);
    byte[] sigBytes = new byte[65];
    System.arraycopy(sd.getR(), 0, sigBytes, 0, 32);
    System.arraycopy(sd.getS(), 0, sigBytes, 32, 32);
    sigBytes[64] = sd.getV()[0];
    return Numeric.toHexString(sigBytes);
  }

  @Test
  public void requestValidation_acceptsCorrectlySignedRequest() throws Exception {
    JSONObject tx = tx("0x" + CLIENT_ADDR_NORM, 0, 21000, 1);
    String reqHash = sha256Hex(tx.toString());
    JSONObject req = new JSONObject(makeSignedRequestEnvelope(tx, reqHash));

    RequestValidator.ValidationResult vr = validateEnvelope(req);

    assertTrue(vr.valid);
  }

  @Test
  public void requestValidation_rejectsIncorrectSignature() throws Exception {
    JSONObject tx = tx("0x" + CLIENT_ADDR_NORM, 0, 21000, 1);

    String badSignature = signKeccakString(Keys.createEcKeyPair(), tx.toString());
    JSONObject req = new JSONObject();
    req.put("address", "0x" + CLIENT_ADDR_NORM);
    req.put("value", tx.toString());
    req.put("signature", badSignature);
    req.put("requestHash", sha256Hex(tx.toString()));

    RequestValidator.ValidationResult vr = validateEnvelope(req);

    assertFalse(vr.valid);
  }

  @Test
  public void validateReadRequest_rejectsInvalidSignatureFormat() {
    JSONObject query = new JSONObject();
    query.put("op", "balanceOf");
    query.put("address", "0x" + CLIENT_ADDR_NORM);

    boolean ok = RequestValidator.validateReadRequest(
      clientAddresses,
        clientPubBytes,
        clientNonces,
        CLIENT_ID,
        "0x1234",
        query.toString());

    assertFalse(ok, "read signature with invalid length must be rejected");
  }

  @Test
  public void validateReadRequest_acceptsWellFormedSignature() {
    JSONObject query = new JSONObject();
    query.put("op", "balanceOf");
    query.put("address", "0x" + CLIENT_ADDR_NORM);

    String sig = signKeccakString(clientKeyPair, query.toString());
    boolean ok = RequestValidator.validateReadRequest(
      clientAddresses,
        clientPubBytes,
        clientNonces,
        CLIENT_ID,
        sig,
        query.toString());

    assertTrue(ok);
  }

  @Test
  public void blockPayloadValidation_acceptsValidSignedEnvelopesWithinGasAndNonceOrder() throws Exception {
    JSONObject tx0 = tx("0x" + CLIENT_ADDR_NORM, 0, 200000, 1);
    JSONObject tx1 = tx("0x" + CLIENT_ADDR_NORM, 1, 200000, 1);

    JSONArray arr = new JSONArray();
    arr.put(new JSONObject(makeSignedRequestEnvelope(tx0, sha256Hex(tx0.toString()))));
    arr.put(new JSONObject(makeSignedRequestEnvelope(tx1, sha256Hex(tx1.toString()))));

    RequestValidator.ValidationResult vr = verifyMockBlockPayload(arr);
    assertTrue(vr.valid);
  }

  @Test
  public void blockPayloadValidation_rejectsNonceOrderingViolation() throws Exception {
    JSONObject txHigh = tx("0x" + CLIENT_ADDR_NORM, 2, 21000, 1);
    JSONObject txLow = tx("0x" + CLIENT_ADDR_NORM, 1, 21000, 1);

    JSONArray arr = new JSONArray();
    arr.put(new JSONObject(makeSignedRequestEnvelope(txHigh, sha256Hex(txHigh.toString()))));
    arr.put(new JSONObject(makeSignedRequestEnvelope(txLow, sha256Hex(txLow.toString()))));

    RequestValidator.ValidationResult vr = verifyMockBlockPayload(arr);
    assertFalse(vr.valid);
  }

  @Test
  public void blockPayloadValidation_rejectsInvalidEnvelopeSignature() throws Exception {
    JSONObject tx = tx("0x" + CLIENT_ADDR_NORM, 0, 21000, 1);
    JSONObject env = new JSONObject();
    env.put("address", "0x" + CLIENT_ADDR_NORM);
    env.put("value", tx.toString());
    env.put("signature", signKeccakString(Keys.createEcKeyPair(), tx.toString()));
    env.put("requestHash", sha256Hex(tx.toString()));

    JSONArray arr = new JSONArray();
    arr.put(env);

    RequestValidator.ValidationResult vr = verifyMockBlockPayload(arr);
    assertFalse(vr.valid);
  }

  @Test
  public void blockPayloadValidation_rejectsGasOverBlockLimit() throws Exception {
    JSONObject tx0 = tx("0x" + CLIENT_ADDR_NORM, 0, 200000, 1);
    JSONObject tx1 = tx("0x" + CLIENT_ADDR_NORM, 1, 200000, 1);
    JSONObject tx2 = tx("0x" + CLIENT_ADDR_NORM, 2, 200000, 1);
    JSONObject tx3 = tx("0x" + CLIENT_ADDR_NORM, 3, 200000, 1);

    JSONArray arr = new JSONArray();
    arr.put(new JSONObject(makeSignedRequestEnvelope(tx0, sha256Hex(tx0.toString()))));
    arr.put(new JSONObject(makeSignedRequestEnvelope(tx1, sha256Hex(tx1.toString()))));
    arr.put(new JSONObject(makeSignedRequestEnvelope(tx2, sha256Hex(tx2.toString()))));
    arr.put(new JSONObject(makeSignedRequestEnvelope(tx3, sha256Hex(tx3.toString()))));

    RequestValidator.ValidationResult vr = verifyMockBlockPayload(arr);
    assertFalse(vr.valid);
  }

  @Test
  public void replicaResponseSignature_isClientVerifiable() throws Exception {
    JSONObject payload = new JSONObject();
    payload.put("type", "RESPONSE");
    payload.put("status", "committed");
    payload.put("requestHash", "0xabc123");
    payload.put("result", new JSONObject().put("success", true).put("gasUsed", 21000));

    String unsigned = payload.toString();
    String signature = signReplicaPayload(replicaKeyPair, unsigned);
    assertNotNull(signature);

    byte[] replicaPubRaw = toRaw64(replicaKeyPair.getPublicKey().toByteArray());
    assertTrue(verifyWithRawPublicKey(replicaPubRaw, unsigned, signature),
        "signature produced by replica path must be verifiable by client-side rules");

    // tampering invalidates signature
    JSONObject tampered = new JSONObject(unsigned);
    tampered.put("status", "failed");
    assertFalse(verifyWithRawPublicKey(replicaPubRaw, tampered.toString(), signature));
  }

  @Test
  public void requestHashMismatch_isRejectedByValidator() throws Exception {
    JSONObject tx = tx("0x" + CLIENT_ADDR_NORM, 0, 21000, 1);

    RequestValidator.ValidationResult vr = RequestValidator.validateClientRequest(
      clientAddresses,
      clientPubBytes,
      clientNonces,
        CLIENT_ID,
        tx.toString(),
        signKeccakString(clientKeyPair, tx.toString()),
        "0xdeadbeef",
        "0x" + CLIENT_ADDR_NORM);

    assertFalse(vr.valid);
    assertNotNull(vr.error);
    assertTrue(vr.error.contains("requestHash"));
  }

  /**
   * Cross-block nonce replay: if a sender's nonce was already committed (clientNonces tracks
   * expectedNext), replaying the same nonce in a subsequent block must be rejected.
   */
  @Test
  public void validateClientRequest_rejectsAlreadyCommittedNonce() throws Exception {
    // nonce 5 was the last committed transaction; expectedNext = 6
    clientNonces.put(CLIENT_ADDR_NORM, 6L);
    JSONObject t = tx("0x" + CLIENT_ADDR_NORM, 5, 21_000, 1);
    String reqHash = sha256Hex(t.toString());
    JSONObject req = new JSONObject(makeSignedRequestEnvelope(t, reqHash));

    RequestValidator.ValidationResult vr = validateEnvelope(req);

    assertFalse(vr.valid, "nonce already committed must be rejected");
    assertNotNull(vr.error);
    assertTrue(vr.error.contains("nonce"), "error must mention nonce, was: " + vr.error);
  }

  /**
   * Per-sender FIFO across blocks: a block proposing nonce=2 for a sender whose
   * committed nonce state already advanced to 3 must be rejected at validation time.
   */
  @Test
  public void blockPayloadValidation_rejectsNonceBelowCrossBlockCommitState() throws Exception {
    // block 1 committed nonce 2 for this sender; expectedNext = 3
    clientNonces.put(CLIENT_ADDR_NORM, 3L);
    JSONObject t = tx("0x" + CLIENT_ADDR_NORM, 2, 21_000, 1);
    String reqHash = sha256Hex(t.toString());

    JSONArray arr = new JSONArray();
    arr.put(new JSONObject(makeSignedRequestEnvelope(t, reqHash)));

    RequestValidator.ValidationResult vr = verifyMockBlockPayload(arr);

    assertFalse(vr.valid, "nonce=2 must be rejected when committed state shows expectedNext=3");
  }

  /**
   * Gas limit enforcement: a transaction with gasLimit > MAX_TX_GAS (200_000) must be
   * rejected by the validator even if the signature and all other fields are valid.
   */
  @Test
  public void validateClientRequest_rejectsGasLimitExceedingMax() throws Exception {
    JSONObject t = tx("0x" + CLIENT_ADDR_NORM, 0, 250_000, 1); // 250k > 200k limit
    String reqHash = sha256Hex(t.toString());
    JSONObject req = new JSONObject(makeSignedRequestEnvelope(t, reqHash));

    RequestValidator.ValidationResult vr = validateEnvelope(req);

    assertFalse(vr.valid, "gasLimit=250_000 exceeds MAX_TX_GAS=200_000 and must be rejected");
    assertNotNull(vr.error);
    assertTrue(vr.error.contains("gasLimit"), "error must mention gasLimit, was: " + vr.error);
  }

}
