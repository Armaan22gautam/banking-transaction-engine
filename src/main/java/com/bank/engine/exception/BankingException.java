package com.bank.engine.exception;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionStatus;

/** Base type: every business exception maps to exactly one status and one error category. */
public abstract class BankingException extends RuntimeException {
    private final ErrorCategory category;
    private final TransactionStatus status;

    protected BankingException(String message, ErrorCategory category, TransactionStatus status) {
        super(message);
        this.category = category;
        this.status = status;
    }

    public ErrorCategory category() { return category; }
    public TransactionStatus status() { return status; }
}
