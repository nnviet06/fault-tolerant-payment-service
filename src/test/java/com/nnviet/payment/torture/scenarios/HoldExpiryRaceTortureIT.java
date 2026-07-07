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
 * The commit-vs-expiry race: holds with a ~2s TTL are committed at the same
 * moment the sweeper expires them. Which side wins any individual race is
 * nondeterministic BY DESIGN - the invariant under test is that every hold
 * has exactly ONE winner (the status CAS) and that money is conserved
 * whichever way each race lands.
 */
class HoldExpiryRaceTortureIT {

    private static final int PORT = 8187;
    private static final int HOLDS = 20;
    private static final long HOLD_AMOUNT = 1_000;

    @Test
    void commitVsExpiryRace() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT,
                Map.of("SWEEP_INTERVAL_MS", "200"))) {
            service.awaitHealthy(Duration.ofSeconds(30));
            String source = TortureHarness.createWallet(PORT, "race-source");
            String destination = TortureHarness.createWallet(PORT, "race-destination");
            TortureHarness.deposit(PORT, source, 100_000, "race-seed");

            long firstCreateAt = System.currentTimeMillis();
            List<String> holdIds = new ArrayList<>();
            for (int i = 0; i < HOLDS; i++) {
                TortureHarness.HttpResult r = TortureHarness.post(PORT, "/holds",
                        new JsonObject().put("walletId", source)
                                .put("amount", HOLD_AMOUNT).put("ttlSeconds", 2),
                        "race-hold-" + i);
                if (r.status() != 201) {
                    throw new IllegalStateException("hold creation failed: " + r.body());
                }
                holdIds.add(r.json().getJsonObject("hold").getString("id"));
            }

            // aim the commit burst at the expiry boundary of the 2s TTLs and
            // stagger individual commits across it, so both sides of the race
            // genuinely win some (the exact split stays nondeterministic)
            long sleepMs = Math.max(0, 1_900 - (System.currentTimeMillis() - firstCreateAt));
            Thread.sleep(sleepMs);

            List<Integer> statuses = TortureHarness.runConcurrently(HOLDS, i -> () -> {
                Thread.sleep(i * 25L);
                return TortureHarness.post(PORT, "/holds/" + holdIds.get(i) + "/commit",
                        new JsonObject().put("destinationWalletId", destination),
                        "race-commit-" + i).status();
            });

            boolean settled = TortureHarness.awaitTrue(Duration.ofSeconds(20), () ->
                    TortureHarness.queryLong(
                            "SELECT count(*) FROM holds WHERE status = 'ACTIVE'") == 0
                            && TortureHarness.queryLong(
                            "SELECT held_amount FROM wallets WHERE id = ?",
                            UUID.fromString(source)) == 0);
            check(failures, settled,
                    "every hold must reach a terminal state and held_amount must return to 0");

            long commitWins = statuses.stream().filter(s -> s == 200).count();
            long commitLosses = statuses.stream().filter(s -> s == 409).count();
            long other = HOLDS - commitWins - commitLosses;
            Map<String, Long> statusCounts = TortureHarness.holdStatusCounts();
            long committed = statusCounts.getOrDefault("COMMITTED", 0L);
            long expired = statusCounts.getOrDefault("EXPIRED", 0L);
            long sourceBalance = TortureHarness.queryLong(
                    "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(source));
            long destBalance = TortureHarness.queryLong(
                    "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(destination));
            long ledgerCommits = TortureHarness.queryLong(
                    "SELECT count(*) FROM ledger_entries WHERE entry_type = 'HOLD_COMMIT'");

            metrics.putAll(TortureReport.metrics(
                    "holds (2s TTL, committed at the boundary)", HOLDS,
                    "hold amount each", HOLD_AMOUNT,
                    "commit responses 200 (commit won)", commitWins,
                    "commit responses 409 (expiry/CAS won)", commitLosses,
                    "other responses", other,
                    "holds COMMITTED", committed,
                    "holds EXPIRED", expired,
                    "ledger HOLD_COMMIT entries", ledgerCommits,
                    "source balance", sourceBalance,
                    "destination balance", destBalance,
                    "held_amount after settling", 0));

            check(failures, other == 0, other + " commit responses were neither 200 nor 409");
            check(failures, committed + expired == HOLDS,
                    "every hold must end COMMITTED or EXPIRED: " + statusCounts);
            check(failures, committed == commitWins,
                    "COMMITTED holds (" + committed + ") must equal commit wins (" + commitWins + ")");
            check(failures, ledgerCommits == committed,
                    "each committed hold must have exactly one ledger entry");
            check(failures, sourceBalance == 100_000 - HOLD_AMOUNT * committed,
                    "source conservation broken: " + sourceBalance);
            check(failures, destBalance == HOLD_AMOUNT * committed,
                    "destination conservation broken: " + destBalance);
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "commit-vs-expiry-race",
                    "concurrent hold commit racing the TTL expiry sweeper",
                    "single compare-and-set status transition "
                            + "(UPDATE ... WHERE status = 'ACTIVE') under the wallet row lock",
                    HOLDS + " holds with 2s TTL; concurrent commits fired at the expiry "
                            + "boundary against a 200ms sweeper; win split is expectedly "
                            + "nondeterministic, invariants are not",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
