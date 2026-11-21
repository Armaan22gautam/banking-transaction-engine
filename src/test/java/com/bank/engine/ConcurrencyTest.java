package com.bank.engine;

import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionStatus;
import com.bank.engine.service.TransactionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest extends AbstractPostgresTest {

    /** The mandatory scenario from the brief: balance 10,000, concurrent withdrawals of 7,000 and 5,000. */
    @RepeatedTest(100)
    void mandatoryScenario_onlyOneWithdrawalSucceeds(RepetitionInfo info) throws Exception {
        account(1, "10000.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        int n = info.getCurrentRepetition();

        List<Callable<TransactionResult>> tasks = List.of(
                () -> svc.process(TransactionRequest.withdrawal("A-" + n, 1L, new BigDecimal("7000"))),
                () -> svc.process(TransactionRequest.withdrawal("B-" + n, 1L, new BigDecimal("5000"))));
        List<TransactionResult> results = runConcurrently(tasks);

        long ok = results.stream().filter(TransactionResult::isSuccess).count();
        long rejected = results.stream().filter(r -> r.status() == TransactionStatus.REJECTED).count();
        assertEquals(1, ok, "exactly one withdrawal must succeed");
        assertEquals(1, rejected, "the other must be rejected");
        BigDecimal balance = balanceOf(1);
        assertTrue(balance.compareTo(new BigDecimal("3000")) == 0 || balance.compareTo(new BigDecimal("5000")) == 0,
                "final balance must be 3000 or 5000 but was " + balance);
        assertTrue(balance.signum() >= 0);
    }

    @Test
    void manyWithdrawalsOnOneAccount_neverOverdraw() throws Exception {
        account(1, "10000.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            tasks.add(() -> svc.process(TransactionRequest.withdrawal("W-" + n, 1L, new BigDecimal("300"))));
        }
        List<TransactionResult> results = runConcurrently(tasks);

        assertEquals(33, results.stream().filter(TransactionResult::isSuccess).count()); // 33 * 300 = 9900
        assertBalance(1, "100.00");
    }

    @Test
    void concurrentDepositsAreNeverLost() throws Exception {
        account(1, "0.00", "ACTIVE");
        TransactionService svc = new TransactionService(ds);
        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int n = i;
            tasks.add(() -> svc.process(TransactionRequest.deposit("D-" + n, 1L, new BigDecimal("25"))));
        }
        runConcurrently(tasks);
        assertBalance(1, "1000.00");
    }
}
