-- Applied at every boot (CREATE ... IF NOT EXISTS); no migration tooling.
-- CHECK constraints are the last line of defense: bad states fail even if the code is buggy.

CREATE TABLE IF NOT EXISTS wallets (
    id          UUID PRIMARY KEY,
    owner_name  TEXT NOT NULL,
    balance     BIGINT NOT NULL DEFAULT 0,
    held_amount BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Head of this wallet's hash chain (entry count, newest hash). Written only by
    -- LedgerRepository.append, under this row's lock.
    chain_seq   BIGINT NOT NULL DEFAULT 0,
    chain_head  TEXT NOT NULL DEFAULT repeat('0', 64),
    CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT wallets_held_non_negative    CHECK (held_amount >= 0),
    CONSTRAINT wallets_held_within_balance  CHECK (balance >= held_amount)
);

-- Status changes only by compare-and-set from ACTIVE, so commit/rollback/expiry have one winner.
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

-- Append-only. One row = one balanced movement, debit and credit side together.
-- Each side is that wallet's next chain link: <side>_wallet_seq = position, <side>_prev_hash =
-- previous link's hash. seq is only a row id (gaps allowed), not hashed, not a commit order.
CREATE TABLE IF NOT EXISTS ledger_entries (
    seq               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_type        TEXT NOT NULL,
    debit_wallet_id   UUID REFERENCES wallets(id),
    debit_wallet_seq  BIGINT,
    debit_prev_hash   TEXT,
    credit_wallet_id  UUID REFERENCES wallets(id),
    credit_wallet_seq BIGINT,
    credit_prev_hash  TEXT,
    amount            BIGINT NOT NULL,
    hold_id           UUID REFERENCES holds(id),
    idempotency_key   TEXT UNIQUE,
    created_at_ms     BIGINT NOT NULL,
    entry_hash        TEXT NOT NULL,
    CONSTRAINT ledger_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_type_valid
        CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'HOLD_COMMIT')),
    -- NULL side = the outside world (deposit, withdrawal), never both
    CONSTRAINT ledger_has_a_side
        CHECK (debit_wallet_id IS NOT NULL OR credit_wallet_id IS NOT NULL),
    -- a row cannot sit twice in the same wallet's chain
    CONSTRAINT ledger_sides_distinct
        CHECK (debit_wallet_id IS DISTINCT FROM credit_wallet_id),
    -- a side is either fully absent or fully linked
    CONSTRAINT ledger_debit_side_complete
        CHECK ((debit_wallet_id IS NULL) = (debit_wallet_seq IS NULL)
           AND (debit_wallet_id IS NULL) = (debit_prev_hash IS NULL)),
    CONSTRAINT ledger_credit_side_complete
        CHECK ((credit_wallet_id IS NULL) = (credit_wallet_seq IS NULL)
           AND (credit_wallet_id IS NULL) = (credit_prev_hash IS NULL))
);
-- For per-wallet listing and chain walks. Each covers one side only; the CAS on
-- wallets.chain_seq stops a debit and a credit link taking the same position.
CREATE UNIQUE INDEX IF NOT EXISTS ledger_debit_chain_idx
    ON ledger_entries (debit_wallet_id, debit_wallet_seq);
CREATE UNIQUE INDEX IF NOT EXISTS ledger_credit_chain_idx
    ON ledger_entries (credit_wallet_id, credit_wallet_seq);

-- Inserted in the same transaction as the ledger entry, then published by the relay.
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

-- Idempotency source of truth (Redis only caches it). The primary key makes a racing
-- duplicate INSERT wait until the first transaction ends.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idem_key        TEXT PRIMARY KEY,
    request_hash    TEXT NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
