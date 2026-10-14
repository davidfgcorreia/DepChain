package com.g27.DepChain.Chain;

import org.json.JSONObject;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import com.g27.DepChain.smartContact.runtime.DepTransaction;
import com.g27.DepChain.smartContact.runtime.EvmExecutor;
import com.g27.DepChain.smartContact.util.PublicKeyUtils;

/**
 * Utility class for executing EVM transactions and read-only calls on behalf of the consensus layer.
 * Handles both state-changing transactions (CALL, TRANSFER) and read-only queries,
 * and credits gas fees to the current leader's account after execution.
 */
public class Execution {

  /**
   * Execute a transaction on the EVM. The tx JSON may contain:
   * `txType` ("CALL" or "TRANSFER"), `sender`, `to`, `gasLimit`, `gasPrice`, `nonce`, `value`, `data`.
   *
   * @param evmExecutor the shared EVM executor holding world state
   * @param tx          the transaction descriptor as a JSON object
   * @param leader      the Ethereum address of the current leader, credited with gas fees;
   *                    may be null to skip fee crediting
   * @return a JSON object containing: `success` (boolean), `gasUsed` (long),
   *         `output` (hex string), and optionally `revertReason` or `error`
   */
  public static JSONObject executeEvmTransaction(EvmExecutor evmExecutor, JSONObject tx, Address leader) {
    JSONObject result = new JSONObject();
    result.put("success", false);
    result.put("gasUsed", 0L);
    result.put("output", "0x");

    try {
      String txTypeStr = tx.optString("txType", "CALL");
      Address sender   = Address.fromHexString(tx.getString("sender"));
      long gasLimit    = tx.optLong("gasLimit", 200_000L);
      long gasPrice    = tx.optLong("gasPrice", 1L);
      long nonce       = tx.optLong("nonce", 0L);
      long value       = tx.optLong("value", 0L);
      String dataHex   = tx.optString("data", "0x");
      Bytes data       = (dataHex.isEmpty() || dataHex.equals("0x"))
                         ? Bytes.EMPTY
                         : Bytes.fromHexString(dataHex);



      EvmExecutor.ExecutionResult execResult;
      DepTransaction depTx = null;

      if ("TRANSFER".equals(txTypeStr)) {
        Address to = Address.fromHexString(tx.getString("to"));
        depTx = DepTransaction.builder()
            .sender(sender).to(to).value(Wei.of(value))
            .data(Bytes.EMPTY).gasLimit(gasLimit)
            .gasPrice(Wei.of(gasPrice)).nonce(nonce)
            .build();
        execResult = evmExecutor.transfer(depTx);

      } else { // CALL (default)
        Address to = Address.fromHexString(tx.getString("to"));
        depTx = DepTransaction.builder()
            .sender(sender).to(to).value(Wei.of(value))
            .data(data).gasLimit(gasLimit)
            .gasPrice(Wei.of(gasPrice)).nonce(nonce)
            .build();
        execResult = evmExecutor.callContract(depTx);
      }

      try {
        if (leader != null && depTx != null && execResult != null) {
          Wei actualFee = depTx.actualFee(execResult.gasUsed());
          // ensure leader account exists (create with zero balance if missing)
          if (evmExecutor.world().getAccount(leader) == null) {
            try { evmExecutor.world().createAccount(leader, 0, Wei.ZERO); } catch (Throwable ignore) {}
          }
          org.hyperledger.besu.evm.account.MutableAccount lac = evmExecutor.world().getAccount(leader);
          if (lac != null) {
            java.math.BigInteger curr = lac.getBalance().toBigInteger();
            java.math.BigInteger add = actualFee.toBigInteger();
            java.math.BigInteger summed = curr.add(add);
            lac.setBalance(Wei.of(summed));
          }
        }
      } catch (Throwable ignore) {}

      result.put("success", execResult.success());
      result.put("gasUsed", execResult.gasUsed());
      result.put("output", execResult.output().toHexString());
      execResult.revertReason()
          .ifPresent(r -> result.put("revertReason", r.toHexString()));

    } catch (Exception e) {
      result.put("error", e.getMessage());
    }
    return result;
  }

  /**
   * Execute a read-only EVM call (no state changes, no fee crediting).
   * Uses client id 4 as the default sender if none is provided.
   * The tx JSON may contain: `to` (required), `sender` (optional), `data` (optional).
   *
   * @param evmExecutor the shared EVM executor holding world state
   * @param tx          the call descriptor as a JSON object
   * @return a JSON object containing: `success` (boolean), `output` (hex string),
   *         and optionally `revertReason` or `error`
   */
  public static JSONObject executeRead(EvmExecutor evmExecutor, JSONObject tx) {
    JSONObject result = new JSONObject();
    result.put("success", false);
    result.put("output", "0x");
    try {
    Address defaultSender = PublicKeyUtils.deriveAddressFromPublicKeys(4);
    String defaultSenderStr = (defaultSender == null) ? Address.ZERO.toHexString() : defaultSender.toHexString();
    Address sender  = Address.fromHexString(tx.optString("sender", defaultSenderStr));
      Address to      = Address.fromHexString(tx.getString("to"));
      String dataHex  = tx.optString("data", "0x");
      Bytes data      = (dataHex.isEmpty() || dataHex.equals("0x"))
                        ? Bytes.EMPTY
                        : Bytes.fromHexString(dataHex);

      DepTransaction depTx = DepTransaction.builder()
          .sender(sender).to(to).value(Wei.ZERO)
          .data(data).gasLimit(200_000L).gasPrice(Wei.of(1L)).nonce(0L)
          .build();
      EvmExecutor.ExecutionResult execResult = evmExecutor.callContract(depTx);

      result.put("success", execResult.success());
      result.put("output", execResult.output().toHexString());
      execResult.revertReason()
          .ifPresent(r -> result.put("revertReason", r.toHexString()));

    } catch (Exception e) {
      result.put("error", e.getMessage());
    }
    return result;
  }

}
