# Technical Design (kĩ thuật)

How the service is built and why each mechanism exists. For what it does in
domain terms, see [business-flows.md](business-flows.md).

Stack: Java 17 · Vert.x 4.5 + Mutiny bindings 3.19 · blocking JDBC (HikariCP)
on worker threads · PostgreSQL 17 · Redis 7 · Maven. Kafka is deferred behind
the `EventPublisher` interface.

## Failure-mode → mechanism map

This table is the core of the project. Each row is reproduced on demand and
proven by a named torture scenario (`mvn -Ptorture verify` →
`target/torture-report.md`).

| Failure mode | Defeated by | Proven by |
|---|---|---|
| Double-spend race (concurrent withdrawals/transfers) | `SELECT ... FOR UPDATE` wallet row lock; `CHECK (balance >= 0)` as last line | `double-spend-100-concurrent` |
| Deadlock on crossed transfers (A→B ∥ B→A) | Both wallets locked in deterministic UUID order | `transfer-deadlock-crossfire` |
| Retry duplicate (client retry, network dup, replay storm) | `idempotency_keys` PK claimed inside the money transaction + stored-response replay; unique `ledger_entries.idempotency_key` as an independent second line | `retry-storm` |
| Idempotency cache loss (Redis down/flushed) | Postgres is the correctness authority; Redis is a fast path whose failure costs only latency | `redis-blackout-retry-storm` |
| Crash between DB commit and event publish | Transactional outbox: event row inserted on the same connection/transaction as the ledger entry | `kill-after-commit-before-publish` |
| Crash between event publish and mark-as-sent | At-least-once relay (unmarked rows republish) + consumer dedup on `event_id` | `kill-after-publish-before-mark` |
| Hold committed and expired at the same time | Single compare-and-set: `UPDATE holds SET status=? WHERE id=? AND status='ACTIVE'` — exactly one winner | `commit-vs-expiry-race` |
| Out-of-band tampering with recorded history | SHA-256 hash chain over ledger rows; `GET /ledger/verify` recomputes every link | `tamper-evidence` |
| Silent drift between ledger and balances | Balances reproducible from the append-only ledger alone | `ledger-replay-audit` |
| Application bug violating money invariants | Schema `CHECK` constraints make invalid states unrepresentable (commit aborts) | `DebitCreditTaskTest` |

Fault tolerance is a property, not a package: it lives in the transaction
boundary (`common/db/Tx`), the workflows' locking and claiming order, the
outbox, and the schema constraints. There is deliberately no `faulttolerance/`
folder.

## Threading model

All HTTP and timer work runs on Vert.x event loops. All JDBC runs on a named
worker pool (`db-worker`, sized equal to the Hikari pool so a task never
queues waiting for a connection). `Tx.inTx(block)` is the single transaction
boundary: borrow connection → `autoCommit(false)` → run the block on a worker
→ commit, or roll back on any exception → return a `Uni`. Every step of a
workflow receives the same `Connection`, which is what makes wallet update +
ledger entry + outbox row + idempotency claim one atomic unit.

The reactive pg-client is deliberately NOT used: blocking JDBC keeps
transaction scope explicit and pins every fault-tolerance mechanism to one
readable place.

## Schema (5 tables)

Money columns are `BIGINT` minor units everywhere; no floating point exists in
the codebase.

- **wallets** — `id UUID`, `owner_name`, `balance`, `held_amount`,
  `created_at`. Constraints: `balance >= 0`, `held_amount >= 0`,
  `balance >= held_amount`. `available = balance − held_amount` is computed,
  never stored. `held_amount` is denormalized from holds but only mutated in
  the same transaction under the same row lock, so it cannot drift.
- **ledger_entries** — append-only; the application never issues UPDATE or
  DELETE against it. One row = one balanced movement: `debit_wallet_id` and
  `credit_wallet_id` on the same row (NULL side = external world, never both
  NULL). `seq BIGINT PK` is assigned `max(seq)+1` under an advisory lock —
  gap-free, unlike a sequence, because a rollback must not leave holes in the
  chain. `idempotency_key` is UNIQUE as a second, independent duplicate
  defense. `created_at_ms BIGINT` is app-assigned epoch millis so hash
  recomputation is byte-deterministic. `prev_hash`/`entry_hash` form the
  chain.
