# Benchmark & Claim-Verification Report

Purpose: back every number and factual claim in the résumé bullet set with real,
reproducible evidence traceable to a committed file. Numbers that could not be
measured are marked **UNMEASURED** with a reason. No number in this document was
estimated.

- Repository: `fault-tolerant-payment-service`
- Git revision at measurement time: `2477d85` (branch `main`)
- Date generated: 2026-07-19

## Machine spec

| field | value |
|---|---|
| CPU | Intel Core i7-11800H @ 2.30 GHz, 8 cores / 16 threads |
| RAM | 7.7 GB |
| OS | Windows 10 Home Single Language, build 10.0.19045 |
| Topology | single node (service + Postgres + Redis all on one laptop) |
| JVM (runtime) | Temurin OpenJDK 21.0.10 (bytecode target: `release 17`, `pom.xml:14`) |
| Postgres | `postgres:17-alpine` (docker compose) |
| Redis | `redis:7-alpine` (docker compose) |

All measurements were taken sequentially with nothing else heavy running.

## Prerequisites for every reproduce command below

```bash
docker compose up -d                 # Postgres :5432, Redis :6379
mvn -DskipTests package              # builds target/fault-tolerant-payment-service-0.1.0-SNAPSHOT.jar
```

---

## Phase A — claim verification

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Ledger is double-entry (single balanced row carrying debit + credit wallet) | **CONFIRMED** (single-row variant, not classic two-row) | `schema.sql:40-58`: one `ledger_entries` row holds both `debit_wallet_id` and `credit_wallet_id`; `CHECK (debit_wallet_id IS NOT NULL OR credit_wallet_id IS NOT NULL)` (`schema.sql:56-57`) — a NULL side = external world (deposit/withdrawal), never both. Append happens once per movement: `TransferWorkflow.java:59-61`. See note below. |
| 2 | Idempotency claim + ledger entry + outbox event committed in ONE transaction (same Connection, single commit) | **CONFIRMED** | Traced below. |
| 3 | Torture suite has exactly 9 scenarios | **CONFIRMED** | 9 `*TortureIT` classes (named below); live run: `Tests run: 9, Failures: 0` (2026-07-19). |
| 4 | Scenarios use JUnit | **CONFIRMED** | `junit-jupiter` via `junit-bom` **5.11.0** — `pom.xml:26-31` (version at `:28`), `pom.xml:87-90`. |
| 5 | Harness runs the packaged service as a real subprocess and kills it mid-transaction | **CONFIRMED** | `ServiceProcess.java:44` `new ProcessBuilder(javaBin, "-jar", jar)`; hard kill = `Chaos.java:32` `Runtime.getRuntime().halt(137)` (no shutdown hooks); external kill = `ServiceProcess.java:95` `destroyForcibly()`. Kill point is *after DB commit, before HTTP response* (`IdempotencyService.java:79-82`). |
| 6 | A scenario runs with Redis fully unavailable and asserts correctness | **CONFIRMED** | `RedisBlackoutTortureIT` starts the service with `REDIS_PORT=1` (unreachable), asserts `health.redis == DOWN`, then runs the full retry storm. Live result: redis DOWN, 100 replays → 1 application, balance 5000, key-reuse → 422. |
| 7 | The suite emits a reproducible pass/fail report | **CONFIRMED** (path caveat) | `TortureReport.java:56-92` renders Markdown with per-scenario `PASS/FAIL` and an `N/total` line. **Actual output path when run via `mvn -Ptorture verify` is `target/target/torture-report.md`**, not the `target/torture-report.md` documented in CLAUDE.md/README (Failsafe's forked JVM runs with `target/` as its working directory; the code writes a relative `Path.of("target","torture-report.md")` — `TortureReport.java:34`). See Suggestions. |
| 8 | Docker is actually used | **CONFIRMED** | `docker-compose.yml`: services `postgres` (`postgres:17-alpine`) and `redis` (`redis:7-alpine`). |
| 9 | Invariants asserted across the suite ("0 invariant violations") | **CONFIRMED** | Full list below; all 9 scenarios PASS in the 2026-07-19 run. |

### Note on claim 1 (interview-honest phrasing)

This is **not** classic two-row double-entry (a separate debit row + credit row).
It is a **single balanced row** carrying both sides. The project documents this
deliberately (CLAUDE.md, `technical-design.md`): a single row makes an unbalanced
movement *unrepresentable by construction*, whereas a two-row scheme allows a bug
to write a mismatched pair. If the résumé says "double-entry," be ready to say
"single balanced-row double-entry" — the claim is defensible but the nuance is
real. Conservation is proven empirically by `ledger-replay-audit`
(`sum(balances) == deposits − withdrawals == 100000`).

### Claim 2 — the single-transaction boundary, traced

The load-bearing claim. One HTTP money operation = one JDBC `Connection` = one
commit, carrying the idempotency claim, the movement, the ledger entry, and the
outbox row together:

1. `LedgerHandler.depositHandler` (`LedgerHandler.java:72-81`) calls
   `idempotency.execute(key, hash, 201, conn -> deposit.run(conn, ...))`.
2. `IdempotencyService.executeAgainstPostgres` (`IdempotencyService.java:56-77`)
   opens **one** transaction: `tx.inTx(conn -> { ... })` (`:59`) and on that single
   `conn`:
   - `repository.claim(conn, ...)` — the idempotency claim (`:60`),
   - `work.run(conn)` — the workflow (`:75`),
   - `repository.storeResponse(conn, ...)` — the stored replay response (`:76`).
3. Inside the workflow, the movement, the ledger append, and the outbox insert all
   use that same `conn`: e.g. `TransferWorkflow.java:57-61`
   (`debit.debit(conn,...)`, `credit.credit(conn,...)`,
   `appendEntry.append(conn,...)`, `outbox.write(conn,...)`).
   `OutboxWriter.write` inserts on the **caller's** Connection (`OutboxWriter.java:21-29`).
4. `Tx.runBlocking` (`Tx.java:50-64`) is the only commit point: `conn.commit()`
   at `Tx.java:55`; any exception → `rollback` (`:58`). There is exactly one
   `commit()` for the whole unit.

Empirical proof: `kill-after-commit-before-publish` hard-kills the process
*between commit and publish* and observes, after crash, `ledger entries = 1`,
`outbox pending = 1`, `events published before crash = 0` — i.e. the ledger entry
and its outbox row committed atomically, nothing leaked out early.

### Claim 3 — the 9 scenarios (names)

1. `transfer-deadlock-crossfire` (`DeadlockCrossfireTortureIT`)
2. `double-spend-100-concurrent` (`DoubleSpendTortureIT`)
3. `commit-vs-expiry-race` (`HoldExpiryRaceTortureIT`)
4. `kill-after-commit-before-publish` (`KillAfterCommitTortureIT`)
5. `kill-after-publish-before-mark` (`KillAfterPublishTortureIT`)
6. `ledger-replay-audit` (`LedgerReplayAuditTortureIT`)
7. `redis-blackout-retry-storm` (`RedisBlackoutTortureIT`)
8. `retry-storm` (`RetryStormTortureIT`)
9. `tamper-evidence` (`TamperEvidenceTortureIT`)

### Claim 9 — invariants proven (backing "0 invariant violations")

Each was asserted and held in the 2026-07-19 run (values from
`target/target/torture-report.md`):

| Invariant | Proven by | Observed |
|---|---|---|
| Money conservation (double-spend) | double-spend | final balance 0; ledger WITHDRAWALs == successes == 10 |
| No overdraft under N-way race | double-spend | 10 of 100 succeed, 90 rejected (422), 0 other, no negative balance (`CHECK balance>=0`) |
| Conservation under crossed transfers (no deadlock loss) | deadlock-crossfire | 100/100 succeed, 0 server errors, A+B == 100000 |
| Exactly-once under retry storm | retry-storm | 100 replays → 1 application, 1 ledger entry, 1 idem row, balance 5000, 99 replays |
| Exactly-once under crash + client retry | kill-after-commit | after retry: 1 ledger entry, `X-Idempotent-Replay: true`, balance 7000 |
| No event lost across crash | kill-after-commit | pending outbox published after restart; 1 distinct event id |
| At-least-once + consumer dedup | kill-after-publish | raw sink = 2, distinct event ids = 1, money applied once |
| Single CAS winner per hold | commit-vs-expiry | committed + expired == 20; ledger commits == commit wins; held_amount == 0 |
| Balance reproducible from ledger | ledger-replay-audit | 4/4 wallets replay; total 100000 == deposits − withdrawals |
| Hash-chain tamper detection | tamper-evidence | valid before; after out-of-band `UPDATE` at seq 3 → invalid, `firstBrokenSeq = 3` |
| Correctness without Redis | redis-blackout | redis DOWN, still exactly-once; key reuse → 422 |

**Reproduce (Phase A dynamic parts + Phase B/9 numbers):**

```bash
mvn -Ptorture verify
# then read the report (note the nested path):
cat target/target/torture-report.md
```

Live run 2026-07-19: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` →
**9/9 scenarios passed** (`target/target/torture-report.md`, git rev `2477d85`).

---

## Phase B — config-derived numbers

| Number | Value | Source |
|---|---|---|
| **[N]** double-spend concurrency | **100** | `DoubleSpendTortureIT.java:39` (`runConcurrently(100, ...)`) |
| Deadlock-crossfire concurrency (interview ammo) | 100 (50× A→B + 50× B→A) | `DeadlockCrossfireTortureIT.java:42` |
| Retry-storm request count (interview ammo) | 100 (50 concurrent + 50 sequential) | `RetryStormTortureIT.java:62` (concurrent 50) and `:64` (sequential 50 loop) |
| Hold-expiry race count (interview ammo) | 20 holds, 2 s TTL | `HoldExpiryRaceTortureIT.java` (`HOLDS = 20`) |
| Crash-kill count per crash scenario (interview ammo) | 1 hard kill each | `KillAfterCommitTortureIT` (`CHAOS_HALT_AT=after-commit`), `KillAfterPublishTortureIT` (`CHAOS_HALT_AT=relay-after-publish-before-mark`) |
| Outbox relay poll interval ("~250 ms") | **250 ms** default | `AppConfig.java:37` (`envInt("RELAY_INTERVAL_MS", 250)`) |

The résumé's "[N]-way double-spend" = **100-way**.

---

## Phase C — measurements

Pre-registered definitions, fixed before running. Only these two metrics.

### [X] — recovery median (kill-after-commit)

**Definition (pre-registered):** time from *service-ready-after-restart*
(`/health` returns 200) to the *previously pending outbox event being published*
(the event sink first becomes non-empty), in the kill-after-commit window.
`X = t1 − t0`.

**Method:** `tools/RecoveryBench.java` — a standalone, JDK-only tool (no production
code touched; all timing lives in the tool). Each iteration:

- *Phase 1:* start the shaded jar with `CHAOS_HALT_AT=after-commit`,
  `PUBLISHER=file`, and the relay effectively disabled (`RELAY_INTERVAL_MS=3600000`);
  POST a deposit; the process hard-halts right after the DB commit, leaving the
  outbox row committed-but-unpublished.
- *Phase 2:* restart clean with `RELAY_INTERVAL_MS=250` (the production default,
  Phase B); record `t0` = first `/health==200` (polled every 5 ms), then
  `t1` = sink first non-empty (polled every 2 ms).

No DB truncation is needed: each iteration uses its own sink file, idempotency key,
and wallet, and a per-run nonce keeps reruns collision-free. (During the first
attempt, 2 iterations reused idempotency keys committed by a smoke run and
**replayed** the stored response — which correctly skips the chaos halt
(`IdempotencyService.java:79`); the per-run nonce fixed it. This is the same
recycled-key contamination the real harness avoids by flushing between scenarios.)

**Raw samples (ms), n = 20, relay = 250 ms:**

```
1.1, 52.7, 51.0, 3.0, 50.2, 27.7, 29.8, 34.8, 41.0, 1.5,
31.2, 1.6, 36.1, 36.7, 31.4, 26.1, 29.6, 27.5, 33.3, 28.7
```

| stat | value |
|---|---|
| **median** | **30.5 ms** |
| min | 1.1 ms |
| max | 52.7 ms |
| iterations (all succeeded) | 20 / 20 |

**Honest interpretation (important):** X is small and **poll-influenced**, not a
measure of any fast "recovery machinery." Because the relay starts at roughly the
same instant the service reports healthy, and because `t0` is defined as
service-ready, X measures only the *residual* gap between health-ready and the
relay's publish. When the relay's first poll fires before the service is deemed
healthy, X collapses toward ~0 (hence the 1–3 ms samples). The résumé-honest
reading is: **"a pending event is delivered within ~30 ms (median) of the service
coming back up."** The dominant tunable is the 250 ms poll interval, not code speed.

**Reproduce:**

```bash
javac -d target/bench tools/RecoveryBench.java
java -cp target/bench RecoveryBench \
  target/fault-tolerant-payment-service-0.1.0-SNAPSHOT.jar 20 8190 250
# args: <jar> [iterations=20] [port=8190] [relayIntervalMs=250]
```

### [T] — sustained committed transfers/sec

**Definition (pre-registered):** committed transfers/sec (HTTP 201) through the
full atomic boundary (HTTP in → transaction committed), over a sustained window
after warm-up. Only 201 responses count as committed.

**Method:** `tools/LoadDriver.java` — standalone, JDK-only. Seeds 20 wallets at
1e12 minor units each, then for each client level runs a 10 s warm-up (discarded)
followed by a 30 s measured window; each client thread issues `/transfers` between
two distinct random wallets with a unique idempotency key. Non-201 responses are
counted and categorised alongside the throughput. Levels run **sequentially with a
10 s cooldown between them** so the DB pool drains and levels don't contaminate.

**Client concurrency (pre-registered = 32, swept 16/32/64 to show saturation).**
Justification: DB pool = 16 (`AppConfig.java:32`) and the global
`pg_advisory_xact_lock` serialises **every** ledger append
(`LedgerRepository.java:29,38,131-136`), so throughput is bounded by serial-append
latency, not client parallelism. 32 (2× pool) keeps the pool saturated and the
single append point continuously fed; beyond that, more clients only add queueing.

**Results (30 s window each):**

| clients | window s | committed (201) | throughput tps | non-201 total | breakdown |
|---|---|---|---|---|---|
| 16 | 30.00 | 6427 | 214.2 | 0 | none |
| **32** | **30.01** | **7063** | **235.3** | **0** | **none** |
| 64 | 30.01 | 6814 | 227.1 | 0 | none |

**Headline [T] = 235.3 committed transfers/sec** at 32 concurrent clients, **0
errors / 0 timeouts** in the measured window. The curve peaks at 32 and dips at 64
— confirming 32 is at/near saturation and that the advisory chain lock, not client
count, is the bottleneck (~235 tps ≈ ~4.2 ms per fully-serialised, fully-durable
transfer on this laptop). Error rate = **0%** at every level.

**Reproduce:**

```bash
# terminal 1 — start the service, wait for {"status":"UP"}:
java -jar target/fault-tolerant-payment-service-0.1.0-SNAPSHOT.jar
#   (poll http://localhost:8080/health until 200)

# terminal 2 — run the sweep:
javac -d target/bench tools/LoadDriver.java
java -cp target/bench LoadDriver http://localhost:8080 16,32,64 10 30 10 20
# args: <baseUrl> <levelsCSV> <warmupSec> <measureSec> <cooldownSec> <wallets>
```

---

## Résumé numbers, filled in

| Placeholder | Value | Traceable to |
|---|---|---|
| **[N]** (double-spend race) | **100** | `DoubleSpendTortureIT.java:39` |
| **[X]** (recovery median) | **30.5 ms** (min 1.1 / max 52.7, n=20, relay 250 ms) | `tools/RecoveryBench.java` + samples above |
| **[T]** (sustained transfers/sec) | **235.3 tps** @ 32 clients, 0 errors | `tools/LoadDriver.java` + table above |

---

## Suggestions (observations only — not acted on)

1. **Report path bug (claim 7).** `mvn -Ptorture verify` writes the report to
   `target/target/torture-report.md`, but CLAUDE.md, README, and `pom.xml:146`
   all say `target/torture-report.md`. Cause: Failsafe forks with `target/` as the
   working directory, and `TortureReport.java:34` uses a relative path. Anyone
   following the docs will not find the file where promised. Low effort to fix
   (resolve against an absolute base dir, or set Failsafe `workingDirectory`), and
   it directly supports the "reproducible report" selling point.

2. **[T] is stronger stated with its error rate and saturation shape.** The current
   résumé bullet 2 mentions throughput but not that it holds at **0% errors** and
   that the system is deliberately serial-append-bound. "≈235 committed
   transfers/sec at 0 errors, throughput bounded by a single globally-ordered
   append point (a documented tradeoff of the hash chain)" is a more defensible and
   more interesting claim than a bare number.

3. **[X] is weaker than it looks and should be framed carefully.** ~30 ms median
   sounds impressive but is really "≤ one 250 ms relay poll, floored by
   health-readiness timing." Recommend phrasing as *"recovers a pending event
   within ~30 ms of restart (poll-bounded)"* rather than implying sub-frame
   recovery logic. Do not quote it without the "poll-bounded" qualifier.

4. **Double-spend result understates the guarantee.** The bullet says "surviving
   N-way double-spend"; the code proves the *stronger* property that **exactly the
   affordable number succeed** (10 of 100) with money conserved to 0 and ledger
   entries == successes — i.e. not just "no overdraft" but "precisely correct under
   contention." Worth a sentence in an interview.

5. **Tamper-evidence and replay-audit are unused résumé ammo.** The SHA-256 hash
   chain detecting an out-of-band DBA `UPDATE` at the exact `seq`, and full balance
   reconstruction from the append-only ledger, are among the strongest scenarios
   and are not reflected in the three bullets at all.
```
