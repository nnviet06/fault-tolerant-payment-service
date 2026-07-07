package com.nnviet.payment.ledger;

import com.nnviet.payment.common.Hashes;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Blocking JDBC access to the append-only ledger. Owns the hash chain:
 * every append links to the previous entry's hash, so any later tampering
 * with a stored row breaks recomputation from that seq onward.
 */
public class LedgerRepository {

    public static final String GENESIS_HASH = "0".repeat(64);

    /**
     * A global hash chain needs a total order over appends. This advisory
     * lock (transaction-scoped: released automatically at commit/rollback)
     * serializes the read-tail-then-insert step. Documented tradeoff: the
     * chain makes the ledger tail a single append point.
     */
    private static final long CHAIN_LOCK_KEY = 0x4C45444745524C4BL; // "LEDGERLK"

    private static final String COLUMNS =
            "seq, entry_type, debit_wallet_id, credit_wallet_id, amount, hold_id, "
                    + "idempotency_key, created_at_ms, prev_hash, entry_hash";

    public LedgerEntry append(Connection conn, String entryType, UUID debitWalletId,
                              UUID creditWalletId, long amount, UUID holdId,
                              String idempotencyKey) throws SQLException {
        acquireChainLock(conn);
        Tail tail = readTail(conn);
        long seq = tail.seq() + 1;
        long createdAtMs = System.currentTimeMillis();
        String entryHash = Hashes.sha256Hex(canonical(
                seq, entryType, debitWalletId, creditWalletId, amount,
                holdId, idempotencyKey, createdAtMs, tail.hash()));
        LedgerEntry entry = new LedgerEntry(seq, entryType, debitWalletId, creditWalletId,
                amount, holdId, idempotencyKey, createdAtMs, tail.hash(), entryHash);
        insert(conn, entry);
        return entry;
    }

    public List<LedgerEntry> listByWallet(Connection conn, UUID walletId, int limit)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM ledger_entries "
                        + "WHERE debit_wallet_id = ? OR credit_wallet_id = ? "
                        + "ORDER BY seq DESC LIMIT ?")) {
            ps.setObject(1, walletId);
            ps.setObject(2, walletId);
            ps.setInt(3, limit);
            return readAll(ps);
        }
    }

    public List<LedgerEntry> listRecent(Connection conn, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM ledger_entries ORDER BY seq DESC LIMIT ?")) {
            ps.setInt(1, limit);
            return readAll(ps);
        }
    }

    /** Walks the whole chain in seq order, recomputing every link. */
    public ChainVerification verifyChain(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM ledger_entries ORDER BY seq ASC");
             ResultSet rs = ps.executeQuery()) {
            long expectedSeq = 1;
            String expectedPrevHash = GENESIS_HASH;
            long checked = 0;
            while (rs.next()) {
                LedgerEntry e = read(rs);
                if (e.seq() != expectedSeq) {
                    return ChainVerification.broken(checked, e.seq(),
                            "sequence gap: expected " + expectedSeq + ", found " + e.seq());
                }
                if (!e.prevHash().equals(expectedPrevHash)) {
                    return ChainVerification.broken(checked, e.seq(),
                            "prev_hash does not match previous entry's hash");
                }
                String recomputed = Hashes.sha256Hex(canonical(
                        e.seq(), e.entryType(), e.debitWalletId(), e.creditWalletId(),
                        e.amount(), e.holdId(), e.idempotencyKey(), e.createdAtMs(),
                        e.prevHash()));
                if (!recomputed.equals(e.entryHash())) {
                    return ChainVerification.broken(checked, e.seq(),
                            "entry hash mismatch: stored content was altered");
                }
                expectedSeq++;
                expectedPrevHash = e.entryHash();
                checked++;
            }
            return ChainVerification.ok(checked);
        }
    }

    public record ChainVerification(boolean valid, long checkedCount, Long firstBrokenSeq,
                                    String reason) {

        static ChainVerification ok(long checkedCount) {
            return new ChainVerification(true, checkedCount, null, null);
        }

        static ChainVerification broken(long checkedCount, long brokenSeq, String reason) {
            return new ChainVerification(false, checkedCount, brokenSeq, reason);
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject()
                    .put("valid", valid)
                    .put("checkedCount", checkedCount);
            if (firstBrokenSeq != null) {
                json.put("firstBrokenSeq", firstBrokenSeq);
            }
            if (reason != null) {
                json.put("reason", reason);
            }
            return json;
        }
    }

    private static void acquireChainLock(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, CHAIN_LOCK_KEY);
            ps.execute();
        }
    }

    private record Tail(long seq, String hash) {
    }

    private static Tail readTail(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT seq, entry_hash FROM ledger_entries ORDER BY seq DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return new Tail(0L, GENESIS_HASH);
            }
            return new Tail(rs.getLong("seq"), rs.getString("entry_hash"));
        }
    }

    private static void insert(Connection conn, LedgerEntry e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger_entries (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, e.seq());
            ps.setString(2, e.entryType());
            ps.setObject(3, e.debitWalletId());
            ps.setObject(4, e.creditWalletId());
            ps.setLong(5, e.amount());
            ps.setObject(6, e.holdId());
            ps.setString(7, e.idempotencyKey());
            ps.setLong(8, e.createdAtMs());
            ps.setString(9, e.prevHash());
            ps.setString(10, e.entryHash());
            ps.executeUpdate();
        }
    }

    /**
     * Canonical hash input. Only stored, immutable columns participate, so
     * verification can recompute the exact same string from the database.
     */
    private static String canonical(long seq, String entryType, UUID debit, UUID credit,
                                    long amount, UUID holdId, String idempotencyKey,
                                    long createdAtMs, String prevHash) {
        return seq + "|" + entryType
                + "|" + (debit == null ? "-" : debit)
                + "|" + (credit == null ? "-" : credit)
                + "|" + amount
                + "|" + (holdId == null ? "-" : holdId)
                + "|" + (idempotencyKey == null ? "-" : idempotencyKey)
                + "|" + createdAtMs
                + "|" + prevHash;
    }

    private static List<LedgerEntry> readAll(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<LedgerEntry> entries = new ArrayList<>();
            while (rs.next()) {
                entries.add(read(rs));
            }
            return entries;
        }
    }

    private static LedgerEntry read(ResultSet rs) throws SQLException {
        return new LedgerEntry(
                rs.getLong("seq"),
                rs.getString("entry_type"),
                rs.getObject("debit_wallet_id", UUID.class),
                rs.getObject("credit_wallet_id", UUID.class),
                rs.getLong("amount"),
                rs.getObject("hold_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getLong("created_at_ms"),
                rs.getString("prev_hash"),
                rs.getString("entry_hash"));
    }
}
