import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Standalone, JDK-only recovery-latency benchmark for the transactional outbox.
 *
 * Metric [X]: time from service-ready-after-restart to the previously pending
 * outbox event being published, in the kill-after-commit window. This mirrors
 * KillAfterCommitTortureIT exactly, but instruments the timing and repeats it.
 *
 * Per iteration:
 *   phase 1 - start the shaded jar armed with CHAOS_HALT_AT=after-commit and the
 *             relay effectively disabled (RELAY_INTERVAL_MS huge); POST a deposit;
 *             the process hard-halts (Runtime.halt) right after the DB commit, so
 *             the outbox row is committed but unpublished.
 *   phase 2 - restart clean with RELAY_INTERVAL_MS=250; t0 = first /health==200,
 *             t1 = event sink first non-empty; X = t1 - t0.
 *
 * No production code is touched; all timing lives here. No DB truncation is needed:
 * each iteration uses its own sink file + idem key + fresh wallet, and older outbox
 * rows are already marked published, so only this iteration's row is pending on
 * restart.
 *
 *   javac -d target/bench tools/RecoveryBench.java
 *   java -cp target/bench RecoveryBench <jarPath> [iterations] [port] [relayIntervalMs]
 *
 * Defaults: iterations=20 port=8190 relayIntervalMs=250
 * Requires docker compose (Postgres+Redis) up.
 */
