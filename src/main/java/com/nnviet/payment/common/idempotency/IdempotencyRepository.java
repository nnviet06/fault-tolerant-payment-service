package com.nnviet.payment.common.idempotency;

import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Durable idempotency store (the correctness authority - Redis only caches
 * what this table committed).
 *
 * claim() relies on Postgres unique-index arbitration: when two duplicates
 * race, the second INSERT blocks until the first transaction commits or
 * aborts. Committed first -> the second sees the conflict and replays the
 * stored response. Aborted first -> the second's claim succeeds and it runs
 * the work itself.
 */
public class IdempotencyRepository {

    public record StoredResponse(String requestHash, Integer status, JsonObject body) {
    }

    /** Returns true when this transaction claimed the key (i.e. must run the work). */
    public boolean claim(Connection conn, String idemKey, String requestHash)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO idempotency_keys (idem_key, request_hash) VALUES (?, ?) "
                        + "ON CONFLICT (idem_key) DO NOTHING")) {
            ps.setString(1, idemKey);
            ps.setString(2, requestHash);
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<StoredResponse> find(Connection conn, String idemKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT request_hash, response_status, response_body::text AS body "
                        + "FROM idempotency_keys WHERE idem_key = ?")) {
            ps.setString(1, idemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String bodyJson = rs.getString("body");
                return Optional.of(new StoredResponse(
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        bodyJson == null ? null : new JsonObject(bodyJson)));
            }
        }
    }

    /**
     * Attaches the response to the claimed row BEFORE the same transaction
     * commits, so a committed key row always carries its response.
     */
    public void storeResponse(Connection conn, String idemKey, int status, JsonObject body)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE idempotency_keys SET response_status = ?, response_body = ?::jsonb "
                        + "WHERE idem_key = ?")) {
            ps.setInt(1, status);
            ps.setString(2, body.encode());
            ps.setString(3, idemKey);
            ps.executeUpdate();
        }
    }
}
