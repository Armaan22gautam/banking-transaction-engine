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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferTest extends AbstractPostgresTest {

    @Test
    void transferMovesMoneyAtomically() throws Exception {
        account(1, "1000.00", "ACTIVE");
        account(2, "0.00", "ACTIVE");
        TransactionResult r = new TransactionService(ds).process(TransactionRequest.transfer("T-1", 1L, 2L, new BigDecimal("400")));
        assertTrue(r.isSuccess());
        assertBalance(1, "600.00");
        assertBalance(2, "400.00");
    }

    @Test
    void insufficientFundsLeavesBothAccountsUntouched() throws Exception {
        account(1, "100.00", "ACTIVE");
        account(2, "0.00", "ACTIVE");
        TransactionResult r = new TransactionService(ds).process(TransactionRequest.transfer("T-2", 1L, 2L, new BigDecimal("400")));
        assertEquals(TransactionStatus.REJECTED, r.status());
        assertEquals(ErrorCategory.INSUFFICIENT_BALANCE, r.errorCategory());
        assertBalance(1, "100.00");
        assertBalance(2, "0.00");
    }

    @Test
    void unknownDestinationRollsBackTheDebit() throws Exception {
        account(1, "1000.00", "ACTIVE");
        TransactionResult r = new TransactionService(ds).process(TransactionRequest.transfer("T-3", 1L, 777L, new BigDecimal("400")));
        assertEquals(ErrorCategory.ACCOUNT_NOT_FOUND, r.errorCategory());
        assertBalance(1, "1000.00");
    }

    /** Opposite-direction transfers are the classic deadlock; ordered locking must make them safe. */
    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        account(1, "100000.00", "ACTIVE");
        account(2, "100000.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int n = i;
            tasks.add(() -> svc.process(TransactionRequest.transfer("AB-" + n, 1L, 2L, new BigDecimal("10"))));
            tasks.add(() -> svc.process(TransactionRequest.transfer("BA-" + n, 2L, 1L, new BigDecimal("10"))));
        }
        List<TransactionResult> results = runConcurrently(tasks);

        assertTrue(results.stream().allMatch(TransactionResult::isSuccess), "no failures, no deadlock victims");
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE status = 'FAILED'"));
        assertEquals(0, new BigDecimal("200000.00").compareTo(balanceOf(1).add(balanceOf(2))));
        assertBalance(1, "100000.00");
    }
}
