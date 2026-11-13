package com.nnviet.payment.ledger;

import com.nnviet.payment.common.Hashes;
import com.nnviet.payment.common.bench.PhaseTimings;
import com.nnviet.payment.common.bench.PhaseTimings.Mark;
import com.nnviet.payment.common.errors.WalletNotFoundException;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blocking JDBC access to the append-only ledger. Owns the hash chains.
 *
 * Chains are PER WALLET (issue #4). Every entry is the next link of the chain
 * of each wallet it touches: it records the wallet's position and the
 * entry_hash of that wallet's previous link, and its own entry_hash covers
 * all of it. A transfer is therefore a link in two chains at once, and editing
 * any hashed column breaks every chain the row belongs to.
 *
 * Ordering without a global lock: a wallet's chain only grows while its row
 * is locked (every money workflow locks its wallets, in id order, before
 * appending), so two movements on disjoint wallets append in parallel. The
 * wallet row stores the chain head; advancing it is a compare-and-set, so an
 * append that somehow ran without the lock fails instead of forking a chain.
 */
public class LedgerRepository {

    public static final String GENESIS_HASH = "0".repeat(64);

    private static final String COLUMNS =
            "seq, entry_type, debit_wallet_id, debit_wallet_seq, debit_prev_hash, "
                    + "credit_wallet_id, credit_wallet_seq, credit_prev_hash, amount, hold_id, "
                    + "idempotency_key, created_at_ms, entry_hash";

    /** A wallet's chain head as stored on its row: position and newest link hash. */
    private record Head(long seq, String hash) {
    }

    public LedgerEntry append(Connection conn, String entryType, UUID debitWalletId,
                              UUID creditWalletId, long amount, UUID holdId,
                              String idempotencyKey) throws SQLException {
        if (debitWalletId != null && debitWalletId.equals(creditWalletId)) {
            // workflows reject this earlier; the schema CHECK backs it up
            throw new IllegalStateException("a ledger entry cannot debit and credit the same wallet");
        }
        // W_lock in PhaseTimings: before #4 the global chain-lock wait, now the head read
        PhaseTimings.global().mark(Mark.LOCK_START); // no-op unless BENCH_TIMING=true
        Map<UUID, Head> heads = readHeads(conn, debitWalletId, creditWalletId);
        PhaseTimings.global().mark(Mark.LOCK_END);

        Head debit = debitWalletId == null ? null : heads.get(debitWalletId);
        Head credit = creditWalletId == null ? null : heads.get(creditWalletId);
        Long debitSeq = debit == null ? null : debit.seq() + 1;
        String debitPrev = debit == null ? null : debit.hash();
        Long creditSeq = credit == null ? null : credit.seq() + 1;
        String creditPrev = credit == null ? null : credit.hash();
        long createdAtMs = System.currentTimeMillis();
        String entryHash = Hashes.sha256Hex(canonical(entryType,
                debitWalletId, debitSeq, debitPrev, creditWalletId, creditSeq, creditPrev,
                amount, holdId, idempotencyKey, createdAtMs));

        long seq = insert(conn, entryType, debitWalletId, debitSeq, debitPrev,
                creditWalletId, creditSeq, creditPrev, amount, holdId, idempotencyKey,
                createdAtMs, entryHash);

        // same id order as the workflows' lock order
        if (debit != null && credit != null && creditWalletId.compareTo(debitWalletId) < 0) {
            advanceHead(conn, creditWalletId, credit.seq(), creditSeq, entryHash);
            advanceHead(conn, debitWalletId, debit.seq(), debitSeq, entryHash);
        } else {
            if (debit != null) {
                advanceHead(conn, debitWalletId, debit.seq(), debitSeq, entryHash);
            }
            if (credit != null) {
                advanceHead(conn, creditWalletId, credit.seq(), creditSeq, entryHash);
            }
        }
        return new LedgerEntry(seq, entryType, debitWalletId, debitSeq, debitPrev,
                creditWalletId, creditSeq, creditPrev, amount, holdId, idempotencyKey,
                createdAtMs, entryHash);
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

    /**
     * Walks every wallet's chain and checks: positions run 1..n with no gap,
     * each link's prev hash equals the previous link's entry_hash, every
     * entry_hash recomputes, and the last link matches the head stored on the
     * wallet row. Every row has at least one wallet side, so every row is
     * checked. Must be the first statement of its transaction: it pins one
     * REPEATABLE READ snapshot so concurrent appends cannot cause false alarms.
     */
    public ChainVerification verifyChain(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        }
        Map<UUID, Head> wallets = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, chain_seq, chain_head FROM wallets ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                wallets.put(rs.getObject("id", UUID.class),
                        new Head(rs.getLong("chain_seq"), rs.getString("chain_head")));
            }
        }
        long checkedRows = 0;
        for (Map.Entry<UUID, Head> wallet : wallets.entrySet()) {
            UUID walletId = wallet.getKey();
            long expectedPosition = 1;
            String expectedPrev = GENESIS_HASH;
            for (Link link : readChain(conn, walletId)) {
                LedgerEntry e = link.entry();
                if (link.position() != expectedPosition) {
                    return ChainVerification.broken(checkedRows, wallets.size(), e.seq(), walletId,
                            "wallet chain position gap: expected " + expectedPosition
                                    + ", found " + link.position());
                }
                if (!link.prevHash().equals(expectedPrev)) {
                    return ChainVerification.broken(checkedRows, wallets.size(), e.seq(), walletId,
                            "prev hash does not match the wallet's previous link");
                }
                String recomputed = Hashes.sha256Hex(canonical(e.entryType(),
                        e.debitWalletId(), e.debitWalletSeq(), e.debitPrevHash(),
                        e.creditWalletId(), e.creditWalletSeq(), e.creditPrevHash(),
                        e.amount(), e.holdId(), e.idempotencyKey(), e.createdAtMs()));
                if (!recomputed.equals(e.entryHash())) {
                    return ChainVerification.broken(checkedRows, wallets.size(), e.seq(), walletId,
                            "entry hash mismatch: stored content was altered");
                }
                // a transfer row is walked once per side; count it once
                if (link.debitSide() || e.debitWalletId() == null) {
                    checkedRows++;
                }
                expectedPosition++;
                expectedPrev = e.entryHash();
            }
            Head head = wallet.getValue();
            long lastPosition = expectedPosition - 1;
            if (head.seq() != lastPosition || !head.hash().equals(expectedPrev)) {
                return ChainVerification.broken(checkedRows, wallets.size(), null, walletId,
                        "wallet chain head (position " + head.seq()
                                + ") does not match its last ledger link (position " + lastPosition
                                + "): links missing or head altered");
            }
        }
        return ChainVerification.ok(checkedRows, wallets.size());
    }

    public record ChainVerification(boolean valid, long checkedCount, long walletsChecked,
                                    Long firstBrokenSeq, UUID walletId, String reason) {

        static ChainVerification ok(long checkedCount, long walletsChecked) {
            return new ChainVerification(true, checkedCount, walletsChecked, null, null, null);
        }

        static ChainVerification broken(long checkedCount, long walletsChecked, Long brokenSeq,
                                        UUID walletId, String reason) {
            return new ChainVerification(false, checkedCount, walletsChecked, brokenSeq,
                    walletId, reason);
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject()
                    .put("valid", valid)
                    .put("checkedCount", checkedCount)
                    .put("walletsChecked", walletsChecked);
            if (firstBrokenSeq != null) {
                json.put("firstBrokenSeq", firstBrokenSeq);
            }
            if (walletId != null) {
                json.put("walletId", walletId.toString());
            }
            if (reason != null) {
                json.put("reason", reason);
            }
            return json;
        }
    }

    /** One row seen as a link of one wallet's chain. */
    private record Link(LedgerEntry entry, boolean debitSide, long position, String prevHash) {
    }

    private static List<Link> readChain(Connection conn, UUID walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + ", true AS link_debit, debit_wallet_seq AS link_position, "
                        + "debit_prev_hash AS link_prev FROM ledger_entries WHERE debit_wallet_id = ? "
                        + "UNION ALL "
                        + "SELECT " + COLUMNS + ", false, credit_wallet_seq, credit_prev_hash "
                        + "FROM ledger_entries WHERE credit_wallet_id = ? "
                        + "ORDER BY link_position")) {
            ps.setObject(1, walletId);
            ps.setObject(2, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Link> links = new ArrayList<>();
                while (rs.next()) {
                    links.add(new Link(read(rs), rs.getBoolean("link_debit"),
                            rs.getLong("link_position"), rs.getString("link_prev")));
                }
                return links;
            }
        }
    }

    /** Chain heads of the (one or two) wallets of an entry, from their locked rows. */
    private static Map<UUID, Head> readHeads(Connection conn, UUID a, UUID b) throws SQLException {
        List<UUID> ids = new ArrayList<>(2);
        if (a != null) {
            ids.add(a);
        }
        if (b != null) {
            ids.add(b);
        }
        String placeholders = ids.size() == 2 ? "?, ?" : "?";
        Map<UUID, Head> heads = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, chain_seq, chain_head FROM wallets WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setObject(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    heads.put(rs.getObject("id", UUID.class),
                            new Head(rs.getLong("chain_seq"), rs.getString("chain_head")));
                }
            }
        }
        for (UUID id : ids) {
            if (!heads.containsKey(id)) {
                throw new WalletNotFoundException(id);
            }
        }
        return heads;
    }

    /**
     * Compare-and-set of a wallet's chain head. Under the caller's row lock it
     * always succeeds; if the head moved anyway (an append without the lock),
     * it fails the whole transaction instead of letting the chain fork. This
     * CAS is what stops two links taking the same position of one wallet - the
     * unique indexes only cover one side (debit or credit) each.
     */
    private static void advanceHead(Connection conn, UUID walletId, long expectedSeq,
                                    long newSeq, String newHash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE wallets SET chain_seq = ?, chain_head = ? WHERE id = ? AND chain_seq = ?")) {
            ps.setLong(1, newSeq);
            ps.setString(2, newHash);
            ps.setObject(3, walletId);
            ps.setLong(4, expectedSeq);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("chain head of wallet " + walletId
                        + " moved during this append: the caller must hold the wallet row lock");
            }
        }
    }

    private static long insert(Connection conn, String entryType,
                               UUID debitWalletId, Long debitSeq, String debitPrev,
                               UUID creditWalletId, Long creditSeq, String creditPrev,
                               long amount, UUID holdId, String idempotencyKey,
                               long createdAtMs, String entryHash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger_entries (entry_type, debit_wallet_id, debit_wallet_seq, "
                        + "debit_prev_hash, credit_wallet_id, credit_wallet_seq, credit_prev_hash, "
                        + "amount, hold_id, idempotency_key, created_at_ms, entry_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING seq")) {
            ps.setString(1, entryType);
            ps.setObject(2, debitWalletId);
            setNullableLong(ps, 3, debitSeq);
            ps.setString(4, debitPrev);
            ps.setObject(5, creditWalletId);
            setNullableLong(ps, 6, creditSeq);
            ps.setString(7, creditPrev);
            ps.setLong(8, amount);
            ps.setObject(9, holdId);
            ps.setString(10, idempotencyKey);
            ps.setLong(11, createdAtMs);
            ps.setString(12, entryHash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * Canonical hash input. Only stored, immutable columns participate, so
     * verification can recompute the exact same string from the database.
     * seq (the row id) is deliberately excluded: the proof is the per-wallet
     * position plus prev hash, not the row id.
     */
    private static String canonical(String entryType,
                                    UUID debit, Long debitSeq, String debitPrev,
                                    UUID credit, Long creditSeq, String creditPrev,
                                    long amount, UUID holdId, String idempotencyKey,
                                    long createdAtMs) {
        return entryType
                + "|" + orDash(debit) + "|" + orDash(debitSeq) + "|" + orDash(debitPrev)
                + "|" + orDash(credit) + "|" + orDash(creditSeq) + "|" + orDash(creditPrev)
                + "|" + amount
                + "|" + orDash(holdId)
                + "|" + orDash(idempotencyKey)
                + "|" + createdAtMs;
    }

    private static String orDash(Object value) {
        return value == null ? "-" : value.toString();
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
                rs.getObject("debit_wallet_seq", Long.class),
                rs.getString("debit_prev_hash"),
                rs.getObject("credit_wallet_id", UUID.class),
                rs.getObject("credit_wallet_seq", Long.class),
                rs.getString("credit_prev_hash"),
                rs.getLong("amount"),
                rs.getObject("hold_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getLong("created_at_ms"),
                rs.getString("entry_hash"));
    }
}
