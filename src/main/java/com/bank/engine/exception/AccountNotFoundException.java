package com.bank.engine.exception;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionStatus;

public class AccountNotFoundException extends BankingException {
    public AccountNotFoundException(long accountId) {
        super("Account not found: " + accountId, ErrorCategory.ACCOUNT_NOT_FOUND, TransactionStatus.REJECTED);
    }
}
