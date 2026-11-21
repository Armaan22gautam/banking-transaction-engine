package com.bank.engine.exception;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionStatus;
import java.math.BigDecimal;

public class InsufficientBalanceException extends BankingException {
    public InsufficientBalanceException(long accountId, BigDecimal requested) {
        super("Insufficient balance in account " + accountId + " for amount " + requested.toPlainString(),
              ErrorCategory.INSUFFICIENT_BALANCE, TransactionStatus.REJECTED);
    }
}
