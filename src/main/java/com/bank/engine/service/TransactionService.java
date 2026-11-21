package com.bank.engine.service;

import com.bank.engine.exception.AccountNotFoundException;
import com.bank.engine.exception.BankingException;
import com.bank.engine.exception.DuplicateTransactionException;
import com.bank.engine.exception.InsufficientBalanceException;
import com.bank.engine.exception.InvalidTransactionException;
import com.bank.engine.model.Account;
import com.bank.engine.model.ErrorCategory;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionStatus;
import com.bank.engine.repository.AccountRepository;
import com.bank.engine.repository.TransactionRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Transaction lifecycle: receive -> validate -> check idempotency -> begin + lock -> apply -> audit + commit -> respond.
 *
 * Concurrency strategy: PESSIMISTIC row locking (SELECT ... FOR UPDATE) on READ COMMITTED.
 * Transfers lock the lower account id first, then the higher one, so two opposite transfers can never deadlock.
 * Idempotency: application-level lookup plus a PRIMARY KEY claim insert on transaction_id.
 */
public class TransactionService {
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00");
    private static final int MAX_ATTEMPTS = 3;

    /** Test seam: lets tests simulate a crash after the money moved but before commit. */
    @FunctionalInterface
    public interface BeforeCommitHook { void run(TransactionRequest request); }

    private final DataSource dataSource;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final BeforeCommitHook beforeCommit;

    public TransactionService(DataSource dataSource) {
        this(dataSource, r -> { });
    }

    public TransactionService(DataSource dataSource, BeforeCommitHook beforeCommit) {
        this.dataSource = dataSource;
        this.beforeCommit = beforeCommit;
    }

    public TransactionResult process(TransactionRequest req) {
        long start = System.nanoTime();
        if (req != null) {
            MDC.put("txnId", String.valueOf(req.transactionId()));
            MDC.put("op", String.valueOf(req.type()));
        }
        try {
            TransactionResult result = execute(req, start);
            logResult(req, result);
            return result;
        } finally {
            MDC.clear();
        }
    }

    // ------------------------------------------------------------------ orchestration

