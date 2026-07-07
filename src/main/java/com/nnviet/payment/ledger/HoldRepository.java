package com.nnviet.payment.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking JDBC access to holds. All expiry decisions use the DATABASE clock
 * (now()): insert, commit-guard and sweeper share one clock authority, so an
 * app/database clock skew cannot make them disagree.
 */
public class HoldRepository {

    /** Extra predicate for the compare-and-set transition. */
    public enum ExpiryGuard {
        /** No expiry condition (rollback releases the reservation either way). */
        NONE,
        /** Commit must not settle a hold that already passed its TTL. */
        MUST_NOT_BE_EXPIRED,
        /** The sweeper must only expire holds that actually passed their TTL. */
        MUST_BE_EXPIRED
    }

    public Hold insert(Connection conn, UUID id, UUID walletId, long amount, int ttlSeconds)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO holds (id, wallet_id, amount, status, expires_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', now() + (? * interval '1 second')) "
                        + "RETURNING expires_at")) {
            ps.setObject(1, id);
            ps.setObject(2, walletId);
            ps.setLong(3, amount);
            ps.setInt(4, ttlSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Hold(id, walletId, amount, Hold.ACTIVE,
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant());
            }
        }
    }

    public Optional<Hold> findById(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, wallet_id, amount, status, expires_at FROM holds WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Hold(
                        rs.getObject("id", UUID.class),
                        rs.getObject("wallet_id", UUID.class),
                        rs.getLong("amount"),
                        rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()));
            }
        }
    }

    /**
     * The atomic compare-and-set that gives every hold exactly one settlement:
     * only a row still in ACTIVE (and passing the expiry guard) is updated.
     * Returns false when another settlement won the race.
     */
    public boolean transitionFromActive(Connection conn, UUID id, String toStatus,
                                        ExpiryGuard guard) throws SQLException {
        String sql = "UPDATE holds SET status = ?, settled_at = now() "
                + "WHERE id = ? AND status = 'ACTIVE'"
                + switch (guard) {
                    case NONE -> "";
                    case MUST_NOT_BE_EXPIRED -> " AND expires_at > now()";
                    case MUST_BE_EXPIRED -> " AND expires_at <= now()";
                };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toStatus);
            ps.setObject(2, id);
            return ps.executeUpdate() == 1;
        }
    }

    public List<UUID> findExpiredActiveIds(Connection conn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM holds WHERE status = 'ACTIVE' AND expires_at <= now() "
                        + "ORDER BY expires_at LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getObject("id", UUID.class));
                }
                return ids;
            }
        }
    }
}
