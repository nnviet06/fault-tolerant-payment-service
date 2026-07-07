package com.nnviet.payment.common.db;

import com.nnviet.payment.testsupport.TestDb;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.WorkerExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxTest {

    private static Vertx vertx;
    private static HikariDataSource dataSource;
    private static Tx tx;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(TestDb.available(), TestDb.SKIP_MESSAGE);
        TestDb.ensureSchema();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(TestDb.URL);
        hc.setUsername(TestDb.USER);
        hc.setPassword(TestDb.PASSWORD);
        hc.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(hc);
        vertx = Vertx.vertx();
        WorkerExecutor executor = vertx.createSharedWorkerExecutor("test-db", 4);
        tx = new Tx(dataSource, executor);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (vertx != null) {
            vertx.closeAndAwait();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        TestDb.truncateAll();
    }

    @Test
    void commitsOnSuccess() {
        UUID id = UUID.randomUUID();
        tx.inTx(conn -> {
            insertWallet(conn, id);
            return null;
        }).await().atMost(Duration.ofSeconds(10));
        assertTrue(walletExists(id), "committed row must be visible afterwards");
    }

    @Test
    void rollsBackWholeTransactionOnException() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () ->
                tx.inTx(conn -> {
                    insertWallet(conn, id);
                    throw new IllegalStateException("boom after the insert");
                }).await().atMost(Duration.ofSeconds(10)));
        assertEquals(false, walletExists(id),
                "the insert must have rolled back with the failure");
    }

    private static void insertWallet(Connection conn, UUID id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallets (id, owner_name) VALUES (?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "tx-test");
            ps.executeUpdate();
        }
    }

    private static boolean walletExists(UUID id) {
        try (Connection conn = TestDb.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM wallets WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
