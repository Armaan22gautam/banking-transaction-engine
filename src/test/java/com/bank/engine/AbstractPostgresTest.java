package com.bank.engine;

import com.bank.engine.config.Database;
import com.bank.engine.config.DbSetup;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One shared PostgreSQL container for the whole test run (requires Docker). */
public abstract class AbstractPostgresTest {
    static final PostgreSQLContainer<?> PG;
    protected static final HikariDataSource ds;

    static {
        PostgreSQLContainer<?> container = null;
        HikariDataSource dataSource;
        try {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            dataSource = Database.create(container.getJdbcUrl(), container.getUsername(), container.getPassword(), 30);
        } catch (RuntimeException dockerFailure) {
            container = null;
            dataSource = Database.fromEnv(30);
        }
        PG = container;
        ds = dataSource;
        DbSetup.applySchema(ds);
    }

    @BeforeEach
    void cleanDatabase() {
        DbSetup.truncateAll(ds);
    }

    protected static void account(long id, String balance, String status) throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO customers(customer_id, name, email) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setLong(1, id);
                ps.setString(2, "Customer " + id);
                ps.setString(3, "c" + id + "@example.com");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts(account_id, customer_id, balance, status) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, id);
                ps.setLong(2, id);
                ps.setBigDecimal(3, new BigDecimal(balance));
                ps.setString(4, status);
                ps.executeUpdate();
            }
        }
    }

    protected static BigDecimal balanceOf(long id) throws Exception {
        return scalar("SELECT balance FROM accounts WHERE account_id = ?", id);
    }

    protected static long count(String sql, Object... args) throws Exception {
        return scalar(sql, args).longValue();
    }

    private static BigDecimal scalar(String sql, Object... args) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    protected static void assertBalance(long id, String expected) throws Exception {
        assertEquals(0, new BigDecimal(expected).compareTo(balanceOf(id)),
                "balance of account " + id + " expected " + expected + " but was " + balanceOf(id));
    }

    /** Releases all tasks at the same instant to maximise contention. */
    protected static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            ex.shutdownNow();
        }
    }
}