- **holds** — the one deliberately mutable money table (a reservation is
  state, not history): `status` in ACTIVE/COMMITTED/ROLLED_BACK/EXPIRED,
  `expires_at TIMESTAMPTZ`. All expiry decisions (insert TTL, commit guard,
  sweeper) use the **database clock** (`now()`), one clock authority.
- **outbox** — `event_id UUID UNIQUE` (the consumer dedup key), `payload
  JSONB`, `published_at NULL` = pending, `attempts` for observability.
- **idempotency_keys** — `idem_key TEXT PK`, `request_hash`, and the stored
  `response_status`/`response_body` replayed to retries. Response columns are
  nullable only because the row is claimed at transaction start and completed
  before the same commit — a committed row always carries its response.

Schema is applied at boot from `schema.sql` (idempotent `CREATE ... IF NOT
EXISTS`); migration tooling is out of scope by design.

## Concurrency: pessimistic row locks

Every movement locks the wallet row(s) first: `SELECT ... FOR UPDATE`.
Contention is naturally per-wallet, so this serializes exactly the operations
that conflict and nothing else — under a 100-way race, 99 requests wait and
then see the true balance.

- Rejected — optimistic versioning: a deliberate 100-concurrent burst
  degenerates into a retry storm (livelock risk, retry/backoff code) for zero
  correctness gain at this granularity.
- Rejected — SERIALIZABLE: correct but opaque; every transaction needs a
  global serialization-failure retry wrapper, and the mechanism is no longer
  pointable-at in code.
- Deadlock defense: operations touching two wallets (transfer, hold commit)
  lock both in UUID order, so crossed operations queue on the same first lock
  instead of cycling. The sweeper locks a single wallet per transaction and
  cannot form a cycle.
- Last line: the CHECK constraints reject invariant-violating commits even if
  every code-level check regressed.

## Idempotency (exactly-once application)

1. Handler requires `Idempotency-Key` on every money POST; the request hash is
   built from the parsed parameters (semantic canonicalization, so JSON key
   order does not matter).
2. Redis fast path: `GET idem:{key}` — a cached finished response returns
   immediately (`X-Idempotent-Replay: true`).
3. Otherwise, **inside the workflow's transaction**, first step:
   `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING`.
   - Claim won → run the workflow, attach the response to the row, commit.
   - Claim lost → Postgres blocked us until the winner committed (unique-
     index arbitration); read the stored response and return it. Different
     `request_hash` → 422 key reuse.
4. Crash pre-commit rolls back claim + movement together — a retry starts
   clean. There is no state where money moved but the key is unknown, or vice
   versa. Business failures (e.g. insufficient funds) also roll back the
   claim: only successful movements are recorded for replay, and re-attempting
   a failed request is safe because it failed atomically.
5. After commit: best-effort `SET idem:{key} EX <ttl>` into Redis.

### The Redis decision, argued

Redis holds **idempotency responses**, not balances. Idempotency responses are
immutable once written — no invalidation problem, perfectly cache-shaped — and
they absorb exactly the adversary traffic (retry storms). A balance cache
would add an invalidation-under-concurrency problem and a staleness failure
mode to a payment read path, to solve a load problem this service does not
have. Crucially, Redis-only idempotency would be indefensible: Redis
durability is best-effort, and a forgotten key means a double charge. So
correctness lives in Postgres and Redis degradation costs latency only —
`redis-blackout-retry-storm` proves the guarantee survives with Redis
unreachable. Key retention: Redis TTL is 24h; Postgres rows are kept
indefinitely (a retention sweeper is documented out of scope).

## Transactional outbox (crash dual-write)

`OutboxWriter.write(conn, ...)` inserts the event on the caller's connection —
the same transaction as the ledger entry. "Money moved but event lost" and
"event without movement" are unrepresentable.

