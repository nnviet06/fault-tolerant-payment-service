package com.nnviet.payment.common.db;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.WorkerExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;

/**
 * THE transaction boundary of the service.
 *
 * One workflow invocation = one JDBC Connection = one database transaction:
 * commit on success, rollback on any exception. Blocking JDBC runs only on
 * the named worker pool (never on an event loop) - this is the locked
 * "blocking JDBC on worker threads" decision made explicit in one place.
 *
 * Every step (task) inside a workflow receives this same Connection, which is
 * what makes wallet update + ledger entry + outbox row + idempotency claim
 * atomically inseparable.
 */
public class Tx {

    @FunctionalInterface
    public interface TxBlock<T> {
        T run(Connection conn) throws Exception;
    }

    private final DataSource dataSource;
    private final WorkerExecutor executor;

    public Tx(DataSource dataSource, WorkerExecutor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    /** Runs the block in a transaction on a worker thread. */
    public <T> Uni<T> inTx(TxBlock<T> block) {
        // ordered=false: independent requests must not serialize behind each
        // other on the caller's context - row locks do the serializing.
        return executor.executeBlocking(() -> runBlocking(block), false);
    }

    /**
     * Synchronous variant for callers already on a worker thread (the hold
     * sweeper runs several small transactions in one offloaded task) and for
     * tests.
     */
    public <T> T runBlocking(TxBlock<T> block) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = block.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Offloads non-transactional blocking work to the worker pool. */
    public <T> Uni<T> offload(Callable<T> work) {
        return executor.executeBlocking(work, false);
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // the original exception is the one worth surfacing
        }
    }
}
