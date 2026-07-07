package com.nnviet.payment.ledger;

import com.nnviet.payment.testsupport.TestDb;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerRepositoryTest {

    private final LedgerRepository ledger = new LedgerRepository();
    private final WalletRepository wallets = new WalletRepository();

    private UUID walletId;

    @BeforeAll
    static void requireDb() throws Exception {
        Assumptions.assumeTrue(TestDb.available(), TestDb.SKIP_MESSAGE);
        TestDb.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        TestDb.truncateAll();
        walletId = UUID.randomUUID();
        try (Connection conn = TestDb.open()) {
            wallets.insert(conn, new Wallet(walletId, "chain-test", 0, 0));
        }
    }

    @Test
    void appendsLinkIntoAChain() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            LedgerEntry first = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletId, 100, null, "k1");
            LedgerEntry second = ledger.append(conn, LedgerEntry.DEPOSIT,
                    null, walletId, 200, null, "k2");
            LedgerEntry third = ledger.append(conn, LedgerEntry.WITHDRAWAL,
                    walletId, null, 50, null, "k3");
            conn.commit();

            assertEquals(1, first.seq());
            assertEquals(2, second.seq());
            assertEquals(3, third.seq());
            assertEquals(LedgerRepository.GENESIS_HASH, first.prevHash());
            assertEquals(first.entryHash(), second.prevHash());
            assertEquals(second.entryHash(), third.prevHash());

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertTrue(result.valid());
            assertEquals(3, result.checkedCount());
        }
    }

    @Test
    void tamperingWithAnAmountIsDetectedAtTheExactSeq() throws Exception {
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletId, 100, null, "k1");
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletId, 200, null, "k2");
            ledger.append(conn, LedgerEntry.DEPOSIT, null, walletId, 300, null, "k3");
            conn.commit();

            // the attack: direct SQL edit of a financial field
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE ledger_entries SET amount = 999 WHERE seq = 2");
            }
            conn.commit();

            LedgerRepository.ChainVerification result = ledger.verifyChain(conn);
            conn.commit();
            assertFalse(result.valid());
            assertEquals(2L, result.firstBrokenSeq());
        }
    }
}
