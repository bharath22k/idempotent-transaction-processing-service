package com.example.transactions.service;

import com.example.transactions.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TransactionProcessor {
    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);
    private static final int MAX_ATTEMPTS = 2;
    private final Map<String, TransactionRecord> records = new LinkedHashMap<>();
    // Every submitted outcome is retained for reporting, including duplicates and invalid requests.
    private final List<TransactionRecord> outcomes = new ArrayList<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, TreeMap<Long, TransactionRecord>> pendingByAccount = new HashMap<>();
    private final Map<String, Long> nextSequenceByAccount = new HashMap<>();
    private final Set<String> transientFailureAlreadyTriggered = new HashSet<>();

    /** Synchronized to make this in-memory demo's state changes atomic within one JVM. */
    public synchronized List<TransactionRecord> submitBatch(List<TransactionRequest> requests) {
        List<TransactionRecord> response = new ArrayList<>();
        for (TransactionRequest request : requests) {
            TransactionRecord outcome = submitOne(request);
            outcomes.add(outcome);
            response.add(outcome);
        }
        return response;
    }

    private TransactionRecord submitOne(TransactionRequest request) {
        String validationError = validate(request);
        if (validationError != null) {
            TransactionRecord invalid = new TransactionRecord(request, TransactionStatus.FAILED, validationError);
            log.warn("transaction rejected: id={}, reason={}", safeId(request), validationError);
            return invalid;
        }

        TransactionRecord existing = records.get(request.transactionId());
        if (existing != null) {
            if (!sameBusinessRequest(existing.getRequest(), request)) {
                return new TransactionRecord(request, TransactionStatus.FAILED,
                        "transactionId already exists with a different payload");
            }
            TransactionRecord duplicate = new TransactionRecord(request, TransactionStatus.DUPLICATE,
                    "Already accepted; no additional balance update performed");
            log.info("duplicate detected: transactionId={}", request.transactionId());
            return duplicate;
        }

        TransactionRecord record = new TransactionRecord(request, TransactionStatus.RECEIVED, "Accepted");
        log.info("transaction received: transactionId={}, accountId={}, sequence={}",
                request.transactionId(), request.accountId(), request.sequenceNumber());

        long expected = nextSequenceByAccount.computeIfAbsent(request.accountId(), ignored -> 1L);
        if (request.sequenceNumber() < expected) {
            record.setStatus(TransactionStatus.FAILED);
            record.setMessage("Stale sequence number; expected sequence " + expected);
            return record;
        }
        if (request.sequenceNumber() > expected) {
            record.setStatus(TransactionStatus.PENDING);
            record.setMessage("Waiting for sequence " + expected);
            TreeMap<Long, TransactionRecord> pending = pendingByAccount.computeIfAbsent(request.accountId(), ignored -> new TreeMap<>());
            TransactionRecord sequenceConflict = pending.get(request.sequenceNumber());
            if (sequenceConflict != null) {
                record.setStatus(TransactionStatus.FAILED);
                record.setMessage("Another transaction already uses this account sequence number");
                log.warn("sequence conflict: transactionId={}, sequence={}", request.transactionId(), request.sequenceNumber());
                return record;
            }
            records.put(request.transactionId(), record);
            pending.put(request.sequenceNumber(), record);
            log.info("transaction pending for missing sequence: transactionId={}, expected={}", request.transactionId(), expected);
            return record;
        }

        records.put(request.transactionId(), record);
        processInSequence(record);
        drainPending(request.accountId());
        return record;
    }

    private void processInSequence(TransactionRecord record) {
        TransactionRequest request = record.getRequest();
        record.setStatus(TransactionStatus.PROCESSING);
        while (true) {
            record.incrementAttempts();
            if (Boolean.TRUE.equals(request.simulatePermanentFailure())) {
                record.setStatus(TransactionStatus.RETRY_PENDING);
                record.setMessage("Simulated transient failure; retrying");
                log.warn("simulated failure: transactionId={}, attempt={}", request.transactionId(), record.getAttempts());
                if (record.getAttempts() < MAX_ATTEMPTS) continue;
                record.setStatus(TransactionStatus.FAILED);
                record.setMessage("Retry limit exhausted; no balance update performed");
                log.error("retry limit exhausted: transactionId={}, attempts={}", request.transactionId(), record.getAttempts());
                break;
            }
            if (Boolean.TRUE.equals(request.simulateTransientFailure())
                    && record.getAttempts() == 1
                    && transientFailureAlreadyTriggered.add(request.transactionId())) {
                record.setStatus(TransactionStatus.RETRY_PENDING);
                record.setMessage("Simulated transient failure; retrying once");
                log.warn("transient failure: transactionId={}, attempt={}", request.transactionId(), record.getAttempts());
                if (record.getAttempts() < MAX_ATTEMPTS) continue;
            }
            try {
                applyBalance(request);
                record.setStatus(TransactionStatus.PROCESSED);
                record.setMessage("Processed successfully");
                log.info("transaction processed: transactionId={}, attempts={}", request.transactionId(), record.getAttempts());
            } catch (RuntimeException ex) {
                record.setStatus(TransactionStatus.FAILED);
                record.setMessage("Processing failed safely");
                log.error("transaction failed: transactionId={}", request.transactionId());
            }
            break;
        }
        nextSequenceByAccount.put(request.accountId(), request.sequenceNumber() + 1);
    }

    private void drainPending(String accountId) {
        TreeMap<Long, TransactionRecord> pending = pendingByAccount.get(accountId);
        while (pending != null) {
            long expected = nextSequenceByAccount.getOrDefault(accountId, 1L);
            TransactionRecord next = pending.remove(expected);
            if (next == null) break;
            processInSequence(next);
            if (pending.isEmpty()) { pendingByAccount.remove(accountId); break; }
        }
    }

    private void applyBalance(TransactionRequest request) {
        BigDecimal signedAmount = request.amount();
        if (request.transactionType() == TransactionType.DEBIT) signedAmount = signedAmount.negate();
        // REVERSAL is modelled as a positive compensating credit in this demo; production needs a reference to the original transaction.
        balances.merge(request.accountId(), signedAmount, BigDecimal::add);
    }

    private String validate(TransactionRequest r) {
        if (r == null) return "Request must not be null";
        if (blank(r.transactionId()) || blank(r.requestId()) || blank(r.idempotencyKey()) || blank(r.accountId())) return "transactionId, requestId, idempotencyKey and accountId are required";
        if (r.transactionType() == null) return "transactionType is required";
        if (r.amount() == null || r.amount().compareTo(BigDecimal.ZERO) <= 0) return "amount must be greater than zero";
        if (blank(r.currency()) || !r.currency().matches("[A-Z]{3}")) return "currency must be a 3-letter uppercase code";
        if (r.sequenceNumber() == null || r.sequenceNumber() < 1) return "sequenceNumber must be at least 1";
        if (r.eventTime() == null) return "eventTime is required";
        return null;
    }
    private boolean sameBusinessRequest(TransactionRequest a, TransactionRequest b) {
        return Objects.equals(a.accountId(), b.accountId()) && a.transactionType() == b.transactionType()
                && Objects.equals(a.amount(), b.amount()) && Objects.equals(a.currency(), b.currency())
                && Objects.equals(a.sequenceNumber(), b.sequenceNumber())
                && Objects.equals(a.idempotencyKey(), b.idempotencyKey());
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safeId(TransactionRequest r) { return r == null ? "unknown" : String.valueOf(r.transactionId()); }

    public synchronized List<TransactionRecord> allRecords() { return new ArrayList<>(outcomes); }
    public synchronized Map<String, BigDecimal> balances() { return new TreeMap<>(balances); }
    public synchronized Map<TransactionStatus, Long> summary() {
        return outcomes.stream().collect(Collectors.groupingBy(TransactionRecord::getStatus,
                () -> new EnumMap<>(TransactionStatus.class), Collectors.counting()));
    }
}
