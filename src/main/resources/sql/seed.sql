-- 1000 customers, 1000 ACTIVE accounts with 50,000.00 each
INSERT INTO customers(name, email, phone)
SELECT 'Customer ' || g, 'customer' || g || '@example.com', '98' || lpad(g::text, 8, '0')
FROM generate_series(1, 1000) g;

INSERT INTO accounts(account_id, customer_id, balance, status)
SELECT g, g, 50000.00, 'ACTIVE'
FROM generate_series(1, 1000) g
