# Changelog

Notable changes, newest first. Housekeeping commits (README edits, templates,
gitignore) are left out.

## Planned

- (none yet)

## 11/2026 — Automated testing suite (#1)

- Unit tests and all 9 torture scenarios run automatically on every push and
  pull request.

## 11/2026 — Per-wallet hash chains (#4)

- Each wallet now has its own hash chain, replacing the single global chain and
  its global lock. Measured throughput went from ~230 to ~940 transfers/sec
  with 1000 wallets, and from ~230 to ~380 with 20 wallets.
- The database schema changed. Local databases must be recreated.
- The benchmark runner can now alternate between versions that use different
  schemas.

## 11/2026 — Throughput bottleneck found (#2)

- Added optional per-step timers (`BENCH_TIMING=true`), database sampling, and
  a benchmark runner with a results summary.
- Found the limit: the global ledger lock stayed held while each commit waited
  for the disk, so commits ran one at a time.

## 07/2026 — Benchmark report

- Added load and crash-recovery benchmark tools, and a report that checks the
  project's claims against measurements.

## 07/2026 — Baseline

- Wallets, deposits, withdrawals, transfers and holds on an append-only,
  hash-chained ledger.
- Idempotent money operations (Postgres is the authority, Redis is a cache) and
  a transactional outbox.
- 9 torture scenarios that crash, race and tamper with the running service.

## 05/2026 — Project setup

- Maven project with Vert.x, Mutiny and Logback; Docker Compose for Postgres
  and Redis.
