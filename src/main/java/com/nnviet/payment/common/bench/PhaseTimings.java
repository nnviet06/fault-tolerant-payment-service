package com.nnviet.payment.common.bench;

import io.vertx.core.json.JsonObject;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Env-gated per-phase timing of the transfer path (issue #2: locate the
 * throughput bottleneck). Measurement only - it never changes what a
 * transaction does.
 *
 * With BENCH_TIMING unset (every normal run) each call returns immediately
 * and the ThreadLocal is never touched.
 *
 * How a sample is built: a whole transaction runs on ONE db-worker thread
 * (blocking JDBC), so the timestamps of one transaction can live in a
 * per-thread array. Tx, TransferWorkflow and LedgerRepository drop marks into
 * it; after a successful commit the marks become one sample. A sample is kept
 * only when EVERY mark is set - in practice only transfers (the only path
 * that marks the wallet-lock phase), never relay/sweeper/health transactions.
 * The marks are cleared after every transaction, committed or not.
 */
public final class PhaseTimings {

    /** Timestamps dropped by the instrumented code, in transaction order. */
    public enum Mark {
        SUBMIT,       // inTx called (caller thread, before executeBlocking)
        START,        // worker thread starts running the transaction
        CONN_START,   // before getConnection()
        CONN_END,     // connection in hand
        ROW_START,    // before locking the two wallet rows (FOR UPDATE)
        ROW_END,      // both wallet rows locked
        LOCK_START,   // before pg_advisory_xact_lock
        LOCK_END,     // chain lock acquired
        COMMIT_START, // before conn.commit()
        COMMIT_END    // commit returned (chain lock released)
    }

    /** Derived durations, each the difference of two marks. */
    public enum Phase {
        W_queue(Mark.SUBMIT, Mark.START),
        W_conn(Mark.CONN_START, Mark.CONN_END),
        W_row(Mark.ROW_START, Mark.ROW_END),
        W_lock(Mark.LOCK_START, Mark.LOCK_END),
        H_work(Mark.LOCK_END, Mark.COMMIT_START),
        H_commit(Mark.COMMIT_START, Mark.COMMIT_END),
        H(Mark.LOCK_END, Mark.COMMIT_END); // chain lock hold = H_work + H_commit

        final Mark from;
        final Mark to;

        Phase(Mark from, Mark to) {
            this.from = from;
            this.to = to;
        }
    }

    private static final long UNSET = Long.MIN_VALUE;
    private static final int MARKS = Mark.values().length;
    private static final int PHASES = Phase.values().length;
    private static final int DEFAULT_CAPACITY = 200_000;

    private static final PhaseTimings GLOBAL = new PhaseTimings(
            "true".equalsIgnoreCase(System.getenv("BENCH_TIMING")),
            DEFAULT_CAPACITY, System::nanoTime);

    public static PhaseTimings global() {
        return GLOBAL;
    }

    private final boolean enabled;
    private final int capacity;
    private final LongSupplier clock;
    private final ThreadLocal<long[]> marks;

    // samples[i * PHASES + phase.ordinal()] = duration in nanoseconds
    private final long[] samples;
    private int count;
    private long dropped;
    private long resetAt;

    public PhaseTimings(boolean enabled, int capacity, LongSupplier clock) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.clock = clock;
        this.marks = ThreadLocal.withInitial(PhaseTimings::emptyMarks);
        this.samples = enabled ? new long[capacity * PHASES] : new long[0];
        this.resetAt = enabled ? clock.getAsLong() : 0;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Timestamp for SUBMIT, taken on the caller thread; 0 when disabled. */
    public long now() {
        return enabled ? clock.getAsLong() : 0;
    }

    /** Worker thread starts a transaction that was submitted at submittedAt. */
    public void begin(long submittedAt) {
        if (!enabled) {
            return;
        }
        long[] m = marks.get();
        Arrays.fill(m, UNSET);
        m[Mark.SUBMIT.ordinal()] = submittedAt;
        m[Mark.START.ordinal()] = clock.getAsLong();
    }

    public void mark(Mark mark) {
        if (!enabled) {
            return;
        }
        marks.get()[mark.ordinal()] = clock.getAsLong();
    }

    /** Called after a successful commit: keeps the sample if every mark is set. */
    public void recordIfComplete() {
        if (!enabled) {
            return;
        }
        long[] m = marks.get();
        for (long t : m) {
            if (t == UNSET) {
                return;
            }
        }
        long[] durations = new long[PHASES];
        for (Phase p : Phase.values()) {
            durations[p.ordinal()] = m[p.to.ordinal()] - m[p.from.ordinal()];
        }
        store(durations);
    }

    /** Called after every transaction (commit, rollback or failure). */
    public void clear() {
        if (!enabled) {
            return;
        }
        Arrays.fill(marks.get(), UNSET);
    }

    // Synchronized: at a few hundred samples/sec the monitor is uncontended,
    // and it runs after commit, i.e. outside the chain-lock hold being measured.
    private synchronized void store(long[] durations) {
        if (count >= capacity) {
            dropped++;
            return;
        }
        System.arraycopy(durations, 0, samples, count * PHASES, PHASES);
        count++;
    }

    public synchronized void reset() {
        count = 0;
        dropped = 0;
        resetAt = clock.getAsLong();
    }

    /**
     * Summary since the last reset: per phase mean / p50 / p95 / p99 / max in
     * microseconds, plus the sample count and the wall-clock window length.
     */
    public synchronized JsonObject snapshot() {
        JsonObject json = new JsonObject()
                .put("enabled", enabled)
                .put("samples", count)
                .put("dropped", dropped)
                .put("windowSeconds", enabled ? (clock.getAsLong() - resetAt) / 1e9 : 0.0);
        JsonObject phases = new JsonObject();
        for (Phase p : Phase.values()) {
            long[] column = new long[count];
            long sum = 0;
            for (int i = 0; i < count; i++) {
                column[i] = samples[i * PHASES + p.ordinal()];
                sum += column[i];
            }
            Arrays.sort(column);
            JsonObject stats = new JsonObject()
                    .put("mean_us", count == 0 ? 0.0 : round(sum / (double) count / 1000.0))
                    .put("p50_us", micros(percentile(column, 50)))
                    .put("p95_us", micros(percentile(column, 95)))
                    .put("p99_us", micros(percentile(column, 99)))
                    .put("max_us", micros(count == 0 ? 0 : column[count - 1]))
                    .put("sum_ms", round(sum / 1e6));
            phases.put(p.name(), stats);
        }
        return json.put("phases", phases);
    }

    /** Nearest-rank percentile of an ascending array; 0 when empty. */
    static long percentile(long[] sorted, int pct) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(pct / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    private static double micros(long nanos) {
        return round(nanos / 1000.0);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static long[] emptyMarks() {
        long[] m = new long[MARKS];
        Arrays.fill(m, UNSET);
        return m;
    }
}
