package com.g27.DepChain.smartContact.abi;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;

/**
 * Minimal ABI encoder for the types used by ISTCoin.
 *
 * Supported Solidity types:
 *   address  → 32-byte left-zero-padded
 *   uint256  → 32-byte big-endian
 *   bool     → 32-byte (0 or 1)
 *
 * Every public method returns the full calldata:
 *   calldata = selector (4 bytes) + encoded args (N × 32 bytes)
 *
 * Reference: https://docs.soliditylang.org/en/v0.8.26/abi-spec.html
 */
public final class AbiEncoder {

    private AbiEncoder() {}


    // ── ISTCoin calls ─────────────────────────────────────────────────────────

    /** transfer(address to, uint256 amount) */
    public static Bytes transfer(Address to, BigInteger amount) {
        return encode(FunctionSelector.TRANSFER, encodeAddress(to), encodeUint256(amount));
    }

    /** transferFrom(address from, address to, uint256 amount) */
    public static Bytes transferFrom(Address from, Address to, BigInteger amount) {
        return encode(FunctionSelector.TRANSFER_FROM,
                encodeAddress(from), encodeAddress(to), encodeUint256(amount));
    }

    /**
     * approve(address spender, uint256 amount)
     * Only safe when current allowance == 0 OR amount == 0 (enforced by contract).
     */
    public static Bytes approve(Address spender, BigInteger amount) {
        return encode(FunctionSelector.APPROVE, encodeAddress(spender), encodeUint256(amount));
    }

    /** increaseAllowance(address spender, uint256 addedValue) */
    public static Bytes increaseAllowance(Address spender, BigInteger addedValue) {
        return encode(FunctionSelector.INCREASE_ALLOWANCE,
                encodeAddress(spender), encodeUint256(addedValue));
    }

    /** decreaseAllowance(address spender, uint256 subtractedValue) */
    public static Bytes decreaseAllowance(Address spender, BigInteger subtractedValue) {
        return encode(FunctionSelector.DECREASE_ALLOWANCE,
                encodeAddress(spender), encodeUint256(subtractedValue));
    }

    /** balanceOf(address account) */
    public static Bytes balanceOf(Address account) {
        return encode(FunctionSelector.BALANCE_OF, encodeAddress(account));
    }

    /** allowance(address owner, address spender) */
    public static Bytes allowance(Address owner, Address spender) {
        return encode(FunctionSelector.ALLOWANCE, encodeAddress(owner), encodeAddress(spender));
    }

    /** totalSupply() */
    public static Bytes totalSupply() {
        return FunctionSelector.TOTAL_SUPPLY;   // no args
    }

    // ── ABI decode helpers ────────────────────────────────────────────────────

    /**
     * Decode a single uint256 return value from EVM output data.
     * The EVM always returns 32-byte words.
     */
    public static BigInteger decodeUint256(Bytes returnData) {
        if (returnData.size() < 32) {
            throw new IllegalArgumentException("Return data too short for uint256: " + returnData.size());
        }
        return new BigInteger(1, returnData.slice(0, 32).toArrayUnsafe());
    }

    /**
     * Decode a single bool return value from EVM output data.
     */
    public static boolean decodeBool(Bytes returnData) {
        if (returnData.size() < 32) {
            throw new IllegalArgumentException("Return data too short for bool: " + returnData.size());
        }
        return !returnData.slice(0, 32).isZero();
    }

    /**
     * Decode a single address return value from EVM output data.
     */
    public static Address decodeAddress(Bytes returnData) {
        if (returnData.size() < 32) {
            throw new IllegalArgumentException("Return data too short for address: " + returnData.size());
        }
        // address occupies the last 20 bytes of a 32-byte word
        return Address.wrap(returnData.slice(12, 20));
    }

    // ── Private encoding helpers ──────────────────────────────────────────────

    /** Concatenate selector + zero or more 32-byte encoded arguments. */
    private static Bytes encode(Bytes selector, Bytes... args) {
        Bytes[] parts = new Bytes[1 + args.length];
        parts[0] = selector;
        System.arraycopy(args, 0, parts, 1, args.length);
        return Bytes.concatenate(parts);
    }

    /** ABI-encode a Solidity {@code address}: left-zero-pad to 32 bytes. */
    private static Bytes encodeAddress(Address address) {
        byte[] padded = new byte[32];
        byte[] addrBytes = address.toArrayUnsafe();   // always 20 bytes
        System.arraycopy(addrBytes, 0, padded, 12, 20);
        return Bytes.wrap(padded);
    }

    /** ABI-encode a Solidity {@code uint256}: big-endian, 32 bytes. */
    private static Bytes encodeUint256(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("uint256 cannot be negative");
        }
        byte[] raw     = value.toByteArray();
        byte[] padded  = new byte[32];
        // BigInteger.toByteArray() may prepend a 0x00 sign byte — strip it
        int srcOffset  = (raw[0] == 0) ? 1 : 0;
        int length     = raw.length - srcOffset;
        if (length > 32) {
            throw new IllegalArgumentException("Value too large for uint256");
        }
        System.arraycopy(raw, srcOffset, padded, 32 - length, length);
        return Bytes.wrap(padded);
    }
}
