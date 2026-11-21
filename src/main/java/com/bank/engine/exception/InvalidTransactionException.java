package com.bank.engine.exception;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionStatus;

public class InvalidTransactionException extends BankingException {
    public InvalidTransactionException(String message) {
        super(message, ErrorCategory.INVALID_TRANSACTION, TransactionStatus.REJECTED);
    }

    public InvalidTransactionException(ErrorCategory category, String message) {
        super(message, category, TransactionStatus.REJECTED);
    }
}
