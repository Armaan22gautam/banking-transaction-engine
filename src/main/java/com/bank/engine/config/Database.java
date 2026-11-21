package com.bank.engine.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Connection settings come from environment variables; defaults match docker-compose.yml. */
public final class Database {
    private Database() {}

    public static String url()      { return env("DB_URL", "jdbc:postgresql://localhost:5432/bankdb"); }
    public static String user()     { return env("DB_USER", "bank"); }
    public static String password() { return env("DB_PASSWORD", "bank"); }

    public static HikariDataSource fromEnv(int poolSize) {
        return create(url(), user(), password(), poolSize);
    }

    public static HikariDataSource create(String url, String user, String password, int poolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(poolSize);
        cfg.setConnectionTimeout(30_000);
        cfg.setPoolName("bank-pool");
        return new HikariDataSource(cfg);
    }

    /** Standalone connection (outside the pool), used by the wait-event sampler. */
    public static Connection direct() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
