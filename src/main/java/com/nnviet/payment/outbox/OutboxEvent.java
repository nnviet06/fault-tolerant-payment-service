package com.nnviet.payment.outbox;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * The event envelope handed to publishers. eventId is the consumer-side
 * deduplication key: delivery is at-least-once, so consumers must treat a
 * repeated eventId as already processed.
 */
public record OutboxEvent(UUID eventId, String eventType, JsonObject payload) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("eventId", eventId.toString())
                .put("eventType", eventType)
                .put("payload", payload);
    }
}