    private TransactionResult execute(TransactionRequest req, long start) {
        if (req == null || req.transactionId() == null || req.transactionId().isBlank() || req.type() == null) {
            // Nothing can be persisted without an id and type; the log line is the only trace.
            return new TransactionResult(req == null ? null : req.transactionId(), TransactionStatus.REJECTED,
                    "Transaction id and type are required", ErrorCategory.INVALID_TRANSACTION, false, elapsedMs(start));
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return attemptOnce(req, start);
            } catch (DuplicateTransactionException d) {
                return new TransactionResult(req.transactionId(), d.status(), d.getMessage(),
                        ErrorCategory.DUPLICATE_TRANSACTION, true, elapsedMs(start));
            } catch (SQLException e) {
                if (isTransient(e) && attempt < MAX_ATTEMPTS) {
                    log.warn("event=txn_retry attempt={} sqlState={}", attempt, e.getSQLState());
                    backoff(attempt);
                    continue;
                }
                return recordSystemFailure(req, e, start);
            } catch (RuntimeException e) {
                return recordSystemFailure(req, e, start);
            }
        }
    }

    private TransactionResult attemptOnce(TransactionRequest req, long start) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                // 3. idempotency: application-level check, then database-level claim
                Optional<TransactionStatus> existing = transactions.findStatus(c, req.transactionId());
                if (existing.isPresent()) {
                    throw new DuplicateTransactionException(req.transactionId(), existing.get());
                }
                if (!transactions.claim(c, req)) {
                    TransactionStatus original = transactions.findStatus(c, req.transactionId()).orElse(TransactionStatus.REJECTED);
                    throw new DuplicateTransactionException(req.transactionId(), original);
                }

                Savepoint afterClaim = c.setSavepoint();
                try {
                    // 2. validate -> 4. lock -> 5. apply
                    validate(req);
                    applyOperation(c, req);

                    // 6. audit + commit
                    transactions.complete(c, req.transactionId(), TransactionStatus.SUCCESS, null);
                    transactions.insertAudit(c, req.transactionId(), Thread.currentThread().getName(), "SUCCESS", elapsedMs(start));
                    beforeCommit.run(req);
                    c.commit();
                    return new TransactionResult(req.transactionId(), TransactionStatus.SUCCESS, null, null, false, elapsedMs(start));
                } catch (BankingException e) {
                    // Business rejection: undo anything applied, keep the claim row, record why.
                    c.rollback(afterClaim);
                    transactions.complete(c, req.transactionId(), e.status(), e.getMessage());
                    transactions.insertFailure(c, req.transactionId(), e.category(), e.getMessage());
                    transactions.insertAudit(c, req.transactionId(), Thread.currentThread().getName(), e.status().name(), elapsedMs(start));
                    c.commit();
                    return new TransactionResult(req.transactionId(), e.status(), e.getMessage(), e.category(), false, elapsedMs(start));
                }
            } catch (SQLException | RuntimeException e) {
                safeRollback(c);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ business rules

    private void validate(TransactionRequest r) {
        BigDecimal amount = r.amount();
        if (amount == null) throw new InvalidTransactionException("Amount is required");
        if (amount.signum() <= 0) throw new InvalidTransactionException("Amount must be greater than zero");
        if (amount.stripTrailingZeros().scale() > 2) throw new InvalidTransactionException("Amount cannot have more than 2 decimal places");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new InvalidTransactionException("Amount exceeds the per-transaction limit");
        switch (r.type()) {
            case DEPOSIT -> require(r.destinationAccountId() != null, "Destination account is required for a deposit");
            case WITHDRAWAL -> require(r.sourceAccountId() != null, "Source account is required for a withdrawal");
            case TRANSFER -> {
                require(r.sourceAccountId() != null && r.destinationAccountId() != null, "Source and destination accounts are required for a transfer");
                require(!r.sourceAccountId().equals(r.destinationAccountId()), "Source and destination accounts must differ");
            }
        }
    }

    private void applyOperation(Connection c, TransactionRequest r) throws SQLException {
        switch (r.type()) {
            case DEPOSIT -> {
                Account dest = lockActive(c, r.destinationAccountId());
                accounts.adjustBalance(c, dest.accountId(), r.amount());
            }
            case WITHDRAWAL -> {
                Account src = lockActive(c, r.sourceAccountId());
                requireFunds(src, r.amount());
                accounts.adjustBalance(c, src.accountId(), r.amount().negate());
            }
            case TRANSFER -> {
                long low = Math.min(r.sourceAccountId(), r.destinationAccountId());
                long high = Math.max(r.sourceAccountId(), r.destinationAccountId());
                Account first = lock(c, low);       // deterministic lock order prevents deadlocks
                Account second = lock(c, high);
                Account src = first.accountId() == r.sourceAccountId() ? first : second;
                Account dst = src == first ? second : first;
                requireActive(src);
                requireActive(dst);
                requireFunds(src, r.amount());
                accounts.adjustBalance(c, src.accountId(), r.amount().negate());
                accounts.adjustBalance(c, dst.accountId(), r.amount());
            }
        }
    }

    private Account lock(Connection c, long accountId) throws SQLException {
        return accounts.lockById(c, accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private Account lockActive(Connection c, long accountId) throws SQLException {
        Account a = lock(c, accountId);
        requireActive(a);
        return a;
    }

    private static void requireActive(Account a) {
        if (!a.isActive()) {
            throw new InvalidTransactionException(ErrorCategory.ACCOUNT_INACTIVE, "Account " + a.accountId() + " is " + a.status());
        }
    }

    private static void requireFunds(Account a, BigDecimal amount) {
        if (a.balance().compareTo(amount) < 0) throw new InsufficientBalanceException(a.accountId(), amount);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new InvalidTransactionException(message);
    }

    // ------------------------------------------------------------------ failure handling

    /** Unexpected failure: the main transaction is already rolled back; record FAILED in a fresh transaction. */
    private TransactionResult recordSystemFailure(TransactionRequest req, Exception e, long start) {
        String reason = "Internal error: " + e.getClass().getSimpleName()
                + (e instanceof SQLException sql && sql.getSQLState() != null ? " (SQLState " + sql.getSQLState() + ")" : "");
        log.error("event=txn_system_failure reason=\"{}\"", reason, e);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                transactions.insertTerminal(c, req, TransactionStatus.FAILED, reason);
                transactions.insertFailure(c, req.transactionId(), ErrorCategory.SYSTEM_ERROR, reason);
                transactions.insertAudit(c, req.transactionId(), Thread.currentThread().getName(), "FAILED", elapsedMs(start));
                c.commit();
            } catch (SQLException ex) {
                safeRollback(c);
                throw ex;
            }
        } catch (SQLException ex) {
            log.error("event=failure_not_recorded reason=\"{}\"", ex.getClass().getSimpleName());
        }
        return new TransactionResult(req.transactionId(), TransactionStatus.FAILED, reason, ErrorCategory.SYSTEM_ERROR, false, elapsedMs(start));
    }

    private static boolean isTransient(SQLException e) {
        String state = e.getSQLState();
        return "40001".equals(state) || "40P01".equals(state); // serialization failure, deadlock detected
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void safeRollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // connection is being discarded anyway
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void logResult(TransactionRequest req, TransactionResult r) {
        String src = req == null ? "NA" : String.valueOf(req.sourceAccountId());
        String dst = req == null ? "NA" : String.valueOf(req.destinationAccountId());
        String amount = req == null || req.amount() == null ? "NA" : req.amount().toPlainString();
        String line = "event=txn_complete src={} dst={} amount={} status={} category={} duplicate={} durationMs={} reason=\"{}\"";
        Object[] args = {src, dst, amount, r.status(), r.errorCategory(), r.duplicate(), r.durationMs(), r.reason()};
        if (r.status() == TransactionStatus.FAILED) log.error(line, args);
        else if (r.status() == TransactionStatus.REJECTED || r.duplicate()) log.warn(line, args);
        else log.info(line, args);
    }
}
