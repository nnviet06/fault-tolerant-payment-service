package com.nnviet.payment.outbox;

import io.smallrye.mutiny.Uni;

/**
 * Pluggable publish port. Kafka is deferred: when it arrives it becomes one
 * more implementation of this interface - nothing in the relay changes.
 *
 * Contract: the returned Uni completes only when the event is durably handed
 * off. A failure makes the relay roll back and retry the whole batch, so
 * delivery is at-least-once; consumers deduplicate on eventId.
 */
public interface EventPublisher {

    Uni<Void> publish(OutboxEvent event);
}
