-- Schema for fault-tolerant-payment-service.
-- Applied idempotently at every boot (CREATE ... IF NOT EXISTS). No migration
-- tooling by design (see docs/technical-design.md, "out of scope").
--
-- CHECK constraints are the last line of defense: they make invariant-violating
-- states unrepresentable even if application code is buggy.

CREATE TABLE IF NOT EXISTS wallets (
    id          UUID PRIMARY KEY,
    owner_name  TEXT NOT NULL,
    balance     BIGINT NOT NULL DEFAULT 0,
    held_amount BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT wallets_held_non_negative    CHECK (held_amount >= 0),
    CONSTRAINT wallets_held_within_balance  CHECK (balance >= held_amount)
);

-- Mutable reservation state. Status changes ONLY through an atomic
-- compare-and-set (UPDATE ... WHERE status = 'ACTIVE'), so racing
-- commit/rollback/expiry have exactly one winner.
CREATE TABLE IF NOT EXISTS holds (
    id         UUID PRIMARY KEY,
    wallet_id  UUID NOT NULL REFERENCES wallets(id),
    amount     BIGINT NOT NULL,
    status     TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ,
    CONSTRAINT holds_amount_positive CHECK (amount > 0),
    CONSTRAINT holds_status_valid
        CHECK (status IN ('ACTIVE', 'COMMITTED', 'ROLLED_BACK', 'EXPIRED'))
);
CREATE INDEX IF NOT EXISTS holds_active_expiry_idx
    ON holds (expires_at) WHERE status = 'ACTIVE';

-- Append-only: application code never issues UPDATE or DELETE on this table.
-- One row = one balanced movement (the row carries both the debit and the
-- credit side, so an unbalanced movement is unrepresentable).
CREATE TABLE IF NOT EXISTS ledger_entries (
    seq              BIGINT PRIMARY KEY,
    entry_type       TEXT NOT NULL,
    debit_wallet_id  UUID REFERENCES wallets(id),
    credit_wallet_id UUID REFERENCES wallets(id),
    amount           BIGINT NOT NULL,
    hold_id          UUID REFERENCES holds(id),
    idempotency_key  TEXT UNIQUE,
    created_at_ms    BIGINT NOT NULL,
    prev_hash        TEXT NOT NULL,
    entry_hash       TEXT NOT NULL,
    CONSTRAINT ledger_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_type_valid
        CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'HOLD_COMMIT')),
    -- NULL side = the external world (deposit has no internal source,
    -- withdrawal has no internal destination) - but never both.
    CONSTRAINT ledger_has_a_side
        CHECK (debit_wallet_id IS NOT NULL OR credit_wallet_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ledger_debit_wallet_idx  ON ledger_entries (debit_wallet_id);
CREATE INDEX IF NOT EXISTS ledger_credit_wallet_idx ON ledger_entries (credit_wallet_id);

-- Transactional outbox: rows are inserted on the SAME connection/transaction
-- as the ledger entry they describe, then published by the relay.
CREATE TABLE IF NOT EXISTS outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID NOT NULL UNIQUE,
    event_type   TEXT NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS outbox_pending_idx
    ON outbox (id) WHERE published_at IS NULL;

-- Durable idempotency store (Redis is only a fast-path cache in front of this).
-- The PRIMARY KEY's unique-index arbitration is what serializes racing
-- duplicates: the second INSERT blocks until the first transaction commits.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idem_key        TEXT PRIMARY KEY,
    request_hash    TEXT NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
