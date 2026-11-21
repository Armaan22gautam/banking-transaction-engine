package com.bank.engine;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionStatus;
import com.bank.engine.service.TransactionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyTest extends AbstractPostgresTest {

    @Test
    void replayedTransactionIdChangesMoneyOnlyOnce() throws Exception {
        account(1, "1000.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        TransactionRequest req = TransactionRequest.deposit("DUP-1", 1L, new BigDecimal("500"));

        TransactionResult first = svc.process(req);
        TransactionResult second = svc.process(req);

        assertTrue(first.isSuccess());
        assertTrue(second.duplicate());
        assertEquals(ErrorCategory.DUPLICATE_TRANSACTION, second.errorCategory());
        assertEquals(TransactionStatus.SUCCESS, second.status(), "replay reports the original outcome");
        assertBalance(1, "1500.00");
        assertEquals(1, count("SELECT count(*) FROM transactions WHERE transaction_id = ?", "DUP-1"));
        assertEquals(1, count("SELECT count(*) FROM transaction_audit WHERE transaction_id = ?", "DUP-1"));
    }

    @Test
    void twentyConcurrentCopiesOfOneIdApplyExactlyOnce() throws Exception {
        account(1, "1000.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> svc.process(TransactionRequest.deposit("SAME-ID", 1L, new BigDecimal("500"))));
        }
        List<TransactionResult> results = runConcurrently(tasks);

        assertEquals(1, results.stream().filter(r -> !r.duplicate()).count());
        assertEquals(19, results.stream().filter(TransactionResult::duplicate).count());
        assertBalance(1, "1500.00");
    }

    @Test
    void replayOfRejectedTransactionStaysRejectedAndHarmless() throws Exception {
        account(1, "100.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        TransactionRequest req = TransactionRequest.withdrawal("REJ-1", 1L, new BigDecimal("500"));

        TransactionResult first = svc.process(req);
        TransactionResult second = svc.process(req);

        assertEquals(TransactionStatus.REJECTED, first.status());
        assertTrue(second.duplicate());
        assertFalse(second.isSuccess());
        assertBalance(1, "100.00");
    }
}
