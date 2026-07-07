package com.nnviet.payment.ledger.task;

import com.nnviet.payment.testsupport.TestDb;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task-level tests against the real Postgres: the schema CHECK constraints
 * are part of the behavior under test (last line of defense).
 */
class DebitCreditTaskTest {

    private final WalletRepository wallets = new WalletRepository();
    private final CreditTask credit = new CreditTask(wallets);
    private final DebitTask debit = new DebitTask(wallets);

    @BeforeAll
    static void requireDb() throws Exception {
        Assumptions.assumeTrue(TestDb.available(), TestDb.SKIP_MESSAGE);
        TestDb.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        TestDb.truncateAll();
    }

    @Test
    void creditThenDebitAdjustsBalance() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            wallets.insert(conn, new Wallet(id, "t", 0, 0));
            credit.credit(conn, id, 100);
            debit.debit(conn, id, 30);
            conn.commit();
            assertEquals(70, wallets.lockById(conn, id).balance());
            conn.commit();
        }
    }

    @Test
    void overdraftIsBlockedByTheCheckConstraintEvenWithoutValidation() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection conn = TestDb.open()) {
            conn.setAutoCommit(false);
            wallets.insert(conn, new Wallet(id, "t", 0, 0));
            credit.credit(conn, id, 50);
            conn.commit();

            // bypass ValidateBalanceTask on purpose: the schema must still refuse
            assertThrows(SQLException.class, () -> debit.debit(conn, id, 200));
            conn.rollback();

            assertEquals(50, wallets.lockById(conn, id).balance(),
                    "balance must be untouched after the rejected debit");
            conn.commit();
        }
    }
}
