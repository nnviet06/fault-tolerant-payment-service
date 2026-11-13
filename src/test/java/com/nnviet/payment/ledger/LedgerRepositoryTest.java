package com.nnviet.payment.ledger;

import com.nnviet.payment.testsupport.TestDb;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerRepositoryTest {

    private final LedgerRepository ledger = new LedgerRepository();
    private final WalletRepository wallets = new WalletRepository();

    private UUID walletA;
    private UUID walletB;

    @BeforeAll
    static void requireDb() throws Exception {
        Assumptions.assumeTrue(TestDb.available(), TestDb.SKIP_MESSAGE);
        TestDb.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        TestDb.truncateAll();
        walletA = UUID.randomUUID();
        walletB = UUID.randomUUID();
        try (Connection conn = TestDb.open()) {
            wallets.insert(conn, new Wallet(walletA, "chain-test-a", 0, 0));
            wallets.insert(conn, new Wallet(walletB, "chain-test-b", 0, 0));
        }
    }

    @Test
    void appendsLinkIntoTheWalletsOwnChain() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            LedgerEntry first = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 100, null, "k1");
            LedgerEntry second = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 200, null, "k2");
            LedgerEntry third = ledger.append(conn, LedgerEntry.WITHDRAWAL,
                    walletA, null, 50, null, "k3");
            conn.commit();

            // positions in wallet A's chain, whichever side A is on
            assertEquals(1L, first.creditWalletSeq());
            assertEquals(2L, second.creditWalletSeq());
            assertEquals(3L, third.debitWalletSeq());
            assertEquals(LedgerRepository.GENESIS_HASH, first.creditPrevHash());
            assertEquals(first.entryHash(), second.creditPrevHash());
            assertEquals(second.entryHash(), third.debitPrevHash());
            assertNull(first.debitWalletSeq(), "external side carries no chain link");

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertTrue(result.valid(), String.valueOf(result.reason()));
            assertEquals(3, result.checkedCount());
        }
    }

    @Test
    void aTransferIsALinkInBothWalletsChains() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            LedgerEntry depositA = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 500, null, "k1");
            LedgerEntry depositB = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletB, 300, null, "k2");
            LedgerEntry transfer = ledger.append(conn, LedgerEntry.TRANSFER,
                    walletA, walletB, 100, null, "k3");
            conn.commit();

            assertEquals(2L, transfer.debitWalletSeq());
            assertEquals(depositA.entryHash(), transfer.debitPrevHash());
            assertEquals(2L, transfer.creditWalletSeq());
            assertEquals(depositB.entryHash(), transfer.creditPrevHash());

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertTrue(result.valid(), String.valueOf(result.reason()));
            assertEquals(3, result.checkedCount(), "the transfer row is counted once");
            assertEquals(2, result.walletsChecked());
        }
    }

    @Test
    void tamperingWithAnAmountIsDetectedAtTheExactRow() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletA, 100, null, "k1");
            LedgerEntry target = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 200, null, "k2");
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletA, 300, null, "k3");
            conn.commit();

            // the attack: direct SQL edit of a financial field
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE ledger_entries SET amount = 999 WHERE seq = " + target.seq());
            }
            conn.commit();

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertFalse(result.valid());
            assertEquals(target.seq(), result.firstBrokenSeq());
            assertEquals(walletA, result.walletId());
        }
    }

    @Test
    void tamperingWithATransferIsDetectedAtThatRow() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletA, 500, null, "k1");
            LedgerEntry transfer = ledger.append(conn, LedgerEntry.TRANSFER,
                    walletA, walletB, 100, null, "k2");
            conn.commit();

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE ledger_entries SET amount = 1 WHERE seq = " + transfer.seq());
            }
            conn.commit();

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertFalse(result.valid());
            assertEquals(transfer.seq(), result.firstBrokenSeq());
        }
    }

    @Test
    void deletingTheNewestLinkIsDetectedWhenTheWalletHeadIsLeftAlone() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletA, 100, null, "k1");
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletA, 200, null, "k2");
            LedgerEntry newest = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 300, null, "k3");
            conn.commit();

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM ledger_entries WHERE seq = " + newest.seq());
            }
            conn.commit();

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertFalse(result.valid());
            assertEquals(walletA, result.walletId());
            assertTrue(result.reason().contains("head"), result.reason());
        }
    }

    /**
     * The documented limit: the head lives in the same database, so someone
     * who can delete the newest link AND rewind the wallet's head is not
     * detected. Tamper evidence, not tamper proofing.
     */
    @Test
    void deletingTheNewestLinkAndRewindingTheHeadIsNotDetected() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletA, 100, null, "k1");
            LedgerEntry second = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 200, null, "k2");
            LedgerEntry newest = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletA, 300, null, "k3");
            conn.commit();

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM ledger_entries WHERE seq = " + newest.seq());
                stmt.execute("UPDATE wallets SET chain_seq = 2, chain_head = '"
                        + second.entryHash() + "' WHERE id = '" + walletA + "'");
            }
            conn.commit();

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertTrue(result.valid(), "a full-DB-access attacker can hide a tail deletion");
        }
    }

    @Test
    void anEntryCannotDebitAndCreditTheSameWallet() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            assertThrows(IllegalStateException.class, () -> ledger.append(conn,
                    LedgerEntry.TRANSFER, walletA, walletA, 100, null, "k1"));
            conn.rollback();
        }
    }

    /**
     * Two appends on the same wallet without the wallet row lock, on DIFFERENT
     * sides (credit, then debit): the unique indexes cannot see the clash, so
     * the compare-and-set on wallets.chain_seq must reject the second one.
     */
    @Test
    void compareAndSetRejectsAnAppendThatSkippedTheWalletLock() throws Exception {
        try (Connection first = TestDb.open(); Connection second = TestDb.open();
             Connection observer = TestDb.open()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            long secondPid = backendPid(second);

            // first: A's position 1 on the credit side; its CAS now holds A's row lock
            ledger.append(first, LedgerEntry.DEPOSIT, null, walletA, 100, null, "k1");

            // second: reads the committed head (still 0), inserts A's position 1 on the
            // debit side, then blocks in its CAS behind first's row lock
            CompletableFuture<Throwable> secondAppend = CompletableFuture.supplyAsync(() -> {
                try {
                    ledger.append(second, LedgerEntry.WITHDRAWAL, walletA, null, 50, null, "k2");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            awaitBlockedOnLock(observer, secondPid);

            first.commit();
            Throwable outcome = secondAppend.get(10, TimeUnit.SECONDS);
            assertInstanceOf(IllegalStateException.class, outcome,
                    "second append must fail, got " + outcome);
            second.rollback();

            LedgerRepository.ChainVerification result = ledger.verifyChain(first);
            first.commit();
            assertTrue(result.valid(), String.valueOf(result.reason()));
            assertEquals(1, result.checkedCount());
        }
    }

    private static long backendPid(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void awaitBlockedOnLock(Connection observer, long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?")) {
            ps.setLong(1, pid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && "Lock".equals(rs.getString(1))) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("second append never blocked on the wallet row lock");
    }
}
