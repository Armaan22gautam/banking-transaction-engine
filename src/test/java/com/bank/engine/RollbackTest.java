package com.bank.engine;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionStatus;
import com.bank.engine.service.TransactionService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollbackTest extends AbstractPostgresTest {

    @Test
    void crashAfterMoneyMovedButBeforeCommitRollsEverythingBack() throws Exception {
        account(1, "1000.00", "ACTIVE");
        account(2, "0.00", "ACTIVE");
        TransactionService crashing = new TransactionService(ds, r -> { throw new IllegalStateException("simulated crash before commit"); });

        TransactionResult result = crashing.process(TransactionRequest.transfer("CRASH-1", 1L, 2L, new BigDecimal("300")));

        assertEquals(TransactionStatus.FAILED, result.status());
        assertEquals(ErrorCategory.SYSTEM_ERROR, result.errorCategory());
        assertBalance(1, "1000.00");
        assertBalance(2, "0.00");
        assertEquals("FAILED", statusOf("CRASH-1"));
        assertEquals(1, count("SELECT count(*) FROM failed_transactions WHERE transaction_id = ? AND error_category = 'SYSTEM_ERROR'", "CRASH-1"));
        assertEquals(1, count("SELECT count(*) FROM transaction_audit WHERE transaction_id = ?", "CRASH-1"));
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE status = 'PENDING'"));
    }

    @Test
    void failedTransactionCanBeRetriedSafelyAfterRecovery() throws Exception {
        account(1, "1000.00", "ACTIVE");
        TransactionService crashing = new TransactionService(ds, r -> { throw new IllegalStateException("boom"); });
        crashing.process(TransactionRequest.deposit("CRASH-2", 1L, new BigDecimal("50")));

        // Same id replays are treated as duplicates of the FAILED record: no money moves; a new id is required.
        TransactionResult replay = new TransactionService(ds).process(TransactionRequest.deposit("CRASH-2", 1L, new BigDecimal("50")));
        assertEquals(true, replay.duplicate());
        assertEquals(TransactionStatus.FAILED, replay.status());
        assertBalance(1, "1000.00");

        TransactionResult retry = new TransactionService(ds).process(TransactionRequest.deposit("CRASH-2-retry", 1L, new BigDecimal("50")));
        assertEquals(true, retry.isSuccess());
        assertBalance(1, "1050.00");
    }

    private static String statusOf(String id) throws Exception {
        try (var c = ds.getConnection(); var ps = c.prepareStatement("SELECT status FROM transactions WHERE transaction_id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
