# Idempotent Background Transaction Processing Service

A small Java 17 + Spring Boot service for the FDE practical assessment. It demonstrates idempotent transaction handling, duplicate detection, sequence-based ordering per account, simulated transient retries, validation, status reporting, and unit tests.

## Requirements

- JDK 17 or newer
- Maven 3.8+

## Run

```bash
mvn test
mvn spring-boot:run
```

The API starts at `http://localhost:8080`.

## Endpoints

- `POST /api/transactions/batch` — submit a JSON array of transactions.
- `GET /api/transactions` — inspect accepted transaction records.
- `GET /api/summary` — counts by status.
- `GET /api/balances` — simulated account balances.

Example request:

```bash
curl -X POST http://localhost:8080/api/transactions/batch \
  -H 'Content-Type: application/json' \
  --data-binary @sample-data/transactions.json
```

`sample-data/transactions.json` intentionally includes a sequence-2 transaction before sequence 1, a duplicate, an invalid negative amount, and a transaction that simulates one transient failure.

## Design note

### Architecture

`TransactionController` handles HTTP input/output. `TransactionProcessor` owns validation, idempotency, ordering, retry behavior, balance changes, and summary reporting. The model classes represent requests, records, statuses, and batch results. The processor is designed so an input adapter (queue, scheduler, file, or API) can be replaced without changing the core processing rules.

### Idempotency and duplicate detection

The in-memory record store is keyed by `transactionId`. If an identical business request arrives again, the service returns `DUPLICATE` and does not apply another balance change. If the same ID arrives with a different business payload, it is rejected as `FAILED`. This avoids silently treating a conflicting request as a valid retry.

### Out-of-order strategy

The service uses sequence-based processing per account, with the next expected sequence starting at 1. Future sequences are held in `PENDING`; when the missing sequence arrives, it is processed and contiguous pending records are drained in sequence order. This is suitable for the demo's explicit sequence-number contract. In production, sequence initialization, missing-sequence timeouts, and recovery/replay policies must be defined with the upstream system.

### Retry and failure handling

Set `simulateTransientFailure` to `true` to simulate a one-time transient failure that succeeds on the second attempt. Set `simulatePermanentFailure` to `true` to simulate repeated transient failures; the processor stops after the configured maximum of two attempts, marks the record `FAILED`, and does not apply a balance effect. The permanent-failure flag exists only as a deterministic test hook, not as a production API contract.

### Consistency and observability

The demo synchronizes state-changing operations inside one JVM and updates the in-memory balance only after validation and retry simulation. Logs include transaction IDs, status events, sequence waits, and attempt counts; they intentionally avoid logging account balances or full request payloads. `/api/summary` exposes counts by status across submitted outcomes, including duplicates and invalid requests. Pending records are retained by reference, so their counts reflect their latest status after they are eventually processed.

### Assumptions and trade-offs

- The demo starts each account's sequence at 1. A real system needs a durable sequence checkpoint or a defined bootstrap rule.
- `REVERSAL` is represented as a positive compensating credit in this sample. A production reversal must reference and validate the original transaction.
- In-memory storage loses state on restart and is not shared across instances. `synchronized` protects only this JVM; it is not a distributed lock.
- The demo's request-level records and balance changes are not a database transaction. Production implementation should persist the idempotency key, transaction state, ledger entry, and balance mutation atomically, with unique constraints and appropriate locking/optimistic concurrency.
- For external side effects, use a transactional outbox or an idempotent downstream consumer. For queue ingestion, acknowledge only after durable processing or durable handoff to retry storage.
- Add authentication/authorization, rate limits, durable retry scheduling, dead-letter handling, metrics/tracing, schema versioning, and load/concurrency tests before production use.

### AI-assisted development

AI assistance was used to accelerate project scaffolding, test-case drafting, and documentation. The implementation should be reviewed against the requirements and tests run locally before submission. The candidate should be prepared to explain the sequence strategy, duplicate semantics, retry boundary, in-memory limitations, and production hardening choices.

## Tests

The JUnit suite covers:

1. Duplicate request does not double-apply a balance effect.
2. Out-of-order sequence is held pending and processed after the gap is filled.
3. Invalid amount is isolated and does not stop the batch.
4. Simulated transient failure retries without duplicate balance effect.
5. Reusing a transaction ID with a conflicting payload is rejected.
6. Retry exhaustion marks a record failed without applying a balance effect.
7. Summary includes duplicate outcomes.
8. A conflicting pending sequence does not overwrite the original pending transaction.

## Continuous integration

The GitHub Actions workflow at `.github/workflows/maven.yml` runs `mvn clean verify` on pushes and pull requests using Java 17. Check the Actions tab after pushing to confirm the full Maven/JUnit build passes.

## Local verification note

Before submission, run `mvn clean verify` on a machine with Maven and dependency-repository access. The assessment environment used to prepare this project did not have Maven installed and could not resolve Maven Central, so the full Spring Boot/JUnit build could not be executed there. The transaction processor and model classes were compiled with Java 21 against minimal framework logging/annotation stubs, and a separate runtime harness passed checks for duplicate idempotency, duplicate summary counts, sequence ordering, retry exhaustion, and sequence-conflict protection. This is not a substitute for the full Maven test run.
