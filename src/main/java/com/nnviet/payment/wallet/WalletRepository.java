package com.nnviet.payment.wallet;

import com.nnviet.payment.common.errors.WalletNotFoundException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking JDBC access to the wallets table. All methods run inside a Tx
 * transaction on a worker thread and share the caller's Connection.
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
     * SELECT ... FOR UPDATE: takes the row lock that serializes all concurrent
     * movements on this wallet until the transaction commits or rolls back.
     * This is the double-spend defense.
     */
    public Wallet lockById(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " FOR UPDATE")) {
            ps.setObject(1, id);
            return readOne(ps).orElseThrow(() -> new WalletNotFoundException(id));
        }
    }

    /**
     * Applies balance/held deltas to a row the caller has already locked.
     * The schema CHECK constraints reject any delta that would break
     * balance >= held_amount >= 0, no matter what the caller validated.
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
