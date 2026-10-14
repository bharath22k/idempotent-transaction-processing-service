package com.example.transactions.service;

import com.example.transactions.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessorTest {
    private TransactionProcessor processor;
    @BeforeEach void setUp() { processor = new TransactionProcessor(); }

    private TransactionRequest request(String id, String account, TransactionType type, String amount, long seq, boolean retry) {
        return new TransactionRequest(id, "REQ-" + id, "KEY-" + id, account, type,
                new BigDecimal(amount), "INR", seq, Instant.parse("2026-10-14T10:00:00Z"), retry, false);
    }

    @Test void duplicateDoesNotApplyBalanceTwice() {
        TransactionRequest r = request("TX-1", "A1", TransactionType.CREDIT, "100", 1, false);
        List<TransactionRecord> result = processor.submitBatch(List.of(r, r));
        assertEquals(TransactionStatus.PROCESSED, result.get(0).getStatus());
        assertEquals(TransactionStatus.DUPLICATE, result.get(1).getStatus());
        assertEquals(new BigDecimal("100"), processor.balances().get("A1"));
    }

    @Test void outOfOrderRequestWaitsAndThenProcessesInSequence() {
        TransactionRequest second = request("TX-2", "A1", TransactionType.DEBIT, "40", 2, false);
        TransactionRequest first = request("TX-1", "A1", TransactionType.CREDIT, "100", 1, false);
        TransactionRecord pending = processor.submitBatch(List.of(second)).get(0);
        assertEquals(TransactionStatus.PENDING, pending.getStatus());
        processor.submitBatch(List.of(first));
        assertEquals(TransactionStatus.PROCESSED, pending.getStatus());
        assertEquals(new BigDecimal("60"), processor.balances().get("A1"));
    }

    @Test void invalidAmountIsRejectedWithoutStoppingBatch() {
        TransactionRequest invalid = request("TX-BAD", "A1", TransactionType.DEBIT, "-10", 1, false);
        TransactionRequest valid = request("TX-GOOD", "A2", TransactionType.CREDIT, "25", 1, false);
        List<TransactionRecord> results = processor.submitBatch(List.of(invalid, valid));
        assertEquals(TransactionStatus.FAILED, results.get(0).getStatus());
        assertEquals(TransactionStatus.PROCESSED, results.get(1).getStatus());
        assertEquals(new BigDecimal("25"), processor.balances().get("A2"));
    }

    @Test void transientFailureRetriesWithoutDuplicateEffect() {
        TransactionRequest r = request("TX-RETRY", "A1", TransactionType.CREDIT, "500", 1, true);
        TransactionRecord result = processor.submitBatch(List.of(r)).get(0);
        assertEquals(TransactionStatus.PROCESSED, result.getStatus());
        assertEquals(2, result.getAttempts());
        assertEquals(new BigDecimal("500"), processor.balances().get("A1"));
    }

    @Test void retryExhaustionFailsWithoutApplyingBalance() {
        TransactionRequest r = new TransactionRequest("TX-EXHAUST", "REQ-TX-EXHAUST", "KEY-TX-EXHAUST",
                "A1", TransactionType.CREDIT, new BigDecimal("500"), "INR", 1L,
                Instant.parse("2026-10-14T10:00:00Z"), false, true);
        TransactionRecord result = processor.submitBatch(List.of(r)).get(0);
        assertEquals(TransactionStatus.FAILED, result.getStatus());
        assertEquals(2, result.getAttempts());
        assertFalse(processor.balances().containsKey("A1"));
    }

    @Test void summaryIncludesDuplicateOutcomes() {
        TransactionRequest r = request("TX-SUMMARY", "A1", TransactionType.CREDIT, "10", 1, false);
        processor.submitBatch(List.of(r, r));
        assertEquals(1L, processor.summary().get(TransactionStatus.PROCESSED));
        assertEquals(1L, processor.summary().get(TransactionStatus.DUPLICATE));
        assertEquals(2, processor.allRecords().size());
    }

    @Test void conflictingPendingSequenceDoesNotOverwriteOriginal() {
        TransactionRequest firstPending = request("TX-2A", "A1", TransactionType.CREDIT, "20", 2, false);
        TransactionRequest conflict = request("TX-2B", "A1", TransactionType.CREDIT, "30", 2, false);
        TransactionRecord pending = processor.submitBatch(List.of(firstPending)).get(0);
        TransactionRecord rejected = processor.submitBatch(List.of(conflict)).get(0);
        assertEquals(TransactionStatus.PENDING, pending.getStatus());
        assertEquals(TransactionStatus.FAILED, rejected.getStatus());
        processor.submitBatch(List.of(request("TX-1", "A1", TransactionType.CREDIT, "10", 1, false)));
        assertEquals(TransactionStatus.PROCESSED, pending.getStatus());
        assertEquals(new BigDecimal("30"), processor.balances().get("A1"));
    }

    @Test void sameTransactionIdWithDifferentPayloadIsRejected() {
        processor.submitBatch(List.of(request("TX-1", "A1", TransactionType.CREDIT, "100", 1, false)));
        TransactionRecord result = processor.submitBatch(List.of(request("TX-1", "A1", TransactionType.CREDIT, "200", 1, false))).get(0);
        assertEquals(TransactionStatus.FAILED, result.getStatus());
        assertEquals(new BigDecimal("100"), processor.balances().get("A1"));
    }
}
