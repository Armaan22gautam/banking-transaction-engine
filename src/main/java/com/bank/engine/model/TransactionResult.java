package com.bank.engine.model;

/** Outcome returned to the caller. "SUCCESS" is only ever returned after the DB commit succeeded. */
public record TransactionResult(String transactionId, TransactionStatus status, String reason,
                                ErrorCategory errorCategory, boolean duplicate, long durationMs) {

    public boolean isSuccess() { return status == TransactionStatus.SUCCESS && !duplicate; }
}
