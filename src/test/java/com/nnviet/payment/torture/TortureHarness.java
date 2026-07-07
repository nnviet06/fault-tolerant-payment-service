package com.nnviet.payment.torture;

import com.nnviet.payment.testsupport.TestDb;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** HTTP + database helpers shared by all torture scenarios. */
public final class TortureHarness {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private TortureHarness() {
    }

    // ---------- lifecycle ----------

    /** Skips (not fails) the scenario when the docker-compose Postgres is down. */
    public static void requireInfrastructure() throws Exception {
        Assumptions.assumeTrue(TestDb.available(), TestDb.SKIP_MESSAGE);
        TestDb.ensureSchema();
        TestDb.truncateAll();
        // Postgres AND Redis must both start clean: a stale idempotency cache
        // from a previous run would (correctly) reject recycled scenario keys
        // as key reuse. Best-effort - a down Redis simply means no cache.
        flushRedis();
    }

    private static void flushRedis() {
        try (java.net.Socket socket = new java.net.Socket("localhost", 6379)) {
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write("*1\r\n$8\r\nFLUSHALL\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.getInputStream().read(new byte[16]); // consume "+OK\r\n"
        } catch (Exception ignored) {
            // Redis unreachable: nothing cached, nothing to flush
        }
    }

    // ---------- HTTP ----------

    public record HttpResult(int status, String body, String replayHeader) {

        public JsonObject json() {
            return new JsonObject(body);
        }
    }

    public static HttpResult post(int port, String path, JsonObject body, String idemKey)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "{}" : body.encode()));
        if (idemKey != null) {
            builder.header("Idempotency-Key", idemKey);
        }
        HttpResponse<String> response =
                HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body(),
                response.headers().firstValue("X-Idempotent-Replay").orElse(null));
    }

    public static HttpResult get(int port, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body(), null);
    }

    public static String createWallet(int port, String owner) throws Exception {
        HttpResult result = post(port, "/wallets",
                new JsonObject().put("ownerName", owner), null);
        if (result.status() != 201) {
            throw new IllegalStateException("wallet creation failed: " + result.body());
        }
        return result.json().getString("id");
    }

    public static HttpResult deposit(int port, String walletId, long amount, String idemKey)
            throws Exception {
        return post(port, "/deposits",
                new JsonObject().put("walletId", walletId).put("amount", amount), idemKey);
    }

    // ---------- concurrency ----------

    /**
     * Runs n tasks with a start latch so they hit the service as one burst.
     * Exceptions are surfaced, not swallowed.
     */
    public static <T> List<T> runConcurrently(int n, IntFunction<Callable<T>> taskFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Polls until the probe satisfies the condition or the timeout passes. */
    public static boolean awaitTrue(Duration timeout, Supplier<Boolean> probe)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(probe.get())) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return false;
    }

    // ---------- direct database probes ----------

    public static long queryLong(String sql, Object... params) {
        try (Connection conn = TestDb.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("probe query failed: " + sql, e);
        }
    }

    public static Map<String, Long> holdStatusCounts() {
        Map<String, Long> counts = new HashMap<>();
        try (Connection conn = TestDb.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status, count(*) FROM holds GROUP BY status");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getLong(2));
            }
            return counts;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void executeSql(String sql) {
        try (Connection conn = TestDb.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- event sink ----------

    public static List<JsonObject> readSink(Path sink) {
        try {
            if (!Files.exists(sink)) {
                return List.of();
            }
            List<JsonObject> events = new ArrayList<>();
            for (String line : Files.readAllLines(sink, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(new JsonObject(line));
                }
            }
            return events;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- assertion collection ----------

    public static void check(List<String> failures, boolean condition, String message) {
        if (!condition) {
            failures.add(message);
        }
    }
}
