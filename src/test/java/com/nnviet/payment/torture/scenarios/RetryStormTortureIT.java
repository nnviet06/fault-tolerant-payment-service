package com.nnviet.payment.torture.scenarios;

import com.nnviet.payment.torture.ServiceProcess;
import com.nnviet.payment.torture.TortureHarness;
import com.nnviet.payment.torture.TortureReport;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nnviet.payment.torture.TortureHarness.check;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversary 2: the retry duplicate. One deposit replayed 100 times with the
 * same Idempotency-Key (50 as a concurrent burst, 50 sequentially). Exactly
 * one application, identical responses for everyone, and key reuse with a
 * different payload rejected.
 */
class RetryStormTortureIT {

    private static final int PORT = 8183;

    @Test
    void retryStorm() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT, Map.of())) {
            service.awaitHealthy(Duration.ofSeconds(30));
            runStorm(PORT, failures, metrics);
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "retry-storm",
                    "retry duplicate: the same request replayed by clients and network",
                    "idempotency_keys PK arbitration in the money transaction "
                            + "+ stored-response replay + Redis fast path",
                    "1 deposit of 5000; same key replayed 50x concurrent + 50x sequential",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    /** Shared with the redis-blackout scenario: same storm, same invariants. */
    static void runStorm(int port, List<String> failures, LinkedHashMap<String, String> metrics)
            throws Exception {
        String wallet = TortureHarness.createWallet(port, "storm-victim");
        String stormKey = "retry-storm-key";
        JsonObject request = new JsonObject().put("walletId", wallet).put("amount", 5_000);

        List<TortureHarness.HttpResult> results = new ArrayList<>(TortureHarness
                .runConcurrently(50, i -> () ->
                        TortureHarness.post(port, "/deposits", request, stormKey)));
        for (int i = 0; i < 50; i++) {
            results.add(TortureHarness.post(port, "/deposits", request, stormKey));
        }

        long ok = results.stream().filter(r -> r.status() == 201).count();
        long firstApplications = results.stream()
                .filter(r -> "false".equals(r.replayHeader())).count();
        long replays = results.stream()
                .filter(r -> "true".equals(r.replayHeader())).count();
        Set<JsonObject> distinctBodies = new HashSet<>();
        results.forEach(r -> distinctBodies.add(r.json()));

        long ledgerEntries = TortureHarness.queryLong(
                "SELECT count(*) FROM ledger_entries");
        long keyRows = TortureHarness.queryLong(
                "SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", stormKey);
        long balance = TortureHarness.queryLong(
                "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(wallet));

        // same key, different payload: must be rejected, never replayed
        int reuseStatus = TortureHarness.post(port, "/deposits",
                new JsonObject().put("walletId", wallet).put("amount", 9_999),
                stormKey).status();

        metrics.putAll(TortureReport.metrics(
                "total replays sent", 100,
                "responses 201", ok,
                "first applications (X-Idempotent-Replay: false)", firstApplications,
                "replayed responses (X-Idempotent-Replay: true)", replays,
                "distinct response bodies (semantic)", distinctBodies.size(),
                "ledger entries", ledgerEntries,
                "idempotency key rows", keyRows,
                "final balance", balance,
                "key reuse w/ different payload", "HTTP " + reuseStatus,
                "redis vs postgres replay split",
                "UNMEASURED (not externally observable)"));

        check(failures, ok == 100, "all 100 must get a 201, got " + ok);
        check(failures, firstApplications == 1,
                "exactly one request may apply the money, got " + firstApplications);
        check(failures, replays == 99, "99 must be marked replayed, got " + replays);
        check(failures, distinctBodies.size() == 1,
                distinctBodies.size() + " distinct bodies - replays must match the original");
        check(failures, ledgerEntries == 1,
                "money applied " + ledgerEntries + " times, must be exactly 1");
        check(failures, keyRows == 1, "expected 1 idempotency row, got " + keyRows);
        check(failures, balance == 5_000, "balance must be 5000, got " + balance);
        check(failures, reuseStatus == 422,
                "key reuse with different payload must be 422, got " + reuseStatus);
    }
}
