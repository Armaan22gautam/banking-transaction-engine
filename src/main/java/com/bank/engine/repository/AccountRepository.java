package com.bank.engine.repository;

import com.bank.engine.model.Account;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AccountRepository {

    public List<Account> list(Connection c, int limit) throws SQLException {
        String sql = "SELECT account_id, customer_id, balance, status FROM accounts ORDER BY account_id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Account> accounts = new ArrayList<>();
                while (rs.next()) {
                    accounts.add(new Account(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getString(4)));
                }
                return accounts;
            }
        }
    }

    /** Pessimistic lock: the row stays locked until the surrounding DB transaction ends. */
    public Optional<Account> lockById(Connection c, long accountId) throws SQLException {
        String sql = "SELECT account_id, customer_id, balance, status FROM accounts WHERE account_id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Account(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getString(4)));
            }
        }
    }

    /** Relative update (balance = balance + delta); the CHECK (balance >= 0) is the last line of defence. */
    public BigDecimal adjustBalance(Connection c, long accountId, BigDecimal delta) throws SQLException {
        String sql = "UPDATE accounts SET balance = balance + ?, updated_at = now() WHERE account_id = ? RETURNING balance";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, delta);
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Account vanished while locked: " + accountId);
                return rs.getBigDecimal(1);
            }
        }
    }
}