`OutboxRelay` polls every 250ms (configurable): `SELECT ... WHERE published_at
IS NULL ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED` → publish each →
`published_at = now()` → commit. A crash anywhere in that window leaves rows
pending, so they republish after restart. Delivery is therefore
**at-least-once — exactly-once delivery is deliberately not claimed** —
and consumers deduplicate on `event_id`. `SKIP LOCKED` future-proofs multiple
relay instances (untested, single instance today).

`EventPublisher` is the pluggable port (Kafka later). Implementations:
`LoggingEventPublisher` (default) and `FileEventPublisher` (JSON-lines sink
the torture suite reads back).

## Holds and the TTL sweeper

A hold raises `held_amount` and writes **no ledger entry** — the ledger
records movements, and a reservation is not a movement. Commit is the
movement: release reservation + debit source + credit destination + one
`HOLD_COMMIT` entry, in one transaction. Rollback and expiry only release the
reservation. All settlements go through one CAS
(`UPDATE ... WHERE status = 'ACTIVE'` + expiry guard) under the wallet row
lock, so commit-vs-expiry races have exactly one winner. The sweeper runs
every second (configurable), settles each expired hold in its own small
transaction, and treats a lost CAS as a legitimate no-op. All hold lifecycle
changes emit outbox events.

## Hash chain (tamper evidence)

`entry_hash = SHA-256(seq | entry_type | debit | credit | amount | hold_id |
idempotency_key | created_at_ms | prev_hash)`; genesis `prev_hash` is 64
zeros. Editing any hashed column breaks recomputation at that seq.
`GET /ledger/verify` walks the whole chain and reports the first broken seq.

Appends are serialized by `pg_advisory_xact_lock` (transaction-scoped, taken
just before reading the tail). **Honest tradeoff:** a global chain has a
global append point — ledger writes serialize at the tail. Acceptable at this
scale; the scaling path (per-wallet chains, periodic signed checkpoints) is a
documented follow-up, not built. Scope note: the chain proves *tamper
evidence* (silent edits are detectable), not tamper *proofing* — an attacker
who can rewrite every subsequent hash defeats it; countering that requires
external hash anchoring, which is out of scope.

## Chaos points (test-only)

`Chaos.point(name)` calls `Runtime.halt(137)` — a hard kill, no shutdown
hooks — iff the env var `CHAOS_HALT_AT` equals the point name. Points:
`after-commit` (workflow transaction committed, response not yet sent) and
`relay-after-publish-before-mark`. Without the env var every point is a
no-op. This exists so the two crash-window scenarios are reproducible on
demand instead of being timing luck.

## HTTP surface

| Endpoint | Idempotency-Key | Notes |
|---|---|---|
| `POST /wallets` | not required | no money moves; a duplicate is a harmless empty wallet |
| `GET /wallets/:id` | — | balance, held, available |
| `POST /deposits`, `/withdrawals`, `/transfers` | required | 422 on insufficient available funds |
| `POST /holds` | required | `ttlSeconds` 1..86400 |
| `POST /holds/:id/commit`, `/holds/:id/rollback` | required | 409 when the hold is not active |
| `GET /ledger/entries?walletId=&limit=` | — | audit listing |
| `GET /ledger/verify` | — | full chain walk |
| `GET /health` | — | DB down → 503; Redis down → reported, still 200 |

Errors are `{"error":{"code","message"}}`; domain failures are typed
exceptions in `common/errors` mapped centrally (`ErrorMapper`).

## Structure conventions

- **Workflow** = orchestration of one business operation = exactly one DB
  transaction; **Task** = one small independently-testable step; tasks take a
  `Connection` and are composed by workflows in a strict order (locks →
  validate → mutate → ledger → outbox).
- Feature-first packages (`wallet/`, `ledger/`, `outbox/`); cross-cutting
  mechanisms under `common/`.
- Tests mirror the main package structure; torture scenarios live under
  `torture/scenarios` and are run by the `torture` Maven profile.

## Out of scope (deliberate)

Auth, frontend, multi-currency, fraud/risk, Kafka (interface only), migration
tooling, metrics/tracing, rate limiting, pagination, multi-instance HA,
idempotency-key retention sweeper, exactly-once delivery claims, partial hold
captures, fees.
