package com.g27.DepChain.Chain;

import org.json.JSONObject;
import org.web3j.utils.Numeric;
import org.web3j.crypto.Hash;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;

/**
 * Application-layer request validation. Accepts only minimal maps/fields
 * so callers don't need to pass whole HotStuffNode instances across packages.
 */
public class RequestValidator {

    /**
     * Holds the outcome of a validation check.
     * On success, `valid` is true and `error` is null.
     * On failure, `valid` is false and `error` contains the reason.
     */
    public static class ValidationResult {
        /** Whether the validation passed. */
        public final boolean valid;
        /** Failure reason, or null if valid. */
        public final String error;

        /**
         * @param valid whether the validation passed
         * @param error failure reason, or null if valid
         */
        public ValidationResult(boolean valid, String error) { this.valid = valid; this.error = error; }

        /**
         * Create a successful ValidationResult.
         *
         * @return a ValidationResult with valid=true and no error
         */
        public static ValidationResult ok() { return new ValidationResult(true, null); }

        /**
         * Create a failed ValidationResult.
         *
         * @param msg the reason for failure
         * @return a ValidationResult with valid=false and the given error message
         */
        public static ValidationResult fail(String msg) { return new ValidationResult(false, msg); }
    }

    /**
     * Validate a client request envelope: verifies the ECDSA signature, the request hash (if present),
     * and basic transaction fields (txType, sender, gasLimit, gasPrice, nonce).
     * Also checks that the sender address matches the registered client address and that the nonce
     * is not lower than the expected next nonce.
     *
     * @param clientEcdsaAddresses  map from client id to normalized (no-0x, lowercase) Ethereum address
     * @param clientEcdsaPublicBytes map from client id to raw 64-byte X||Y ECDSA public key
     * @param clientNonces          map from normalized address to next expected nonce
     * @param clientId              the id of the client sending the request
     * @param value                 the JSON-encoded transaction string that was signed
     * @param signature             the hex-encoded ECDSA signature (r||s||v, 65 bytes)
     * @param requestHash           optional hash of the value (keccak-256 or SHA-256), or null to skip check
     * @param clientAddress         the Ethereum address from the request envelope (must match tx sender)
     * @return a ValidationResult indicating success or the reason for failure
     */
    public static ValidationResult validateClientRequest(Map<Integer, String> clientEcdsaAddresses,
                                                          Map<Integer, byte[]> clientEcdsaPublicBytes,
                                                          Map<String, Long> clientNonces,
                                                          int clientId,
                                                          String value,
                                                          String signature,
                                                          String requestHash,
                                                          String clientAddress) {
        try {
            if (value == null || signature == null || clientId < 0 || clientAddress == null) return ValidationResult.fail("missing fields");

            String expectedAddr = clientEcdsaAddresses.get(clientId);
            if (expectedAddr == null) return ValidationResult.fail("unknown client id");

            // parse signature
            String sigHex = signature.startsWith("0x") ? signature.substring(2) : signature;
            byte[] sigBytes = Numeric.hexStringToByteArray(sigHex);
            if (sigBytes == null || sigBytes.length != 65) return ValidationResult.fail("invalid signature length");
            byte[] r = Arrays.copyOfRange(sigBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(sigBytes, 32, 64);

            byte[] h = Hash.sha3(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] xy = clientEcdsaPublicBytes.get(clientId);
            if (xy == null) return ValidationResult.fail("missing stored public key for client");
            if (xy.length != 64) return ValidationResult.fail("stored public key has invalid length");

            BigInteger x = new BigInteger(1, Arrays.copyOfRange(xy, 0, 32));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(xy, 32, 64));
            ECParameterSpec bcSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            org.bouncycastle.math.ec.ECCurve curve = bcSpec.getCurve();
            ECPoint q = curve.createPoint(x, y);
            ECDomainParameters domain = new ECDomainParameters(curve, bcSpec.getG(), bcSpec.getN(), bcSpec.getH());
            ECPublicKeyParameters pubParams = new ECPublicKeyParameters(q, domain);
            ECDSASigner signer = new ECDSASigner();
            signer.init(false, pubParams);
            BigInteger rBig = new BigInteger(1, r);
            BigInteger sBig = new BigInteger(1, s);
            boolean verified = signer.verifySignature(h, rBig, sBig);
            if (!verified) return ValidationResult.fail("signature does not verify against stored public key");

            // If a requestHash was provided, verify it matches the hash of the value.
            // Accept either the keccak-256 (used for signing) or SHA-256 (used by block/request utilities).
            if (requestHash != null) {
                try {
                    String rh = (requestHash.startsWith("0x") || requestHash.startsWith("0X")) ? requestHash : ("0x" + requestHash);
                    // keccak-256 (existing behavior)
                    String computedKeccak = Numeric.toHexString(Hash.sha3(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    if (computedKeccak.equalsIgnoreCase(rh)) {
                        // ok
                    } else {
                        // also accept SHA-256 formatted as 0x... lowercase hex
                        try {
                            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                            byte[] hashed = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            StringBuilder sb = new StringBuilder();
                            sb.append("0x");
                            for (byte b : hashed) sb.append(String.format("%02x", b));
                            String computedSha256 = sb.toString();
                            if (!computedSha256.equalsIgnoreCase(rh)) return ValidationResult.fail("requestHash mismatch");
                        } catch (Throwable ex) {
                            return ValidationResult.fail("failed to compute requestHash");
                        }
                    }
                } catch (Throwable ignore) {
                    return ValidationResult.fail("failed to compute requestHash");
                }
            }

            // Validate tx fields (basic)
            JSONObject tx = null;
            try { tx = new JSONObject(value); } catch (Throwable e) { return ValidationResult.fail("malformed tx JSON"); }
            String txType = tx.optString("txType", null);
            String sender = tx.optString("sender", null);
            long gasLimit = tx.optLong("gasLimit", -1L);
            long gasPrice = tx.optLong("gasPrice", -1L);
            long nonce = tx.optLong("nonce", -1L);
            if (txType == null || sender == null || gasLimit < 0 || gasPrice < 0 || nonce < 0) return ValidationResult.fail("missing tx fields");

            String normSender = sender.startsWith("0x") ? sender.substring(2).toLowerCase() : sender.toLowerCase();
            if (!normSender.equals(expectedAddr)) return ValidationResult.fail("tx sender does not match client address");

            // Ensure the provided clientAddress (envelope) matches the sender inside the tx value
            String normClientAddr = clientAddress.startsWith("0x") ? clientAddress.substring(2).toLowerCase() : clientAddress.toLowerCase();
            if (!normClientAddr.equals(normSender)) return ValidationResult.fail("clientAddress does not match tx sender");

            final long MAX_TX_GAS = 200_000L;
            if (gasPrice < 1L) return ValidationResult.fail("gasPrice must be >= 1");
            if (gasLimit <= 0L || gasLimit > MAX_TX_GAS) return ValidationResult.fail("gasLimit must be >0 and <= " + MAX_TX_GAS);

            Long expectedNext = clientNonces.getOrDefault(expectedAddr, 0L);
            if (nonce < expectedNext) return ValidationResult.fail("nonce too low");


            return ValidationResult.ok();
        } catch (Throwable t) {
            return ValidationResult.fail("verification error: " + t.getMessage());
        }
    }

    /**
     * Validate a read-only query request: verifies the ECDSA signature over the query string
     * and optionally checks the nonce if present in the query JSON.
     *
     * @param clientEcdsaAddresses  map from client id to normalized (no-0x, lowercase) Ethereum address
     * @param clientEcdsaPublicBytes map from client id to raw 64-byte X||Y ECDSA public key
     * @param clientNonces          map from normalized address to next expected nonce
     * @param clientId              the id of the client sending the request
     * @param signature             the hex-encoded ECDSA signature (r||s||v, 65 bytes)
     * @param queryStr              the JSON-encoded query string that was signed
     * @return true if the signature is valid and nonce is acceptable, false otherwise
     */
    public static boolean validateReadRequest(Map<Integer, String> clientEcdsaAddresses,
                                              Map<Integer, byte[]> clientEcdsaPublicBytes,
                                              Map<String, Long> clientNonces,
                                              int clientId,
                                              String signature,
                                              String queryStr) {
        if (queryStr == null || signature == null || clientId < 0) return false;
        try {
            String expectedAddr = clientEcdsaAddresses.get(clientId);
            if (expectedAddr == null) return false;

            // Parse signature
            String sigHex = signature.startsWith("0x") ? signature.substring(2) : signature;
            byte[] sigBytes = Numeric.hexStringToByteArray(sigHex);
            if (sigBytes == null || sigBytes.length != 65) return false;
            byte[] r = Arrays.copyOfRange(sigBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(sigBytes, 32, 64);

            byte[] h = Hash.sha3(queryStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] xy = clientEcdsaPublicBytes.get(clientId);
            if (xy == null) return false;
            if (xy.length != 64) return false;
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(xy, 0, 32));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(xy, 32, 64));
            ECParameterSpec bcSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            org.bouncycastle.math.ec.ECCurve curve = bcSpec.getCurve();
            ECPoint q = curve.createPoint(x, y);
            ECDomainParameters domain = new ECDomainParameters(curve, bcSpec.getG(), bcSpec.getN(), bcSpec.getH());
            ECPublicKeyParameters pubParams = new ECPublicKeyParameters(q, domain);
            ECDSASigner signer = new ECDSASigner();
            signer.init(false, pubParams);
            BigInteger rBig = new BigInteger(1, r);
            BigInteger sBig = new BigInteger(1, s);
            boolean verified = signer.verifySignature(h, rBig, sBig);
            if (!verified) return false;

            // Optional: check nonce if present in query
            JSONObject query = new JSONObject(queryStr);
            if (query.has("nonce")) {
                long nonce = query.optLong("nonce", -1L);
                if (nonce >= 0) {
                    Long expectedNext = clientNonces.getOrDefault(expectedAddr, 0L);
                    if (nonce < expectedNext) return false;
                }
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
