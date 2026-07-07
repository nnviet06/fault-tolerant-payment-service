package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.common.db.Tx;
import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.ledger.task.SettleHoldTask;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.WalletRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TTL sweeper: periodically expires ACTIVE holds whose deadline passed.
 *
 * Each hold is settled in its OWN small transaction, so the sweeper never
 * holds more than one wallet lock at a time and cannot deadlock with
 * transfers/commits (which lock two wallets in id order). Losing the
 * compare-and-set to a concurrent commit is a silent no-op - that race
 * having exactly one winner is the commit-vs-expiry torture scenario.
 */
public class ExpireHoldsWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ExpireHoldsWorkflow.class);

    private final Tx tx;
    private final HoldRepository holds;
    private final WalletRepository wallets;
    private final SettleHoldTask settleHold;
    private final OutboxWriter outbox;
    private final long intervalMs;
    private final int batchSize;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public ExpireHoldsWorkflow(Tx tx, HoldRepository holds, WalletRepository wallets,
                               SettleHoldTask settleHold, OutboxWriter outbox,
                               long intervalMs, int batchSize) {
        this.tx = tx;
        this.holds = holds;
        this.wallets = wallets;
        this.settleHold = settleHold;
        this.outbox = outbox;
        this.intervalMs = intervalMs;
        this.batchSize = batchSize;
    }

    public void start(Vertx vertx) {
        vertx.setPeriodic(intervalMs, timerId -> tick());
        log.info("hold expiry sweeper started (every {} ms, batch {})", intervalMs, batchSize);
    }

    void tick() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        sweep().subscribe().with(
                expired -> {
                    inFlight.set(false);
                    if (expired > 0) {
                        log.info("expired {} hold(s)", expired);
                    }
                },
                failure -> {
                    inFlight.set(false);
                    log.warn("hold sweep failed, will retry: {}", failure.toString());
                });
    }

    Uni<Integer> sweep() {
        return tx.offload(() -> {
            List<UUID> candidates =
                    tx.runBlocking(conn -> holds.findExpiredActiveIds(conn, batchSize));
            int expired = 0;
            for (UUID holdId : candidates) {
                expired += tx.runBlocking(conn -> expireOne(conn, holdId));
            }
            return expired;
        });
    }

    private int expireOne(Connection conn, UUID holdId) throws SQLException {
        Hold hold = holds.findById(conn, holdId).orElse(null);
        if (hold == null || !Hold.ACTIVE.equals(hold.status())) {
            return 0;
        }
        wallets.lockById(conn, hold.walletId());
        boolean won = settleHold.settle(conn, hold, Hold.EXPIRED,
                HoldRepository.ExpiryGuard.MUST_BE_EXPIRED);
        if (!won) {
            return 0; // a commit or rollback settled it first - their win is legitimate
        }
        outbox.write(conn, "hold.expired", new JsonObject()
                .put("holdId", hold.id().toString())
                .put("walletId", hold.walletId().toString())
                .put("amount", hold.amount()));
        return 1;
    }
}