public final class RecoveryBench {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RecoveryBench <jarPath> [iterations] [port] [relayIntervalMs]");
            System.exit(2);
        }
        String jar = args[0];
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8190;
        int relayIntervalMs = args.length > 3 ? Integer.parseInt(args[3]) : 250;

        if (!new File(jar).isFile()) {
            System.err.println("jar not found: " + jar);
            System.exit(2);
        }

        System.out.println("== RecoveryBench ==");
        System.out.println("jar=" + jar + " iterations=" + iterations + " port=" + port
                + " relayIntervalMs=" + relayIntervalMs);
        System.out.println("started " + java.time.Instant.now() + "\n");

        Path sinkDir = Path.of("target", "bench-sink");
        Files.createDirectories(sinkDir);

        // Per-run nonce: the DB is not truncated between runs, so idempotency keys
        // must be unique per run or a rerun would replay a prior run's stored
        // response (replayed outcomes skip the chaos halt, breaking the scenario).
        String run = Long.toHexString(System.nanoTime());
        System.out.println("run nonce=" + run + "\n");

        List<Double> samples = new ArrayList<>();
        int failures = 0;

        for (int i = 0; i < iterations; i++) {
            Path sink = sinkDir.resolve("recovery-" + run + "-" + i + ".jsonl");
            Files.deleteIfExists(sink);
            try {
                double x = oneIteration(jar, port, relayIntervalMs, sink, run + "-" + i);
                if (x >= 0) {
                    samples.add(x);
                    System.out.printf("iter %2d: X = %.1f ms%n", i, x);
                } else {
                    failures++;
                    System.out.printf("iter %2d: FAILED%n", i);
                }
            } catch (Exception e) {
                failures++;
                System.out.printf("iter %2d: EXCEPTION %s%n", i, e);
            }
        }

        System.out.println("\n== RESULT ==");
        System.out.println("samples: " + samples);
        System.out.println("iterations requested: " + iterations
                + ", succeeded: " + samples.size() + ", failed: " + failures);
        if (!samples.isEmpty()) {
            List<Double> sorted = new ArrayList<>(samples);
            sorted.sort(Double::compareTo);
            double median = median(sorted);
            double min = sorted.get(0);
            double max = sorted.get(sorted.size() - 1);
            System.out.printf("median = %.1f ms | min = %.1f ms | max = %.1f ms | n = %d%n",
                    median, min, max, sorted.size());
        }
        System.out.println("\nfinished " + java.time.Instant.now());
    }

    private static double oneIteration(String jar, int port, int relayIntervalMs,
                                       Path sink, String iter) throws Exception {
        // ---- phase 1: arm, deposit, self-halt after commit ----
        Process armed = start(jar, port,
                "CHAOS_HALT_AT", "after-commit",
                "PUBLISHER", "file",
                "EVENT_SINK_PATH", sink.toString(),
                "RELAY_INTERVAL_MS", "3600000");
        try {
            if (!awaitHealthy(port, Duration.ofSeconds(30), null)) {
                armed.destroyForcibly();
                throw new IllegalStateException("armed service not healthy");
            }
            String wallet = createWallet(port, "rec-victim-" + iter);
            try {
                deposit(port, wallet, 7_000, "rec-commit-" + iter);
                // if we got a response, the halt did not fire as expected
                armed.destroyForcibly();
                throw new IllegalStateException("deposit returned; chaos halt did not fire");
            } catch (java.io.IOException expected) {
                // connection died at the halt - the intended outcome
            }
            if (!armed.waitFor(15, TimeUnit.SECONDS)) {
                armed.destroyForcibly();
                throw new IllegalStateException("process did not halt at after-commit");
            }
        } finally {
            if (armed.isAlive()) armed.destroyForcibly();
        }

        // ---- phase 2: clean restart, measure publish latency ----
        Process restarted = start(jar, port,
                "PUBLISHER", "file",
                "EVENT_SINK_PATH", sink.toString(),
                "RELAY_INTERVAL_MS", String.valueOf(relayIntervalMs));
        try {
            long[] readyAt = new long[1];
            if (!awaitHealthy(port, Duration.ofSeconds(30), readyAt)) {
                throw new IllegalStateException("restarted service not healthy");
            }
            long t0 = readyAt[0];                       // service-ready instant
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            long t1 = -1;
            while (System.nanoTime() < deadline) {
                if (sinkNonEmpty(sink)) {
                    t1 = System.nanoTime();
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(2);
            }
            if (t1 < 0) {
                throw new IllegalStateException("event never published within 15s");
            }
            // settle: let the relay commit markPublished before we kill it, so the
            // row does not leak as still-pending into a later iteration.
            TimeUnit.MILLISECONDS.sleep(500);
            return (t1 - t0) / 1_000_000.0;
        } finally {
            restarted.destroyForcibly();
            restarted.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static Process start(String jar, int port, String... env) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", jar);
        pb.environment().put("HTTP_PORT", String.valueOf(port));
        for (int i = 0; i + 1 < env.length; i += 2) {
            pb.environment().put(env[i], env[i + 1]);
        }
        File logDir = new File("target/bench-logs");
        logDir.mkdirs();
        File log = new File(logDir, "svc-" + port + "-" + System.nanoTime() + ".log");
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        return pb.start();
    }

    /** Polls /health every 5ms; on first 200, records the instant into readyAt[0] if non-null. */
    private static boolean awaitHealthy(int port, Duration timeout, long[] readyAt) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> r = HTTP.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                                .timeout(Duration.ofSeconds(1)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    if (readyAt != null) readyAt[0] = System.nanoTime();
                    return true;
                }
            } catch (Exception ignored) {
                // not listening yet
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return false;
    }

    private static boolean sinkNonEmpty(Path sink) {
        try {
            if (!Files.exists(sink)) return false;
            for (String line : Files.readAllLines(sink, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) return true;
            }
            return false;
        } catch (Exception e) {
            return false;   // mid-write; try again next poll
        }
    }

    private static String createWallet(int port, String owner) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/wallets"))
                        .timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"ownerName\":\"" + owner + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 201) {
            throw new IllegalStateException("wallet create failed: " + r.statusCode() + " " + r.body());
        }
        String json = r.body();
        int i = json.indexOf("\"id\"");
        int q1 = json.indexOf('"', json.indexOf(':', i) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(q1 + 1, q2);
    }

    private static void deposit(int port, String walletId, long amount, String idemKey)
            throws Exception {
        String body = "{\"walletId\":\"" + walletId + "\",\"amount\":" + amount + "}";
        HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/deposits"))
                        .timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json")
                        .header("Idempotency-Key", idemKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
