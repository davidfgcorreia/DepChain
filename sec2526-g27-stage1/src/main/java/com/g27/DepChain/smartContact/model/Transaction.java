package com.g27.DepChain.smartContact.model;

import org.json.JSONObject;

public class Transaction {
    private final String from;
    private final String to;
    private final long value;
    private final long gasLimit;
    private long gasUsed;

    public Transaction(String from, String to, long value, long gasLimit, long gasUsed) {
        this.from = from;
        this.to = to;
        this.value = value;
        this.gasLimit = gasLimit;
        this.gasUsed = gasUsed;
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public long getValue() { return value; }
    public long getGasLimit() { return gasLimit; }
    public long getGasUsed() { return gasUsed; }
    public void setGasUsed(long g) { this.gasUsed = g; }

    public String toJson() {
        return new JSONObject(this).toString();
    }

    public static long estimateGas(byte[] data) {
        int dataLen = data == null ? 0 : data.length;
        return 21000L + dataLen; // simple estimator for now
    }
}
