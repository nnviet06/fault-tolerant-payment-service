-- Issue #2 secondary evidence: samples what every client backend of the
-- current database is doing, every :interval_ms, for :duration_s seconds
-- (time-bounded, so it can be made to end inside a load window), then prints
-- three summaries. Read-only towards the service's tables.
--
--   docker compose exec -T postgres psql -U user123 -d userdb \
--     -v duration_s=55 -v interval_ms=10 < tools/pg-wait-sampler.sql
--
-- Summaries:
--   1. chain-lock busy share  = share of samples in which SOME backend holds
--                               the advisory lock (the ledger chain lock is
--                               the only advisory lock the service takes)
--   2. wait distribution      = what in-transaction backends are doing
--   3. commit-flush overlap   = how many backends are flushing/awaiting WAL
--                               at the same instant (always <= 1 means
--                               commits are not being grouped)
-- Caveat: a 10 ms sampler sees short events (a ~1 ms fsync) only
-- probabilistically; read the shares as proportions, not durations.

\set QUIET on
\pset footer off
SET bench.duration_s = :duration_s;
SET bench.interval_ms = :interval_ms;

CREATE TEMP TABLE wait_samples (
    sample          int,
    pid             int,
    state           text,
    wait_event_type text,
    wait_event      text,
    holds_chain_lock boolean
);

DO $$
DECLARE
    t_end    timestamptz := clock_timestamp()
                            + make_interval(secs => current_setting('bench.duration_s')::int);
    pause    double precision := current_setting('bench.interval_ms')::int / 1000.0;
    i        int := 0;
BEGIN
    WHILE clock_timestamp() < t_end LOOP
        i := i + 1;
        -- pg_stat_activity is snapshotted per transaction; this DO block is one
        -- transaction, so the snapshot must be dropped every iteration.
        PERFORM pg_stat_clear_snapshot();
        INSERT INTO wait_samples
        SELECT i, a.pid, a.state, a.wait_event_type, a.wait_event,
               EXISTS (SELECT 1 FROM pg_locks l
                       WHERE l.pid = a.pid AND l.locktype = 'advisory' AND l.granted)
        FROM pg_stat_activity a
        WHERE a.backend_type = 'client backend'
          AND a.datname = current_database()
          AND a.pid <> pg_backend_pid();
        PERFORM pg_sleep(pause);
    END LOOP;
END
$$;

\echo '== 1. chain-lock busy share =='
SELECT count(DISTINCT sample)                                        AS samples,
       count(DISTINCT sample) FILTER (WHERE holds_chain_lock)        AS samples_lock_held,
       round(count(DISTINCT sample) FILTER (WHERE holds_chain_lock)::numeric
             / NULLIF(count(DISTINCT sample), 0), 3)                 AS busy_share
FROM wait_samples;

\echo '== 2. wait distribution of in-transaction backends =='
SELECT coalesce(wait_event_type || ':' || wait_event, 'CPU/running') AS activity,
       state,
       count(*)                                                      AS observations,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1)            AS pct
FROM wait_samples
WHERE state <> 'idle'
GROUP BY 1, 2
ORDER BY observations DESC;

\echo '== 3. commit-flush overlap (backends in WAL flush per sample) =='
WITH per_sample AS (
    SELECT s.sample,
           count(w.pid) FILTER (WHERE (w.wait_event_type = 'IO' AND w.wait_event IN ('WalSync', 'WalWrite'))
                                   OR (w.wait_event_type = 'LWLock' AND w.wait_event = 'WALWrite'))
               AS flushing,
           count(w.pid) FILTER (WHERE w.state <> 'idle') AS in_tx
    FROM (SELECT DISTINCT sample FROM wait_samples) s
    LEFT JOIN wait_samples w ON w.sample = s.sample
    GROUP BY s.sample
)
SELECT max(flushing)                                                  AS max_flushing,
       round(avg(flushing), 3)                                       AS avg_flushing,
       count(*) FILTER (WHERE flushing >= 2)                         AS samples_flushing_ge2,
       max(in_tx)                                                    AS max_in_tx,
       round(avg(in_tx), 2)                                          AS avg_in_tx
FROM per_sample;
