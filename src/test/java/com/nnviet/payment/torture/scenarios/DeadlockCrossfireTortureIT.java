package com.nnviet.payment.torture.scenarios;

import com.nnviet.payment.torture.ServiceProcess;
import com.nnviet.payment.torture.TortureHarness;
import com.nnviet.payment.torture.TortureReport;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nnviet.payment.torture.TortureHarness.check;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crossed transfers: 50x A->B racing 50x B->A. Without deterministic lock
 * ordering this deadlocks (each side holds one wallet lock and wants the
 * other); Postgres would kill victims and 500s would surface. With id-ordered
 * locking, every request resolves and the two-wallet total is conserved.
 */
class DeadlockCrossfireTortureIT {

    private static final int PORT = 8182;

    @Test
    void transferDeadlockCrossfire() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT, Map.of())) {
            service.awaitHealthy(Duration.ofSeconds(30));
            String walletA = TortureHarness.createWallet(PORT, "crossfire-a");
            String walletB = TortureHarness.createWallet(PORT, "crossfire-b");
            TortureHarness.deposit(PORT, walletA, 50_000, "crossfire-seed-a");
            TortureHarness.deposit(PORT, walletB, 50_000, "crossfire-seed-b");

            long start = System.nanoTime();
            List<Integer> statuses = TortureHarness.runConcurrently(100, i -> () -> {
                String from = (i % 2 == 0) ? walletA : walletB;
                String to = (i % 2 == 0) ? walletB : walletA;
                return TortureHarness.post(PORT, "/transfers",
                        new JsonObject().put("fromWalletId", from).put("toWalletId", to)
                                .put("amount", 100),
                        "crossfire-" + i).status();
            });
            long wallTimeMs = (System.nanoTime() - start) / 1_000_000;

            long succeeded = statuses.stream().filter(s -> s == 201).count();
            long serverErrors = statuses.stream().filter(s -> s >= 500).count();
            long balanceA = TortureHarness.queryLong(
                    "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(walletA));
            long balanceB = TortureHarness.queryLong(
                    "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(walletB));

            metrics.putAll(TortureReport.metrics(
                    "crossed transfers", "50x A->B + 50x B->A, concurrent",
                    "amount each", 100,
                    "succeeded (201)", succeeded,
                    "server errors (5xx, deadlock symptom)", serverErrors,
                    "balance A after", balanceA,
                    "balance B after", balanceB,
                    "A+B total (must stay 100000)", balanceA + balanceB,
                    "wall time ms", wallTimeMs));

            check(failures, succeeded == 100,
                    "all 100 transfers must resolve successfully, got " + succeeded);
            check(failures, serverErrors == 0,
                    serverErrors + " server errors - deadlock victims killed by Postgres");
            check(failures, balanceA + balanceB == 100_000,
                    "two-wallet total drifted: " + (balanceA + balanceB));
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "transfer-deadlock-crossfire",
                    "deadlock: crossed transfers each holding one lock and wanting the other",
                    "deterministic lock ordering (both wallets locked in UUID order)",
                    "100 concurrent transfers of 100, alternating A->B / B->A",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
