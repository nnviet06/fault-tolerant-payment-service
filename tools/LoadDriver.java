import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone, JDK-only throughput driver for the fault-tolerant-payment-service.
 * Measures sustained COMMITTED transfers/sec (HTTP 201) through the full atomic
 * boundary, plus the non-201 breakdown (error/timeout rate) at each client level.
 *
 * Not part of the Maven build: depends only on java.net.http + java.util.concurrent
 * so it compiles with a bare `javac` and runs with a bare `java`, no classpath.
 *
 *   javac -d target/bench tools/LoadDriver.java
 *   java -cp target/bench LoadDriver [baseUrl] [levelsCSV] [warmupSec] [measureSec] [cooldownSec] [wallets]
 *
 * Defaults: http://localhost:8080  16,32,64  10  30  10  20
 *
 * The service (java -jar target/...jar) and docker compose (Postgres+Redis) must
 * already be running.
 */
public final class LoadDriver {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static volatile boolean measuring = false;
    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        String baseUrl   = arg(args, 0, "http://localhost:8080");
        String levelsCsv = arg(args, 1, "16,32,64");
        int warmupSec    = Integer.parseInt(arg(args, 2, "10"));
        int measureSec   = Integer.parseInt(arg(args, 3, "30"));
        int cooldownSec  = Integer.parseInt(arg(args, 4, "10"));
        int walletCount  = Integer.parseInt(arg(args, 5, "20"));

        int[] levels = parseLevels(levelsCsv);

        System.out.println("== LoadDriver ==");
        System.out.println("baseUrl=" + baseUrl + " levels=" + levelsCsv
                + " warmupSec=" + warmupSec + " measureSec=" + measureSec
                + " cooldownSec=" + cooldownSec + " wallets=" + walletCount);
        System.out.println("started " + java.time.Instant.now());

        // one shared, heavily-seeded wallet pool for the whole sweep
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < walletCount; i++) {
            String id = createWallet(baseUrl, "load-" + i);
            deposit(baseUrl, id, 1_000_000_000_000L, UUID.randomUUID().toString());
            wallets.add(id);
        }
        System.out.println("seeded " + wallets.size() + " wallets @ 1e12 minor units each\n");

        List<String> summary = new ArrayList<>();
        summary.add("| clients | window s | committed (201) | throughput tps | non-201 total | breakdown |");
        summary.add("|---|---|---|---|---|---|");

        for (int idx = 0; idx < levels.length; idx++) {
            int clients = levels[idx];
            String row = runLevel(baseUrl, wallets, clients, warmupSec, measureSec);
            summary.add(row);
            if (idx < levels.length - 1) {
                System.out.println("cooldown " + cooldownSec + "s (let DB pool drain)...\n");
                Thread.sleep(cooldownSec * 1000L);
            }
        }

        System.out.println("\n== SWEEP SUMMARY ==");
        summary.forEach(System.out::println);
        System.out.println("\nfinished " + java.time.Instant.now());
    }

    private static String runLevel(String baseUrl, List<String> wallets, int clients,
                                   int warmupSec, int measureSec) throws Exception {
        System.out.println("--- level: " + clients + " clients ---");
        measuring = false;
        running = true;

        AtomicLong committed = new AtomicLong();          // 201 during measure window
        ConcurrentHashMap<Integer, AtomicLong> byStatus = new ConcurrentHashMap<>();
        AtomicLong timeouts = new AtomicLong();
        AtomicLong otherErrors = new AtomicLong();

        List<Thread> threads = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(clients);
        for (int c = 0; c < clients; c++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                while (running) {
                    String from = wallets.get(ThreadLocalRandom.current().nextInt(wallets.size()));
                    String to;
                    do {
                        to = wallets.get(ThreadLocalRandom.current().nextInt(wallets.size()));
                    } while (to.equals(from));
                    boolean inWindow = measuring;
                    int status = doTransfer(baseUrl, from, to, timeouts, otherErrors, inWindow);
                    if (inWindow && status > 0) {
                        if (status == 201) {
                            committed.incrementAndGet();
                        } else {
                            byStatus.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
                        }
                    }
                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        ready.await();

        Thread.sleep(warmupSec * 1000L);          // warm-up: results ignored
        // reset window counters right before measuring
        committed.set(0);
        byStatus.clear();
        timeouts.set(0);
        otherErrors.set(0);
        long t0 = System.nanoTime();
        measuring = true;
        Thread.sleep(measureSec * 1000L);
        measuring = false;
        long windowNanos = System.nanoTime() - t0;
        running = false;
        for (Thread t : threads) {
            t.join(60_000);
        }

        double windowSec = windowNanos / 1_000_000_000.0;
        long ok = committed.get();
        long to = timeouts.get();
        long other = otherErrors.get();
        long nonStatus = byStatus.values().stream().mapToLong(AtomicLong::get).sum();
        long non201 = nonStatus + to + other;
        double tps = ok / windowSec;

        StringBuilder breakdown = new StringBuilder();
        byStatus.forEach((code, n) -> breakdown.append("HTTP ").append(code)
                .append("=").append(n.get()).append(" "));
        if (to > 0) breakdown.append("timeout=").append(to).append(" ");
        if (other > 0) breakdown.append("other-exc=").append(other).append(" ");
        if (breakdown.length() == 0) breakdown.append("none");

        System.out.printf("clients=%d window=%.2fs committed=%d tps=%.1f non201=%d [%s]%n%n",
                clients, windowSec, ok, tps, non201, breakdown.toString().trim());

        return String.format("| %d | %.2f | %d | %.1f | %d | %s |",
                clients, windowSec, ok, tps, non201, breakdown.toString().trim());
    }

    private static int doTransfer(String baseUrl, String from, String to,
                                  AtomicLong timeouts, AtomicLong otherErrors, boolean inWindow) {
        String body = "{\"fromWalletId\":\"" + from + "\",\"toWalletId\":\"" + to
                + "\",\"amount\":1}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/transfers"))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode();
        } catch (java.net.http.HttpTimeoutException e) {
            if (inWindow) timeouts.incrementAndGet();
            return -1;
        } catch (Exception e) {
            if (inWindow) otherErrors.incrementAndGet();
            return -1;
        }
    }

    // ---------- setup helpers ----------

    private static String createWallet(String baseUrl, String owner) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/wallets"))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ownerName\":\"" + owner + "\"}"))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 201) {
            throw new IllegalStateException("wallet create failed: " + r.statusCode() + " " + r.body());
        }
        return extract(r.body(), "id");
    }

    private static void deposit(String baseUrl, String walletId, long amount, String idemKey)
            throws Exception {
        String body = "{\"walletId\":\"" + walletId + "\",\"amount\":" + amount + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/deposits"))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 201) {
            throw new IllegalStateException("seed deposit failed: " + r.statusCode() + " " + r.body());
        }
    }

    /** Minimal JSON string-field extractor: "field":"value". */
    private static String extract(String json, String field) {
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) throw new IllegalStateException("field " + field + " not in " + json);
        int colon = json.indexOf(':', i + needle.length());
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(q1 + 1, q2);
    }

    private static int[] parseLevels(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static String arg(String[] args, int i, String fallback) {
        return (args.length > i && !args[i].isBlank()) ? args[i] : fallback;
    }
}
