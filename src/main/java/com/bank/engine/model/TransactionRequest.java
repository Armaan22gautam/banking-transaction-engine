package com.bank.engine.model;

import java.math.BigDecimal;

/**
 * An incoming money-movement request. DEPOSIT uses destination, WITHDRAWAL uses source,
 * TRANSFER uses both. Validation happens in the service so that invalid requests are audited.
 */
public record TransactionRequest(String transactionId, TransactionType type,
                                 Long sourceAccountId, Long destinationAccountId, BigDecimal amount) {

    public static TransactionRequest deposit(String id, Long account, BigDecimal amount) {
        return new TransactionRequest(id, TransactionType.DEPOSIT, null, account, amount);
    }

    public static TransactionRequest withdrawal(String id, Long account, BigDecimal amount) {
        return new TransactionRequest(id, TransactionType.WITHDRAWAL, account, null, amount);
    }

    public static TransactionRequest transfer(String id, Long from, Long to, BigDecimal amount) {
        return new TransactionRequest(id, TransactionType.TRANSFER, from, to, amount);
    }
}
