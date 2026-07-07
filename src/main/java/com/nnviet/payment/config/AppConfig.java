package com.nnviet.payment.config;

/**
 * Typed environment configuration. Defaults match docker-compose.yml so
 * `docker compose up -d && java -jar ...` works with zero configuration.
 */
public record AppConfig(
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String redisHost,
        int redisPort,
        int idemCacheTtlSeconds,
        int httpPort,
        long relayIntervalMs,
        int relayBatchSize,
        long sweepIntervalMs,
        int sweepBatchSize,
        String publisher,
        String eventSinkPath) {

    public static AppConfig fromEnv() {
        return new AppConfig(
                env("DB_HOST", "localhost"),
                envInt("DB_PORT", 5432),
                env("DB_NAME", "userdb"),
                env("DB_USER", "user123"),
                env("DB_PASSWORD", "user123"),
                envInt("DB_POOL_SIZE", 16),
                env("REDIS_HOST", "localhost"),
                envInt("REDIS_PORT", 6379),
                envInt("IDEM_CACHE_TTL_SECONDS", 86_400),
                envInt("HTTP_PORT", 8080),
                envInt("RELAY_INTERVAL_MS", 250),
                envInt("RELAY_BATCH_SIZE", 50),
                envInt("SWEEP_INTERVAL_MS", 1_000),
                envInt("SWEEP_BATCH_SIZE", 100),
                env("PUBLISHER", "log"),
                env("EVENT_SINK_PATH", "target/events.jsonl"));
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(dbHost, dbPort, dbName);
    }

    public String redisUri() {
        return "redis://%s:%d".formatted(redisHost, redisPort);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(v.trim());
    }
}
