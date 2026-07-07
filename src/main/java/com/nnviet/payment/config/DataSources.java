package com.nnviet.payment.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Builds the two data-access clients:
 * - HikariCP JDBC pool (blocking, used only from worker threads via Tx)
 * - Mutiny Redis client (optional at runtime: only the idempotency fast path)
 */
public final class DataSources {

    private DataSources() {
    }

    public static HikariDataSource createPool(AppConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl());
        hc.setUsername(cfg.dbUser());
        hc.setPassword(cfg.dbPassword());
        hc.setMaximumPoolSize(cfg.dbPoolSize());
        hc.setPoolName("payment-db");
        return new HikariDataSource(hc);
    }

    /** Applies src/main/resources/schema.sql (idempotent DDL) at boot. */
    public static void applySchema(DataSource dataSource) throws SQLException, IOException {
        String ddl;
        try (InputStream in = DataSources.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    public static RedisAPI createRedis(Vertx vertx, AppConfig cfg) {
        return RedisAPI.api(Redis.createClient(vertx, cfg.redisUri()));
    }
}
