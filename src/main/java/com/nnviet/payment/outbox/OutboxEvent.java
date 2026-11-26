package com.nnviet.payment.outbox;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Event sent to publishers. Delivery is at-least-once, so consumers dedup by eventId.
 */
public record OutboxEvent(UUID eventId, String eventType, JsonObject payload) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("eventId", eventId.toString())
                .put("eventType", eventType)
                .put("payload", payload);
    }
}
