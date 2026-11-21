-- Banking Transaction Processing System : PostgreSQL schema
DROP TABLE IF EXISTS failed_transactions, transaction_audit, transactions, accounts, customers CASCADE;

CREATE TABLE customers (
    customer_id  BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    email        VARCHAR(150) NOT NULL,
    phone        VARCHAR(20),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_email UNIQUE (email)
);

CREATE TABLE accounts (
    account_id   BIGINT PRIMARY KEY,
    customer_id  BIGINT        NOT NULL REFERENCES customers(customer_id),
    balance      NUMERIC(15,2) NOT NULL DEFAULT 0,
    status       VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE','INACTIVE','FROZEN'))
);

-- No FK from transactions to accounts on purpose: requests for unknown accounts
-- (and other invalid requests) must still be persisted as REJECTED for audit.
CREATE TABLE transactions (
    transaction_id          VARCHAR(64)   PRIMARY KEY,
    type                    VARCHAR(12)   NOT NULL,
    amount                  NUMERIC(19,4),
    status                  VARCHAR(10)   NOT NULL,
    source_account_id       BIGINT,
    destination_account_id  BIGINT,
    reason                  VARCHAR(255),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT chk_txn_type   CHECK (type IN ('DEPOSIT','WITHDRAWAL','TRANSFER')),
    CONSTRAINT chk_txn_status CHECK (status IN ('PENDING','SUCCESS','FAILED','REJECTED'))
);

CREATE TABLE transaction_audit (
    audit_id        BIGSERIAL PRIMARY KEY,
    transaction_id  VARCHAR(64) NOT NULL REFERENCES transactions(transaction_id),
    event_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    thread_name     VARCHAR(100) NOT NULL,
    outcome         VARCHAR(10)  NOT NULL,
    duration_ms     BIGINT       NOT NULL,
    CONSTRAINT chk_audit_duration CHECK (duration_ms >= 0)
);

CREATE TABLE failed_transactions (
    transaction_id  VARCHAR(64) PRIMARY KEY REFERENCES transactions(transaction_id),
    error_category  VARCHAR(30)  NOT NULL,
    reason          VARCHAR(255) NOT NULL,
    failed_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Indexes justified by the workload (see docs/DESIGN.md)
CREATE INDEX idx_accounts_customer   ON accounts(customer_id);
CREATE INDEX idx_txn_source          ON transactions(source_account_id);
CREATE INDEX idx_txn_destination     ON transactions(destination_account_id);
CREATE INDEX idx_txn_created_at      ON transactions(created_at);
CREATE INDEX idx_audit_txn           ON transaction_audit(transaction_id);
CREATE INDEX idx_failed_category     ON failed_transactions(error_category)
