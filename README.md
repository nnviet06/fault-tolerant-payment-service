# fault-tolerant-payment-service

A payment ledger service whose selling point is **proven fault tolerance**:
three classic ways payment systems lose money are reproduced on demand and
defeated by a specific, testable mechanism.

| Adversary | Defeated by | Proven by |
|---|---|---|
| **Double-spend race** — concurrent withdrawals driving a balance negative | `SELECT ... FOR UPDATE` row locks + `CHECK` constraints | `double-spend-100-concurrent` |
| **Retry duplicate** — the same request replayed must apply exactly once | Idempotency keys claimed inside the money transaction (Postgres authority, Redis fast path) | `retry-storm`, `redis-blackout-retry-storm` |
| **Crash dual-write** — a crash between DB commit and event publish | Transactional outbox + at-least-once relay + consumer dedup | `kill-after-commit-before-publish`, `kill-after-publish-before-mark` |

Plus: hold/commit/rollback with TTL auto-expiry, an append-only double-entry
ledger with a SHA-256 hash chain (tamper evidence), and a replay audit that
rebuilds every balance from the ledger alone.

Stack: Java 17 · Vert.x + Mutiny · blocking JDBC on worker threads ·
PostgreSQL 17 · Redis 7.

## Run

```bash
docker compose up -d          # Postgres :5432, Redis :6379
mvn package
java -jar target/fault-tolerant-payment-service-0.1.0-SNAPSHOT.jar   # :8080
```

Smoke: `POST /wallets` → `POST /deposits` (with an `Idempotency-Key` header) →
`GET /wallets/:id` → `GET /ledger/verify`.

## Torture suite

```bash
docker compose up -d
mvn -Ptorture verify          # 9 named attack scenarios, real process kills
```

Report: `target/torture-report.md` — pass/fail + measured numbers per
scenario. Values the suite cannot observe are marked UNMEASURED, never
invented.

## Docs

- [docs/business-flows.md](docs/business-flows.md) — what it does, in domain
  terms (no implementation detail)
- [docs/technical-design.md](docs/technical-design.md) — schema, transactions,
  locking, outbox, hash chain, and the failure-mode → mechanism map
