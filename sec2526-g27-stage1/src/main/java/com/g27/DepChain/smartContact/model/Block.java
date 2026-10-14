package com.g27.DepChain.smartContact.model;

import java.util.List;
import org.json.JSONObject;

public class Block {
    private final long number;
    private final String parentHash;
    private final List<Transaction> transactions;
    private final String blockHash;

    public Block(long number, String parentHash, List<Transaction> transactions, String blockHash) {
        this.number = number;
        this.parentHash = parentHash;
        this.transactions = transactions;
        this.blockHash = blockHash;
    }

    public long getNumber() { return number; }
    public String getParentHash() { return parentHash; }
    public List<Transaction> getTransactions() { return transactions; }
    public String getBlockHash() { return blockHash; }

    public String toJson() {
        return new JSONObject(this).toString();
    }
}
