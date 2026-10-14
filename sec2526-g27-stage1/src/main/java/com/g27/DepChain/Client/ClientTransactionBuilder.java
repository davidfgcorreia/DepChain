package com.g27.DepChain.Client;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.json.JSONObject;

import com.g27.DepChain.smartContact.abi.AbiEncoder;

/**
 * Builds structured DepChain transaction JSON payloads for the client.
 *
 * Every method returns a {@link JSONObject} ready to be placed in the
 * {@code "value"} field of a {@code REQUEST} (state-changing) or
 * {@code "query"} field of a {@code READ} message.
 *
 * Transaction format (state-changing):
 * <pre>
 * {
 *   "txType"   : "CALL" | "TRANSFER",
 *   "sender"   : "0x…",
 *   "to"       : "0x…",
 *   "value"    : 0,
 *   "data"     : "0x…",   // ABI-encoded calldata
 *   "gasLimit" : 200000,
 *   "gasPrice" : 1,
 *   "nonce"    : 0
 * }
 * </pre>
 */
public final class ClientTransactionBuilder {

    private static long DEFAULT_GAS_LIMIT = 200_000L;
    private static long DEFAULT_GAS_PRICE  = 1L;

    private ClientTransactionBuilder() {}

    // ── IST Coin — transfers ──────────────────────────────────────────────────

    /** Build a {@code ISTCoin.transfer(to, amount)} transaction. */
    public static JSONObject istTransfer(Address sender, Address to,
                                         BigInteger amount, long nonce) {
        return call(sender, ClientApp.getIstAddress(),
                AbiEncoder.transfer(to, amount), nonce);
    }

    /** Build a {@code ISTCoin.transferFrom(from, to, amount)} transaction. */
    public static JSONObject istTransferFrom(Address sender, Address from,
                                              Address to, BigInteger amount,
                                              long nonce) {
        return call(sender, ClientApp.getIstAddress(),
                AbiEncoder.transferFrom(from, to, amount), nonce);
    }

    // ── IST Coin — allowance ──────────────────────────────────────────────────

    /**
     * Build a {@code ISTCoin.approve(spender, amount)} transaction.
     * <p>
     * Safety rule (frontrunning guard): only safe when the current allowance
     * for {@code spender} is 0 OR {@code amount} is 0.  Use
     * {@link #istIncreaseAllowance} / {@link #istDecreaseAllowance} otherwise.
     */
    public static JSONObject istApprove(Address sender, Address spender,
                                         BigInteger amount, long nonce) {
        return call(sender, ClientApp.getIstAddress(),
                AbiEncoder.approve(spender, amount), nonce);
    }

    /** Build a {@code ISTCoin.increaseAllowance(spender, addedValue)} transaction. */
    public static JSONObject istIncreaseAllowance(Address sender, Address spender,
                                                   BigInteger addedValue, long nonce) {
        return call(sender, ClientApp.getIstAddress(),
                AbiEncoder.increaseAllowance(spender, addedValue), nonce);
    }

    /** Build a {@code ISTCoin.decreaseAllowance(spender, subtractedValue)} transaction. */
    public static JSONObject istDecreaseAllowance(Address sender, Address spender,
                                                   BigInteger subtractedValue, long nonce) {
        return call(sender, ClientApp.getIstAddress(),
                AbiEncoder.decreaseAllowance(spender, subtractedValue), nonce);
    }

    // ── IST Coin — read queries ───────────────────────────────────────────────

    /** Build a {@code ISTCoin.balanceOf(account)} READ query. */
    public static JSONObject istBalanceOf(Address caller, Address account) {
        return read(caller, ClientApp.getIstAddress(),
                AbiEncoder.balanceOf(account));
    }

    /** Build a {@code ISTCoin.allowance(owner, spender)} READ query. */
    public static JSONObject istAllowance(Address caller, Address owner, Address spender) {
        return read(caller, ClientApp.getIstAddress(),
                AbiEncoder.allowance(owner, spender));
    }

    /** Build a {@code ISTCoin.totalSupply()} READ query. */
    public static JSONObject istTotalSupply(Address caller) {
        return read(caller, ClientApp.getIstAddress(),
                AbiEncoder.totalSupply());
    }


    // ── Native DepCoin transfer ───────────────────────────────────────────────

    /**
     * Build a native DepCoin (Wei) transfer transaction.
     * Intrinsic cost is 21,000 gas; pass at least that as {@code gasLimit}.
     */
    public static JSONObject depCoinTransfer(Address sender, Address to,
                                              long weiAmount, long nonce) {
        JSONObject tx = baseTx("TRANSFER", sender, nonce);
        tx.put("to",       to.toHexString());
        tx.put("value",    weiAmount);
        tx.put("data",     "0x");
        tx.put("gasLimit", 21_000L);
        tx.put("gasPrice", DEFAULT_GAS_PRICE);
        return tx;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Wrap a contract call in a REQUEST-payload JSON. */
    private static JSONObject call(Address sender, Address contract,
                                   Bytes calldata, long nonce) {
        if (contract == null) throw new IllegalStateException("IST contract address unknown; ensure client derived it from config/public_keys.json");
        JSONObject tx = baseTx("CALL", sender, nonce);
        tx.put("to",   contract.toHexString());
        tx.put("data", calldata.toHexString());
        tx.put("gasLimit", DEFAULT_GAS_LIMIT);
        tx.put("gasPrice", DEFAULT_GAS_PRICE);
        return tx;
    }

    /** Wrap a read-only EVM call in a READ-query JSON. */
    private static JSONObject read(Address caller, Address contract, Bytes calldata) {
        if (contract == null) throw new IllegalStateException("IST contract address unknown; ensure client derived it from config/public_keys.json");
        JSONObject q = new JSONObject();
        q.put("sender", caller.toHexString());
        q.put("to",     contract.toHexString());
        q.put("data",   calldata.toHexString());
        // Note: nonce not included for reads to allow multiple queries without incrementing
        return q;
    }

    private static JSONObject baseTx(String txType, Address sender, long nonce) {
        JSONObject tx = new JSONObject();
        tx.put("txType",   txType);
        tx.put("sender",   sender.toHexString());
        tx.put("value",    0L);
        tx.put("gasLimit", DEFAULT_GAS_LIMIT);
        tx.put("gasPrice", DEFAULT_GAS_PRICE);
        tx.put("nonce",    nonce);
        return tx;
    }

    public static synchronized void setDefaultGasLimit(long gasLimit) {
        if (gasLimit <= 0) throw new IllegalArgumentException("gasLimit must be > 0");
        DEFAULT_GAS_LIMIT = gasLimit;
    }

    public static synchronized void setDefaultGasPrice(long gasPrice) {
        if (gasPrice < 0) throw new IllegalArgumentException("gasPrice must be >= 0");
        DEFAULT_GAS_PRICE = gasPrice;
    }

    public static synchronized long getDefaultGasLimit() { return DEFAULT_GAS_LIMIT; }

    public static synchronized long getDefaultGasPrice() { return DEFAULT_GAS_PRICE; }
}
