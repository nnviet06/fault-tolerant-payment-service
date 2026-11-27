package com.nnviet.payment.outbox;

import com.nnviet.payment.common.id.Ids;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Inserts the event in the caller's transaction, so the event and the movement commit together.
 * No movement without its event, no event without its movement.
 */
public class OutboxWriter {

    public UUID write(Connection conn, String eventType, JsonObject payload) throws SQLException {
        UUID eventId = Ids.newId();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO outbox (event_id, event_type, payload) VALUES (?, ?, ?::jsonb)")) {
            ps.setObject(1, eventId);
            ps.setString(2, eventType);
            ps.setString(3, payload.encode());
            ps.executeUpdate();
        }
        return eventId;
    }
}
