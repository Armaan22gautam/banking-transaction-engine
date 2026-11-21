package com.bank.engine.simulator;

import com.bank.engine.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Samples pg_stat_activity every 100 ms to show where active backends wait (locks, IO, CPU). Best effort. */
final class WaitSampler implements AutoCloseable {
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    WaitSampler() {
        thread = new Thread(this::loop, "wait-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        String sql = "SELECT COALESCE(wait_event_type, 'CPU') || ':' || COALESCE(wait_event, 'running'), count(*) "
                   + "FROM pg_stat_activity WHERE datname = current_database() AND state = 'active' AND pid <> pg_backend_pid() GROUP BY 1";
        try (Connection c = Database.direct(); PreparedStatement ps = c.prepareStatement(sql)) {
            while (running) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) counts.merge(rs.getString(1), rs.getInt(2), Integer::sum);
                }
                Thread.sleep(100);
            }
        } catch (Exception ignored) {
            // sampling is optional evidence, never fail the run because of it
        }
    }

    String top(int n) {
        String s = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        return s.isEmpty() ? "n/a" : s;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
