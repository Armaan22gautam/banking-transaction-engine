package com.bank.engine.simulator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Proves money conservation after a run: sum(balances) must equal
 * opening total + successful deposits - successful withdrawals (transfers net to zero).
 */
public final class IntegrityVerifier {
    private IntegrityVerifier() {}

    public record Report(boolean ok, String detail) {}

    public static BigDecimal totalBalance(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(balance), 0) FROM accounts")) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    public static Report verify(DataSource ds, BigDecimal openingTotal) throws SQLException {
        String sql = """
            SELECT (SELECT COALESCE(SUM(balance), 0) FROM accounts),
                   (SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'DEPOSIT' AND status = 'SUCCESS'),
                   (SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'WITHDRAWAL' AND status = 'SUCCESS'),
                   (SELECT COUNT(*) FROM accounts WHERE balance < 0),
                   (SELECT COUNT(*) FROM transactions WHERE status = 'PENDING'),
                   (SELECT COUNT(*) FROM transactions t WHERE status <> 'SUCCESS'
                        AND NOT EXISTS (SELECT 1 FROM failed_transactions f WHERE f.transaction_id = t.transaction_id)),
                   (SELECT COUNT(*) FROM transactions t
                        WHERE NOT EXISTS (SELECT 1 FROM transaction_audit a WHERE a.transaction_id = t.transaction_id))
            """;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            BigDecimal actual = rs.getBigDecimal(1);
            BigDecimal expected = openingTotal.add(rs.getBigDecimal(2)).subtract(rs.getBigDecimal(3));
            long negative = rs.getLong(4), pending = rs.getLong(5), unexplained = rs.getLong(6), unaudited = rs.getLong(7);
            boolean ok = actual.compareTo(expected) == 0 && negative == 0 && pending == 0 && unexplained == 0 && unaudited == 0;
            String detail = String.format("balanceSum=%s expected=%s negativeAccounts=%d pending=%d failedWithoutReason=%d unaudited=%d",
                    actual.toPlainString(), expected.toPlainString(), negative, pending, unexplained, unaudited);
            return new Report(ok, detail);
        }
    }
}
