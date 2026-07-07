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
 * Adversary 1: the double-spend race. 100 concurrent withdrawals against a
 * balance that only fits 10 of them. The row lock must serialize them; the
 * losers must see the true balance and be rejected; money must be conserved.
 */
class DoubleSpendTortureIT {

    private static final int PORT = 8181;

    @Test
    void doubleSpend100Concurrent() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT, Map.of())) {
            service.awaitHealthy(Duration.ofSeconds(30));
            String wallet = TortureHarness.createWallet(PORT, "double-spend-victim");
            TortureHarness.deposit(PORT, wallet, 10_000, "double-spend-seed");

            long start = System.nanoTime();
            List<Integer> statuses = TortureHarness.runConcurrently(100, i -> () ->
                    TortureHarness.post(PORT, "/withdrawals",
                            new JsonObject().put("walletId", wallet).put("amount", 1_000),
                            "double-spend-" + i).status());
            long wallTimeMs = (System.nanoTime() - start) / 1_000_000;

            long succeeded = statuses.stream().filter(s -> s == 201).count();
            long rejected = statuses.stream().filter(s -> s == 422).count();
            long other = 100 - succeeded - rejected;
            long finalBalance = TortureHarness.queryLong(
                    "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(wallet));
            long ledgerWithdrawals = TortureHarness.queryLong(
                    "SELECT count(*) FROM ledger_entries WHERE entry_type = 'WITHDRAWAL'");

            metrics.putAll(TortureReport.metrics(
                    "concurrent withdrawals", 100,
                    "amount each", 1_000,
                    "seeded balance", 10_000,
                    "succeeded (201)", succeeded,
                    "rejected as insufficient (422)", rejected,
                    "other statuses", other,
                    "final balance", finalBalance,
                    "ledger WITHDRAWAL entries", ledgerWithdrawals,
                    "wall time ms", wallTimeMs));

            check(failures, succeeded == 10, "exactly 10 must succeed, got " + succeeded);
            check(failures, other == 0, other + " responses were neither 201 nor 422");
            check(failures, finalBalance == 0,
                    "conservation broken: expected balance 0, got " + finalBalance);
            check(failures, finalBalance >= 0, "balance went negative");
            check(failures, ledgerWithdrawals == succeeded,
                    "ledger entries (" + ledgerWithdrawals + ") != successes (" + succeeded + ")");
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "double-spend-100-concurrent",
                    "double-spend race: concurrent withdrawals racing the balance check",
                    "SELECT ... FOR UPDATE row lock; CHECK (balance >= 0) as last line",
                    "100 concurrent withdrawals of 1000 minor units against a balance of 10000",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
