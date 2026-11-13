package com.nnviet.payment.common.bench;

import com.nnviet.payment.common.bench.PhaseTimings.Mark;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PhaseTimingsTest {

    /** Fake clock: every read advances by the next scripted step (nanoseconds). */
    private static final class StepClock {
        final AtomicLong now = new AtomicLong(1_000_000);
        long step = 1_000;

        long read() {
            return now.getAndAdd(step);
        }
    }

    /** Drops every mark in transaction order; each mark is 1 µs after the previous. */
    private static void fullTransfer(PhaseTimings t) {
        t.begin(t.now());                 // SUBMIT, START
        t.mark(Mark.CONN_START);
        t.mark(Mark.CONN_END);
        t.mark(Mark.ROW_START);
        t.mark(Mark.ROW_END);
        t.mark(Mark.LOCK_START);
        t.mark(Mark.LOCK_END);
        t.mark(Mark.COMMIT_START);
        t.mark(Mark.COMMIT_END);
        t.recordIfComplete();
        t.clear();
    }

    private static double stat(JsonObject snap, String phase, String stat) {
        return snap.getJsonObject("phases").getJsonObject(phase).getDouble(stat);
    }

    @Test
    void disabledRecordsNothing() {
        PhaseTimings t = new PhaseTimings(false, 10, System::nanoTime);
        fullTransfer(t);
        JsonObject snap = t.snapshot();
        assertFalse(snap.getBoolean("enabled"));
        assertEquals(0, snap.getInteger("samples"));
        assertEquals(0, t.now());
    }

    @Test
    void completeTransactionBecomesOneSampleWithDerivedPhases() {
        StepClock clock = new StepClock();
        PhaseTimings t = new PhaseTimings(true, 10, clock::read);
        t.reset();
        fullTransfer(t);
        JsonObject snap = t.snapshot();
        assertEquals(1, snap.getInteger("samples"));
        // consecutive marks are 1 µs apart
        assertEquals(1.0, stat(snap, "W_queue", "mean_us"));
        assertEquals(1.0, stat(snap, "W_conn", "mean_us"));
        assertEquals(1.0, stat(snap, "W_row", "mean_us"));
        assertEquals(1.0, stat(snap, "W_lock", "mean_us"));
        assertEquals(1.0, stat(snap, "H_work", "mean_us"));
        assertEquals(1.0, stat(snap, "H_commit", "mean_us"));
        assertEquals(2.0, stat(snap, "H", "mean_us"), "H = H_work + H_commit");
    }

    @Test
    void transactionThatNeverTookTheChainLockIsNotRecorded() {
        PhaseTimings t = new PhaseTimings(true, 10, new StepClock()::read);
        t.begin(t.now());
        t.mark(Mark.CONN_START);
        t.mark(Mark.CONN_END);
        t.mark(Mark.COMMIT_START);
        t.mark(Mark.COMMIT_END);
        t.recordIfComplete();
        t.clear();
        assertEquals(0, t.snapshot().getInteger("samples"));
    }

    @Test
    void clearPreventsStaleMarksLeakingIntoTheNextTransaction() {
        PhaseTimings t = new PhaseTimings(true, 10, new StepClock()::read);
        // a transfer that rolls back after taking the lock: marks set, never recorded
        t.begin(t.now());
        t.mark(Mark.ROW_START);
        t.mark(Mark.ROW_END);
        t.mark(Mark.LOCK_START);
        t.mark(Mark.LOCK_END);
        t.clear();
        // next transaction on the same thread (e.g. relay) commits without row/lock marks
        t.begin(t.now());
        t.mark(Mark.CONN_START);
        t.mark(Mark.CONN_END);
        t.mark(Mark.COMMIT_START);
        t.mark(Mark.COMMIT_END);
        t.recordIfComplete();
        t.clear();
        assertEquals(0, t.snapshot().getInteger("samples"));
    }

    @Test
    void nearestRankPercentiles() {
        long[] sorted = new long[100];
        for (int i = 0; i < 100; i++) {
            sorted[i] = i + 1;
        }
        assertEquals(50, PhaseTimings.percentile(sorted, 50));
        assertEquals(95, PhaseTimings.percentile(sorted, 95));
        assertEquals(99, PhaseTimings.percentile(sorted, 99));
        assertEquals(0, PhaseTimings.percentile(new long[0], 50));
    }

    @Test
    void capacityOverflowIsCountedAsDropped() {
        PhaseTimings t = new PhaseTimings(true, 2, new StepClock()::read);
        fullTransfer(t);
        fullTransfer(t);
        fullTransfer(t);
        JsonObject snap = t.snapshot();
        assertEquals(2, snap.getInteger("samples"));
        assertEquals(1, snap.getLong("dropped"));
    }

    @Test
    void resetStartsAnEmptyWindow() {
        PhaseTimings t = new PhaseTimings(true, 10, new StepClock()::read);
        fullTransfer(t);
        t.reset();
        assertEquals(0, t.snapshot().getInteger("samples"));
    }
}
