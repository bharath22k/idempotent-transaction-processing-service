package com.example.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        String transactionId,
        String requestId,
        String idempotencyKey,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        Long sequenceNumber,
        Instant eventTime,
        Boolean simulateTransientFailure,
        Boolean simulatePermanentFailure
) { }
