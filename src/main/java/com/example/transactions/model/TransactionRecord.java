package com.example.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;

public class TransactionRecord {
    private final TransactionRequest request;
    private TransactionStatus status;
    private String message;
    private int attempts;
    private final Instant receivedAt = Instant.now();

    public TransactionRecord(TransactionRequest request, TransactionStatus status, String message) {
        this.request = request; this.status = status; this.message = message;
    }
    public TransactionRequest getRequest() { return request; }
    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public int getAttempts() { return attempts; }
    public void incrementAttempts() { attempts++; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getTransactionId() { return request == null ? null : request.transactionId(); }
    public String getAccountId() { return request == null ? null : request.accountId(); }
    public BigDecimal getAmount() { return request == null ? null : request.amount(); }
}
