package com.bank.engine;

import com.bank.engine.config.Database;
import com.bank.engine.config.DbSetup;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.service.TransactionService;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Commands: init (schema + seed), demo (resets data, then runs every core scenario). */
@SpringBootApplication
public class Main {
    public Main() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            SpringApplication.run(Main.class, args);
            return;
        }
        String cmd = args[0];
        try (HikariDataSource ds = Database.fromEnv(5)) {
            switch (cmd) {
                case "init" -> {
                    DbSetup.applySchema(ds);
                    DbSetup.seed(ds);
                    System.out.println("Schema created and seed data loaded (1000 customers/accounts, 50,000.00 each).");
                }
                case "demo" -> demo(ds);
                default -> System.out.println("Usage: init | demo");
            }
        }
    }

    @Bean
    public HikariDataSource dataSource() {
        return Database.fromEnv(20);
    }

    private static void demo(HikariDataSource ds) {
        DbSetup.resetAndSeed(ds);
        TransactionService svc = new TransactionService(ds);
        show(svc.process(TransactionRequest.deposit("DEMO-1", 1L, bd("500.00"))));
        show(svc.process(TransactionRequest.withdrawal("DEMO-2", 1L, bd("100.00"))));
        show(svc.process(TransactionRequest.transfer("DEMO-3", 1L, 2L, bd("250.00"))));
        System.out.println("-- replay of DEMO-1 (must not change any balance):");
        show(svc.process(TransactionRequest.deposit("DEMO-1", 1L, bd("500.00"))));
        System.out.println("-- rejections:");
        show(svc.process(TransactionRequest.withdrawal("DEMO-4", 1L, bd("9000000.00"))));
        show(svc.process(TransactionRequest.deposit("DEMO-5", 999_999L, bd("10.00"))));
        show(svc.process(TransactionRequest.deposit("DEMO-6", 1L, bd("-5.00"))));
    }

    private static void show(TransactionResult r) {
        System.out.printf("%-8s status=%-8s duplicate=%-5s category=%s reason=%s%n",
                r.transactionId(), r.status(), r.duplicate(), r.errorCategory(), r.reason());
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
