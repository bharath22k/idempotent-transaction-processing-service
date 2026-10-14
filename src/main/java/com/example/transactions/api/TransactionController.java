package com.example.transactions.api;

import com.example.transactions.model.*;
import com.example.transactions.service.TransactionProcessor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {
    private final TransactionProcessor processor;
    public TransactionController(TransactionProcessor processor) { this.processor = processor; }

    @PostMapping("/transactions/batch")
    public BatchResponse submitBatch(@RequestBody List<TransactionRequest> requests) {
        if (requests == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must be a JSON array");
        List<TransactionRecord> results = processor.submitBatch(requests);
        return new BatchResponse(results, processor.summary());
    }
    @GetMapping("/transactions") public List<TransactionRecord> allTransactions() { return processor.allRecords(); }
    @GetMapping("/summary") public Map<TransactionStatus, Long> summary() { return processor.summary(); }
    @GetMapping("/balances") public Map<String, BigDecimal> balances() { return processor.balances(); }
}
