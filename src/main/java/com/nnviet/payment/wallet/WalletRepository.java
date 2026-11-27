package com.nnviet.payment.wallet;

import com.nnviet.payment.common.errors.WalletNotFoundException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Wallets table access; every method runs inside the caller's Tx connection.
 */
public class WalletRepository {

    private static final String SELECT =
            "SELECT id, owner_name, balance, held_amount FROM wallets WHERE id = ?";

    public void insert(Connection conn, Wallet wallet) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallets (id, owner_name, balance, held_amount) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, wallet.id());
            ps.setString(2, wallet.ownerName());
            ps.setLong(3, wallet.balance());
            ps.setLong(4, wallet.heldAmount());
            ps.executeUpdate();
        }
    }

    public Optional<Wallet> findById(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT)) {
            ps.setObject(1, id);
            return readOne(ps);
        }
    }

    /**
     * Locks the wallet row (FOR UPDATE) until the transaction ends: the double-spend defense.
     */
    public Wallet lockById(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " FOR UPDATE")) {
            ps.setObject(1, id);
            return readOne(ps).orElseThrow(() -> new WalletNotFoundException(id));
        }
    }

    /**
     * Adds the deltas to a row the caller locked. The schema still rejects balance < held or < 0.
     */
    public void applyDeltas(Connection conn, UUID id, long balanceDelta, long heldDelta)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE wallets SET balance = balance + ?, held_amount = held_amount + ? WHERE id = ?")) {
            ps.setLong(1, balanceDelta);
            ps.setLong(2, heldDelta);
            ps.setObject(3, id);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException("expected 1 wallet row updated, got " + updated);
            }
        }
    }

    private static Optional<Wallet> readOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new Wallet(
                    rs.getObject("id", UUID.class),
                    rs.getString("owner_name"),
                    rs.getLong("balance"),
                    rs.getLong("held_amount")));
        }
    }
}
