package com.bank.engine;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionStatus;
import com.bank.engine.service.TransactionService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest extends AbstractPostgresTest {

    @Test
    void depositAndWithdrawalHappyPath() throws Exception {
        account(1, "100.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        assertTrue(svc.process(TransactionRequest.deposit("V-1", 1L, new BigDecimal("50.50"))).isSuccess());
        assertTrue(svc.process(TransactionRequest.withdrawal("V-2", 1L, new BigDecimal("20.25"))).isSuccess());
        assertBalance(1, "130.25");
    }

    @Test
    void rejectionsCarryStatusCategoryAndAreAuditedAndPersisted() throws Exception {
        account(1, "100.00", "ACTIVE");
        account(2, "100.00", "INACTIVE");
        TransactionService svc = new TransactionService(ds);

        assertRejected(svc.process(TransactionRequest.deposit("R-1", 1L, new BigDecimal("-5"))), ErrorCategory.INVALID_TRANSACTION);
        assertRejected(svc.process(TransactionRequest.deposit("R-2", 1L, BigDecimal.ZERO)), ErrorCategory.INVALID_TRANSACTION);
        assertRejected(svc.process(TransactionRequest.deposit("R-3", 1L, null)), ErrorCategory.INVALID_TRANSACTION);
        assertRejected(svc.process(TransactionRequest.deposit("R-4", 1L, new BigDecimal("1.234"))), ErrorCategory.INVALID_TRANSACTION);
        assertRejected(svc.process(TransactionRequest.deposit("R-5", 999L, new BigDecimal("10"))), ErrorCategory.ACCOUNT_NOT_FOUND);
        assertRejected(svc.process(TransactionRequest.deposit("R-6", 2L, new BigDecimal("10"))), ErrorCategory.ACCOUNT_INACTIVE);
        assertRejected(svc.process(TransactionRequest.withdrawal("R-7", 1L, new BigDecimal("101"))), ErrorCategory.INSUFFICIENT_BALANCE);
        assertRejected(svc.process(TransactionRequest.transfer("R-8", 1L, 1L, new BigDecimal("10"))), ErrorCategory.INVALID_TRANSACTION);

        assertBalance(1, "100.00");
        assertBalance(2, "100.00");
        assertEquals(8, count("SELECT count(*) FROM transactions WHERE status = 'REJECTED'"));
        assertEquals(8, count("SELECT count(*) FROM failed_transactions"));
        assertEquals(8, count("SELECT count(*) FROM transaction_audit"));
    }

    @Test
    void missingTransactionIdIsRejectedWithoutTouchingTheDatabase() throws Exception {
        account(1, "100.00", "ACTIVE");
        TransactionResult r = new TransactionService(ds).process(TransactionRequest.deposit(" ", 1L, new BigDecimal("10")));
        assertRejected(r, ErrorCategory.INVALID_TRANSACTION);
        assertEquals(0, count("SELECT count(*) FROM transactions"));
        assertBalance(1, "100.00");
    }

    private static void assertRejected(TransactionResult r, ErrorCategory expected) {
        assertEquals(TransactionStatus.REJECTED, r.status(), r.reason());
        assertEquals(expected, r.errorCategory());
        assertTrue(r.reason() != null && !r.reason().isBlank());
    }
}
