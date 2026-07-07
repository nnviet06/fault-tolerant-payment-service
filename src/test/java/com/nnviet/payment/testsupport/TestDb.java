package com.nnviet.payment.testsupport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared JDBC plumbing for DB-backed tests. Talks to the docker-compose
 * Postgres; tests calling available() first skip (not fail) when it is down.
 */
public final class TestDb {

    public static final String URL = "jdbc:postgresql://localhost:5432/userdb";
    public static final String USER = "user123";
    public static final String PASSWORD = "user123";

    public static final String SKIP_MESSAGE =
            "requires the docker-compose Postgres (run: docker compose up -d)";

    private TestDb() {
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static boolean available() {
        try (Connection ignored = open()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public static void ensureSchema() throws Exception {
        String ddl;
        try (InputStream in = TestDb.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not on test classpath");
            }
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    public static void truncateAll() throws SQLException {
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE idempotency_keys, outbox, ledger_entries, holds, wallets CASCADE");
        }
    }
}
