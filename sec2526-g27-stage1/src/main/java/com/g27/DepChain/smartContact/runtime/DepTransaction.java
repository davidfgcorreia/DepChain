package com.g27.DepChain.smartContact.runtime;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Optional;

/**
 * Represents a DepChain transaction.
 *
 * A transaction can be:
 *  - A native DepCoin transfer        (to != null, data == empty)
 *  - A smart contract call            (to != null, data == ABI-encoded call)
 *  - A contract deployment            (to == null, data == EVM init bytecode)
 *
 * Gas fee (in DepCoin / Wei) = min(gasPrice * gasLimit, gasPrice * gasUsed)
 * If gasUsed > gasLimit the transaction is aborted; gas is NOT refunded.
 */
public class DepTransaction {

    // ── Sender ───────────────────────────────────────────────────────────────
    private final Address sender;

    // ── Recipient (empty for contract deployment) ────────────────────────────
    private final Optional<Address> to;

    // ── Native-coin value transferred (Wei denomination) ─────────────────────
    private final Wei value;

    // ── EVM input data (ABI call or init bytecode) ───────────────────────────
    private final Bytes data;

    // ── Gas parameters ───────────────────────────────────────────────────────
    /** Maximum gas units the sender is willing to spend. Must be > 0. */
    private final long gasLimit;

    /** Price in Wei per gas unit. Must be > 0. */
    private final Wei gasPrice;

    // ── Replay-protection / ordering ─────────────────────────────────────────
    private final long nonce;

    private final byte[] signature;

    private DepTransaction(Builder b) {
        this.sender    = b.sender;
        this.to        = b.to;
        this.value     = b.value;
        this.data      = b.data;
        this.gasLimit  = b.gasLimit;
        this.gasPrice  = b.gasPrice;
        this.nonce     = b.nonce;
        this.signature = b.signature;
    }

    public Address getSender()             { return sender; }
    public Optional<Address> getTo()       { return to; }
    public Wei getValue()                  { return value; }
    public Bytes getData()                 { return data; }
    public long getGasLimit()              { return gasLimit; }
    public Wei getGasPrice()               { return gasPrice; }
    public long getNonce()                 { return nonce; }
    public byte[] getSignature()           { return signature; }

    public boolean isContractCreation()    { return to.isEmpty(); }

    public Wei maxFee() {
        return Wei.of(gasPrice.toLong() * gasLimit);
    }

    public Wei actualFee(long gasUsed) {
        long capped = Math.min(gasLimit, gasUsed);
        return Wei.of(gasPrice.toLong() * capped);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Address          sender;
        private Optional<Address> to   = Optional.empty();
        private Wei              value = Wei.ZERO;
        private Bytes            data  = Bytes.EMPTY;
        private long             gasLimit;
        private Wei              gasPrice;
        private long             nonce;
        private byte[]           signature = new byte[0];

        public Builder sender(Address sender)           { this.sender = sender;   return this; }
        public Builder to(Address to)                   { this.to = Optional.of(to); return this; }
        public Builder value(Wei value)                 { this.value = value;     return this; }
        public Builder data(Bytes data)                 { this.data = data;       return this; }
        public Builder gasLimit(long gasLimit)          { this.gasLimit = gasLimit; return this; }
        public Builder gasPrice(Wei gasPrice)           { this.gasPrice = gasPrice; return this; }
        public Builder nonce(long nonce)                { this.nonce = nonce;     return this; }
        public Builder signature(byte[] sig)            { this.signature = sig;   return this; }

        public DepTransaction build() {
            if (sender   == null) throw new IllegalStateException("sender required");
            if (gasPrice == null) throw new IllegalStateException("gasPrice required");
            if (gasLimit <= 0)    throw new IllegalStateException("gasLimit must be > 0");
            if (gasPrice.isZero()) throw new IllegalStateException("gasPrice must be > 0");
            return new DepTransaction(this);
        }
    }

    @Override
    public String toString() {
        return "DepTransaction{" +
               "sender=" + sender +
               ", to=" + to.map(Address::toString).orElse("CONTRACT_CREATION") +
               ", value=" + value +
               ", gasLimit=" + gasLimit +
               ", gasPrice=" + gasPrice +
               ", nonce=" + nonce +
               ", dataLen=" + data.size() +
               '}';
    }
}
