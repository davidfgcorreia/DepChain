package com.g27.DepChain.smartContact.abi;


import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.crypto.Hash;

import java.nio.charset.StandardCharsets;

public final class FunctionSelector {

    private FunctionSelector() {}

    // -- ISTCoin --
    public static final Bytes TRANSFER           = of("transfer(address,uint256)");
    public static final Bytes TRANSFER_FROM      = of("transferFrom(address,address,uint256)");
    public static final Bytes APPROVE            = of("approve(address,uint256)");
    public static final Bytes INCREASE_ALLOWANCE = of("increaseAllowance(address,uint256)");
    public static final Bytes DECREASE_ALLOWANCE = of("decreaseAllowance(address,uint256)");
    public static final Bytes BALANCE_OF         = of("balanceOf(address)");
    public static final Bytes ALLOWANCE          = of("allowance(address,address)");
    public static final Bytes TOTAL_SUPPLY       = of("totalSupply()");
    public static final Bytes NAME               = of("name()");
    public static final Bytes SYMBOL             = of("symbol()");
    public static final Bytes DECIMALS           = of("decimals()");

    /**
     * Compute the 4-byte selector for a canonical Solidity function signature.
     * selector = keccak256(signature)[0..3]
     */
    public static Bytes of(String signature) {
        byte[] input = signature.getBytes(StandardCharsets.UTF_8);
        Bytes  hash  = Hash.keccak256(Bytes.wrap(input));
        return hash.slice(0, 4);
    }
}
