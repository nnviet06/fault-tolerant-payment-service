package com.nnviet.payment.outbox;

import com.nnviet.payment.common.chaos.Chaos;
import com.nnviet.payment.common.db.Tx;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls unpublished outbox rows, publishes them and marks them sent, in one transaction.
 * A crash before commit leaves them unsent, so they go out again: at-least-once, never lost.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final Tx tx;
    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final long intervalMs;
    private final int batchSize;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public OutboxRelay(Tx tx, OutboxRepository repository, EventPublisher publisher,
                       long intervalMs, int batchSize) {
        this.tx = tx;
        this.repository = repository;
        this.publisher = publisher;
        this.intervalMs = intervalMs;
        this.batchSize = batchSize;
    }

    public void start(Vertx vertx) {
        vertx.setPeriodic(intervalMs, timerId -> tick());
        log.info("outbox relay started (every {} ms, batch {})", intervalMs, batchSize);
    }

    void tick() {
        // skip a tick rather than pile up if the previous one is still running
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        relayOnce().subscribe().with(
                published -> {
                    inFlight.set(false);
                    if (published > 0) {
                        log.debug("relay published {} event(s)", published);
                    }
                },
                failure -> {
                    inFlight.set(false);
                    log.warn("relay tick failed, batch rolled back, will retry: {}",
                            failure.toString());
                });
    }

    Uni<Integer> relayOnce() {
        return tx.inTx(conn -> {
            List<OutboxRepository.PendingEvent> pending =
                    repository.lockPending(conn, batchSize);
            int published = 0;
            for (OutboxRepository.PendingEvent p : pending) {
                repository.incrementAttempts(conn, p.id());
                OutboxEvent event = new OutboxEvent(
                        p.eventId(), p.eventType(), new JsonObject(p.payloadJson()));
                // blocking is fine: this runs on a db-worker thread
                publisher.publish(event).await().indefinitely();
                Chaos.point(Chaos.RELAY_AFTER_PUBLISH_BEFORE_MARK);
                repository.markPublished(conn, p.id());
                published++;
            }
            return published;
        });
    }
}
