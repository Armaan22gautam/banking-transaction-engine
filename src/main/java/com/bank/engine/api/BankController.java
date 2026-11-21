package com.bank.engine.api;

import com.bank.engine.config.DbSetup;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionType;
import com.bank.engine.repository.AccountRepository;
import com.bank.engine.service.TransactionService;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BankController {
    private final HikariDataSource dataSource;
    private final TransactionService transactions;
    private final AccountRepository accounts = new AccountRepository();

    public BankController(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.transactions = new TransactionService(dataSource);
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        try (Connection c = dataSource.getConnection()) {
            return Map.of("status", "ok", "database", c.isValid(2));
        } catch (Exception e) {
            return Map.of("status", "degraded", "database", false);
        }
    }

    @GetMapping("/api/accounts")
    public ResponseEntity<?> accountList(@RequestParam(value = "limit", defaultValue = "25") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try (Connection c = dataSource.getConnection()) {
            List<?> rows = accounts.list(c, safeLimit);
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return serverError(e);
        }
    }

    @GetMapping("/api/transactions")
    public ResponseEntity<?> transactionList(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String sql = "SELECT transaction_id, type, amount, status, source_account_id, "
                   + "destination_account_id, reason, created_at, completed_at "
                   + "FROM transactions ORDER BY created_at DESC LIMIT ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("transactionId", rs.getString("transaction_id"));
                    row.put("type", rs.getString("type"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("status", rs.getString("status"));
                    row.put("sourceAccountId", rs.getObject("source_account_id"));
                    row.put("destinationAccountId", rs.getObject("destination_account_id"));
                    row.put("reason", rs.getString("reason"));
                    Timestamp created = rs.getTimestamp("created_at");
                    Timestamp completed = rs.getTimestamp("completed_at");
                    row.put("createdAt", created == null ? null : created.toInstant().toString());
                    row.put("completedAt", completed == null ? null : completed.toInstant().toString());
                    rows.add(row);
                }
                return ResponseEntity.ok(rows);
            }
        } catch (Exception e) {
            return serverError(e);
        }
    }

    @PostMapping("/api/transactions")
    public ResponseEntity<?> process(@RequestBody TransactionRequestDto request) {
        try {
            TransactionType type = TransactionType.valueOf(request.type().trim().toUpperCase());
            BigDecimal amount = request.amount() == null ? null : new BigDecimal(request.amount().trim());
            TransactionRequest tx;
            switch (type) {
                case DEPOSIT -> tx = TransactionRequest.deposit(request.transactionId(), request.destinationAccountId(), amount);
                case WITHDRAWAL -> tx = TransactionRequest.withdrawal(request.transactionId(), request.sourceAccountId(), amount);
                case TRANSFER -> tx = TransactionRequest.transfer(request.transactionId(), request.sourceAccountId(),
                        request.destinationAccountId(), amount);
                default -> throw new IllegalArgumentException("Unsupported transaction type");
            }
            TransactionResult result = transactions.process(tx);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return serverError(e);
        }
    }

    @PostMapping("/api/reset")
    public ResponseEntity<?> reset() {
        try {
            DbSetup.resetAndSeed(dataSource);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Database reset and seeded"));
        } catch (Exception e) {
            return serverError(e);
        }
    }

    private ResponseEntity<Map<String, String>> serverError(Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
    }

    public record TransactionRequestDto(String transactionId, String type, Long sourceAccountId,
                                        Long destinationAccountId, String amount) {}
}
