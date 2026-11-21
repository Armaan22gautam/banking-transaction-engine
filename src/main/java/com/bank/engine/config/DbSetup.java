package com.bank.engine.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** Applies the SQL files in src/main/resources/sql (the same files a reviewer can run with psql). */
public final class DbSetup {
    private DbSetup() {}

    public static void applySchema(DataSource ds) { runScript(ds, "sql/schema.sql"); }
    public static void seed(DataSource ds)        { runScript(ds, "sql/seed.sql"); }

    public static void truncateAll(DataSource ds) {
        execute(ds, "TRUNCATE failed_transactions, transaction_audit, transactions, accounts, customers RESTART IDENTITY CASCADE");
    }

    public static void resetAndSeed(DataSource ds) {
        truncateAll(ds);
        seed(ds);
    }

    public static void runScript(DataSource ds, String resource) {
        String raw;
        try (InputStream in = DbSetup.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing resource " + resource);
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
        StringBuilder cleaned = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (!line.trim().startsWith("--")) cleaned.append(line).append('\n');
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : cleaned.toString().split(";")) {
                if (!sql.isBlank()) st.execute(sql.trim());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Script failed: " + resource, e);
        }
    }

    private static void execute(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }
}
