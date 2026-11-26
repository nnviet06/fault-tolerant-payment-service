package com.nnviet.payment.outbox;

import io.smallrye.mutiny.Uni;

/**
 * Where the relay sends events; a future Kafka publisher is one more implementation.
 * The Uni completes only once the event is safely handed off; a failure means the relay retries.
 */
public interface EventPublisher {

    Uni<Void> publish(OutboxEvent event);
}
