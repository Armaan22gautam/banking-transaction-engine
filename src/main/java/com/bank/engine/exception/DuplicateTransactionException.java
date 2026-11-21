package com.bank.engine.exception;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionStatus;

/** Carries the status of the ORIGINAL transaction so replays can return the same outcome. */
public class DuplicateTransactionException extends BankingException {
    public DuplicateTransactionException(String transactionId, TransactionStatus originalStatus) {
        super("Duplicate transaction id " + transactionId + "; original outcome was " + originalStatus,
              ErrorCategory.DUPLICATE_TRANSACTION, originalStatus);
    }
}
