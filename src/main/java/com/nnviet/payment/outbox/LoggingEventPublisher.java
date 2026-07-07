package com.nnviet.payment.outbox;

import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default publisher until Kafka lands: the event stream goes to the log. */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public Uni<Void> publish(OutboxEvent event) {
        return Uni.createFrom().item(() -> {
            log.info("event published: {}", event.toJson().encode());
            return (Void) null;
        });
    }
}
