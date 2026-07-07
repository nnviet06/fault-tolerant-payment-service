package com.nnviet.payment.outbox;

import com.nnviet.payment.common.id.Ids;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The write side of the transactional outbox - the crash-dual-write defense.
 *
 * write() inserts on the CALLER'S Connection, i.e. inside the same database
 * transaction as the ledger entry it describes. Either both commit or both
 * roll back; "money moved but the event is lost" and "event exists for a
 * movement that never happened" are unrepresentable states.
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
