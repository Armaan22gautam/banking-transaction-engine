package com.bank.engine.repository;

import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;

public class TransactionRepository {
    private static final BigDecimal STORABLE_LIMIT = new BigDecimal("100000000000000");

    /** Application-level duplicate check. */
    public Optional<TransactionStatus> findStatus(Connection c, String transactionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM transactions WHERE transaction_id = ?")) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(TransactionStatus.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /**
     * Database-level idempotency: the PK on transaction_id makes the insert the claim.
     * A concurrent duplicate blocks here until the first transaction ends, then gets 0 rows.
     */
    public boolean claim(Connection c, TransactionRequest r) throws SQLException {
        return insert(c, r, TransactionStatus.PENDING, null);
    }

    /** Used when the main transaction was rolled back and the failure itself must still be recorded. */
    public boolean insertTerminal(Connection c, TransactionRequest r, TransactionStatus status, String reason) throws SQLException {
        return insert(c, r, status, reason);
    }

    private boolean insert(Connection c, TransactionRequest r, TransactionStatus status, String reason) throws SQLException {
        String sql = "INSERT INTO transactions(transaction_id, type, amount, status, source_account_id, destination_account_id, reason, completed_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (transaction_id) DO NOTHING";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, r.transactionId());
            ps.setString(2, r.type().name());
            ps.setObject(3, storable(r.amount()), Types.NUMERIC);
            ps.setString(4, status.name());
            ps.setObject(5, r.sourceAccountId(), Types.BIGINT);
            ps.setObject(6, r.destinationAccountId(), Types.BIGINT);
            ps.setString(7, truncate(reason));
            if (status == TransactionStatus.PENDING) ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            else ps.setObject(8, OffsetDateTime.now(), Types.TIMESTAMP_WITH_TIMEZONE);
            return ps.executeUpdate() == 1;
        }
    }

    public void complete(Connection c, String transactionId, TransactionStatus status, String reason) throws SQLException {
        String sql = "UPDATE transactions SET status = ?, reason = ?, completed_at = now() WHERE transaction_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, truncate(reason));
            ps.setString(3, transactionId);
            ps.executeUpdate();
        }
    }

    public void insertFailure(Connection c, String transactionId, ErrorCategory category, String reason) throws SQLException {
        String sql = "INSERT INTO failed_transactions(transaction_id, error_category, reason) VALUES (?, ?, ?) ON CONFLICT (transaction_id) DO NOTHING";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            ps.setString(2, category.name());
            ps.setString(3, truncate(reason));
            ps.executeUpdate();
        }
    }

    public void insertAudit(Connection c, String transactionId, String thread, String outcome, long durationMs) throws SQLException {
        String sql = "INSERT INTO transaction_audit(transaction_id, thread_name, outcome, duration_ms) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            ps.setString(2, thread);
            ps.setString(3, outcome);
            ps.setLong(4, durationMs);
            ps.executeUpdate();
        }
    }

    private static BigDecimal storable(BigDecimal amount) {
        return (amount == null || amount.abs().compareTo(STORABLE_LIMIT) >= 0) ? null : amount;
    }

    private static String truncate(String s) {
        return (s == null || s.length() <= 255) ? s : s.substring(0, 255);
    }
}
