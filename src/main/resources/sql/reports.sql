-- Report 1: Top 10 accounts by transaction volume (successful transactions, debit or credit side)
SELECT account_id, COUNT(*) AS txn_count, SUM(amount) AS total_amount
FROM (
    SELECT source_account_id AS account_id, amount
    FROM transactions WHERE status = 'SUCCESS' AND source_account_id IS NOT NULL
    UNION ALL
    SELECT destination_account_id, amount
    FROM transactions WHERE status = 'SUCCESS' AND destination_account_id IS NOT NULL
) t
GROUP BY account_id
ORDER BY txn_count DESC, total_amount DESC
LIMIT 10;

-- Report 2: Failed / rejected transactions grouped by reason category
SELECT f.error_category, t.status, COUNT(*) AS occurrences
FROM failed_transactions f
JOIN transactions t ON t.transaction_id = f.transaction_id
GROUP BY f.error_category, t.status
ORDER BY occurrences DESC;

-- Report 3: Daily transaction amount by type (successful only)
SELECT created_at::date AS day, type, COUNT(*) AS txn_count, SUM(amount) AS total_amount
FROM transactions
WHERE status = 'SUCCESS'
GROUP BY created_at::date, type
ORDER BY day, type;

-- Report 4: Accounts with more than N transactions (N = 100 here, change as needed)
SELECT account_id, COUNT(*) AS txn_count
FROM (
    SELECT source_account_id AS account_id FROM transactions WHERE source_account_id IS NOT NULL
    UNION ALL
    SELECT destination_account_id FROM transactions WHERE destination_account_id IS NOT NULL
) t
GROUP BY account_id
HAVING COUNT(*) > 100
ORDER BY txn_count DESC;

-- Report 5: Complete transaction history for a customer (customer_id = 1 here)
SELECT t.transaction_id, t.type, t.amount, t.status, 'DEBIT' AS direction,
       t.source_account_id, t.destination_account_id, t.created_at
FROM accounts a JOIN transactions t ON t.source_account_id = a.account_id
WHERE a.customer_id = 1
UNION ALL
SELECT t.transaction_id, t.type, t.amount, t.status, 'CREDIT' AS direction,
       t.source_account_id, t.destination_account_id, t.created_at
FROM accounts a JOIN transactions t ON t.destination_account_id = a.account_id
WHERE a.customer_id = 1
ORDER BY created_at;
