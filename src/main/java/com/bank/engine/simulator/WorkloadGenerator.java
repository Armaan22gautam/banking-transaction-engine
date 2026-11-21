package com.bank.engine.simulator;

import com.bank.engine.model.TransactionRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic workload (same seed -> same requests) so every worker-count run is comparable.
 * Mix: ~40% deposits, ~35% withdrawals, ~25% transfers, 20% of traffic on 10 "hot" accounts,
 * ~1% duplicate ids, ~1% unknown accounts, ~0.5% invalid amounts.
 */
public final class WorkloadGenerator {
    private WorkloadGenerator() {}

    public static List<TransactionRequest> generate(int total, int accountCount, long seed) {
        Random rnd = new Random(seed);
        List<TransactionRequest> list = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String id = "TXN-" + i;
            double p = rnd.nextDouble();
            if (p < 0.01 && !list.isEmpty()) {
                list.add(list.get(rnd.nextInt(list.size())));                       // duplicate id
            } else if (p < 0.02) {
                list.add(TransactionRequest.deposit(id, (long) accountCount + 1 + rnd.nextInt(100), amount(rnd))); // unknown account
            } else if (p < 0.025) {
                list.add(TransactionRequest.withdrawal(id, account(rnd, accountCount), amount(rnd).negate()));     // invalid amount
            } else {
                long a = account(rnd, accountCount);
                int kind = rnd.nextInt(100);
                if (kind < 40) {
                    list.add(TransactionRequest.deposit(id, a, amount(rnd)));
                } else if (kind < 75) {
                    list.add(TransactionRequest.withdrawal(id, a, amount(rnd)));
                } else {
                    long b = account(rnd, accountCount);
                    while (b == a) b = account(rnd, accountCount);
                    list.add(TransactionRequest.transfer(id, a, b, amount(rnd)));
                }
            }
        }
        return list;
    }

    private static long account(Random rnd, int accountCount) {
        return rnd.nextDouble() < 0.20 ? 1 + rnd.nextInt(10) : 1 + rnd.nextInt(accountCount);
    }

    private static BigDecimal amount(Random rnd) {
        return BigDecimal.valueOf(10_000 + rnd.nextInt(490_000), 2); // 100.00 .. 4999.99
    }
}
