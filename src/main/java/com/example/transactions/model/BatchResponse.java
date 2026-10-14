package com.example.transactions.model;

import java.util.List;
import java.util.Map;

public record BatchResponse(List<TransactionRecord> results, Map<TransactionStatus, Long> summary) { }
