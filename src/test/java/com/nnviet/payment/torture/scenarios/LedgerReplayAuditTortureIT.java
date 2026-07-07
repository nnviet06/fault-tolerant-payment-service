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
 * Capstone audit: after a mixed workload (deposits, withdrawals, transfers,
 * holds that commit / roll back / expire), every wallet balance must be
 * reproducible from the append-only ledger alone, and the global money
 * equation must hold: sum(balances) = deposits - withdrawals.
 */
class LedgerReplayAuditTortureIT {

    private static final int PORT = 8189;

    @Test
    void ledgerReplayAudit() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT,
                Map.of("SWEEP_INTERVAL_MS", "200"))) {
            service.awaitHealthy(Duration.ofSeconds(30));

            List<String> wallets = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                wallets.add(TortureHarness.createWallet(PORT, "audit-" + i));
            }
            // deposits: 110_000 total in
            long[] deposits = {50_000, 30_000, 20_000, 10_000};
            for (int i = 0; i < 4; i++) {
                TortureHarness.deposit(PORT, wallets.get(i), deposits[i], "audit-dep-" + i);
            }
            // transfers: internal, must conserve
            transfer(wallets.get(0), wallets.get(1), 5_000, "audit-tr-0");
            transfer(wallets.get(1), wallets.get(2), 3_000, "audit-tr-1");
            transfer(wallets.get(2), wallets.get(3), 1_500, "audit-tr-2");
            transfer(wallets.get(3), wallets.get(0), 500, "audit-tr-3");
            transfer(wallets.get(0), wallets.get(2), 2_500, "audit-tr-4");
            // withdrawals: 10_000 total out
            TortureHarness.post(PORT, "/withdrawals", new JsonObject()
                    .put("walletId", wallets.get(1)).put("amount", 4_000), "audit-wd-0");
            TortureHarness.post(PORT, "/withdrawals", new JsonObject()
                    .put("walletId", wallets.get(0)).put("amount", 6_000), "audit-wd-1");
            // holds: one committed, one rolled back, one left to expire
            String committedHold = createHold(wallets.get(0), 2_000, 60, "audit-h-0");
            String rolledBackHold = createHold(wallets.get(1), 1_000, 60, "audit-h-1");
            createHold(wallets.get(2), 500, 1, "audit-h-2"); // expires via sweeper
            TortureHarness.post(PORT, "/holds/" + committedHold + "/commit",
                    new JsonObject().put("destinationWalletId", wallets.get(3)), "audit-hc-0");
            TortureHarness.post(PORT, "/holds/" + rolledBackHold + "/rollback",
                    new JsonObject(), "audit-hr-0");

            boolean quiesced = TortureHarness.awaitTrue(Duration.ofSeconds(20), () ->
                    TortureHarness.queryLong(
                            "SELECT count(*) FROM holds WHERE status = 'ACTIVE'") == 0
                            && TortureHarness.queryLong(
                            "SELECT coalesce(sum(held_amount), 0) FROM wallets") == 0);
            check(failures, quiesced, "all holds must settle and held_amount return to 0");

            // the audit: rebuild every balance from the ledger alone
            int mismatches = 0;
            StringBuilder detail = new StringBuilder();
            for (String w : wallets) {
                UUID id = UUID.fromString(w);
                long credits = TortureHarness.queryLong(
                        "SELECT coalesce(sum(amount), 0) FROM ledger_entries "
                                + "WHERE credit_wallet_id = ?", id);
                long debits = TortureHarness.queryLong(
                        "SELECT coalesce(sum(amount), 0) FROM ledger_entries "
                                + "WHERE debit_wallet_id = ?", id);
                long stored = TortureHarness.queryLong(
                        "SELECT balance FROM wallets WHERE id = ?", id);
                if (credits - debits != stored) {
                    mismatches++;
                    detail.append(w).append(": ledger says ").append(credits - debits)
                            .append(", wallet row says ").append(stored).append("; ");
                }
            }
            long entries = TortureHarness.queryLong("SELECT count(*) FROM ledger_entries");
            long totalBalances = TortureHarness.queryLong(
                    "SELECT coalesce(sum(balance), 0) FROM wallets");
            JsonObject chain = TortureHarness.get(PORT, "/ledger/verify").json();
            Map<String, Long> holdStates = TortureHarness.holdStatusCounts();

            metrics.putAll(TortureReport.metrics(
                    "wallets", wallets.size(),
                    "ledger entries", entries,
                    "hold end states", holdStates.toString(),
                    "wallets whose balance replays from the ledger", (4 - mismatches) + "/4",
                    "total balances", totalBalances,
                    "deposits - withdrawals (expected total)", 110_000 - 10_000,
                    "chain valid", chain.getBoolean("valid")));

            check(failures, mismatches == 0, "replay mismatches: " + detail);
            check(failures, totalBalances == 100_000,
                    "money equation broken: total " + totalBalances + " != 100000");
            check(failures, Boolean.TRUE.equals(chain.getBoolean("valid")),
                    "hash chain must verify after the workload");
            check(failures, holdStates.getOrDefault("COMMITTED", 0L) == 1
                            && holdStates.getOrDefault("ROLLED_BACK", 0L) == 1
                            && holdStates.getOrDefault("EXPIRED", 0L) == 1,
                    "hold lifecycle mix must be 1 committed / 1 rolled back / 1 expired: "
                            + holdStates);
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "ledger-replay-audit",
                    "silent drift between the ledger and wallet balances",
                    "append-only balanced ledger rows as the single source of truth; "
                            + "double-entry conservation",
                    "4 wallets; 4 deposits (110000), 5 transfers, 2 withdrawals (10000), "
                            + "3 holds (commit / rollback / expire); then full replay audit",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private void transfer(String from, String to, long amount, String key) throws Exception {
        TortureHarness.HttpResult r = TortureHarness.post(PORT, "/transfers",
                new JsonObject().put("fromWalletId", from).put("toWalletId", to)
                        .put("amount", amount), key);
        if (r.status() != 201) {
            throw new IllegalStateException("transfer failed: " + r.body());
        }
    }

    private String createHold(String walletId, long amount, int ttlSeconds, String key)
            throws Exception {
        TortureHarness.HttpResult r = TortureHarness.post(PORT, "/holds",
                new JsonObject().put("walletId", walletId).put("amount", amount)
                        .put("ttlSeconds", ttlSeconds), key);
        if (r.status() != 201) {
            throw new IllegalStateException("hold failed: " + r.body());
        }
        return r.json().getJsonObject("hold").getString("id");
    }
}
