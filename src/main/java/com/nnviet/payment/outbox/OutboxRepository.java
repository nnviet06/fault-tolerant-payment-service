package com.nnviet.payment.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Outbox table access for the relay. */
public class OutboxRepository {

    public record PendingEvent(long id, UUID eventId, String eventType, String payloadJson,
                               int attempts) {
    }

    /** Locks a batch of unsent rows. SKIP LOCKED lets a second relay skip rows another is sending. */
    public List<PendingEvent> lockPending(Connection conn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, event_id, event_type, payload::text AS payload, attempts "
                        + "FROM outbox WHERE published_at IS NULL "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new PendingEvent(
                            rs.getLong("id"),
                            rs.getObject("event_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload"),
                            rs.getInt("attempts")));
                }
                return events;
            }
        }
    }

    public void incrementAttempts(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE outbox SET attempts = attempts + 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void markPublished(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE outbox SET published_at = now() WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
