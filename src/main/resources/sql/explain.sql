-- Run after a performance run so the tables are populated.
-- docker compose exec -T db psql -U bank -d bankdb < src/main/resources/sql/explain.sql > docs/explain-output.txt

\echo '=== Q1: Top 10 accounts by volume ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT account_id, COUNT(*) AS txn_count, SUM(amount) AS total_amount
FROM (
    SELECT source_account_id AS account_id, amount FROM transactions WHERE status = 'SUCCESS' AND source_account_id IS NOT NULL
    UNION ALL
    SELECT destination_account_id, amount FROM transactions WHERE status = 'SUCCESS' AND destination_account_id IS NOT NULL
) t
GROUP BY account_id ORDER BY txn_count DESC, total_amount DESC LIMIT 10;

\echo '=== Q2: Customer history (debit side, index on source_account_id) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.transaction_id, t.type, t.amount, t.status, t.created_at
FROM accounts a JOIN transactions t ON t.source_account_id = a.account_id
WHERE a.customer_id = 1
ORDER BY t.created_at;

\echo '=== Q3: Daily amount by type ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT created_at::date AS day, type, COUNT(*), SUM(amount)
FROM transactions WHERE status = 'SUCCESS'
GROUP BY created_at::date, type ORDER BY day, type;

\echo '=== Q4: Row lock lookup used on the hot path (primary key) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT account_id, customer_id, balance, status FROM accounts WHERE account_id = 42 FOR UPDATE;

\echo '=== Q5: Idempotency lookup (unique PK on transaction_id) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT status FROM transactions WHERE transaction_id = 'TXN-100';
