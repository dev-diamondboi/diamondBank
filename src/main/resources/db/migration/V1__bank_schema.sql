CREATE TABLE customers (
 id UUID PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(60) NOT NULL, frozen BOOLEAN NOT NULL DEFAULT FALSE,
 notifications BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 customer_id UUID NOT NULL REFERENCES customers(id), kind VARCHAR(10) NOT NULL CHECK(kind IN ('checking','savings')),
 name VARCHAR(40) NOT NULL, number VARCHAR(4) NOT NULL, balance BIGINT NOT NULL CHECK(balance >= 0),
 PRIMARY KEY(customer_id,kind)
);
CREATE TABLE goals (
 id UUID PRIMARY KEY, customer_id UUID NOT NULL REFERENCES customers(id), name VARCHAR(60) NOT NULL,
 target BIGINT NOT NULL CHECK(target > 0), saved BIGINT NOT NULL DEFAULT 0 CHECK(saved >= 0 AND saved <= target)
);
CREATE TABLE ledger_entries (
 id UUID PRIMARY KEY, customer_id UUID NOT NULL, account VARCHAR(10) NOT NULL, name VARCHAR(100) NOT NULL,
 category VARCHAR(30) NOT NULL, amount BIGINT NOT NULL CHECK(amount <> 0), operation_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), FOREIGN KEY(customer_id,account) REFERENCES accounts(customer_id,kind)
);
CREATE INDEX ledger_customer_date ON ledger_entries(customer_id,created_at DESC);
CREATE INDEX goals_customer ON goals(customer_id);
CREATE TABLE operations (
 customer_id UUID NOT NULL REFERENCES customers(id), idempotency_key UUID NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(customer_id,idempotency_key)
);
