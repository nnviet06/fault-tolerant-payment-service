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

import static com.nnviet.payment.torture.TortureHarness.check;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tamper evidence: a financial field is edited directly in SQL, bypassing the
 * application entirely. The hash chain must detect it at the exact row.
 * seq is a row id from an identity column (not reset by the harness's
 * TRUNCATE), so the target is located as "the 3rd entry", not a literal seq.
 */
class TamperEvidenceTortureIT {

    private static final int PORT = 8188;

    @Test
    void tamperEvidence() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT, Map.of())) {
            service.awaitHealthy(Duration.ofSeconds(30));
            String wallet = TortureHarness.createWallet(PORT, "audit-victim");
            for (int i = 1; i <= 5; i++) {
                TortureHarness.deposit(PORT, wallet, i * 1_000L, "tamper-" + i);
            }

            JsonObject before = TortureHarness.get(PORT, "/ledger/verify").json();
            metrics.put("chain valid before tampering", String.valueOf(before.getBoolean("valid")));
            metrics.put("entries checked", String.valueOf(before.getLong("checkedCount")));
            check(failures, Boolean.TRUE.equals(before.getBoolean("valid")),
                    "chain must verify before the attack");

            // the attack: +1 minor unit on the 3rd entry, straight through SQL
            long target = TortureHarness.queryLong(
                    "SELECT seq FROM ledger_entries ORDER BY seq OFFSET 2 LIMIT 1");
            TortureHarness.executeSql(
                    "UPDATE ledger_entries SET amount = amount + 1 WHERE seq = " + target);

            JsonObject after = TortureHarness.get(PORT, "/ledger/verify").json();
            metrics.put("tampered seq (3rd entry)", String.valueOf(target));
            metrics.put("chain valid after tampering", String.valueOf(after.getBoolean("valid")));
            metrics.put("first broken seq reported", String.valueOf(after.getLong("firstBrokenSeq")));
            metrics.put("reason reported", String.valueOf(after.getString("reason")));
            check(failures, Boolean.FALSE.equals(after.getBoolean("valid")),
                    "tampering must invalidate the chain");
            check(failures, Long.valueOf(target).equals(after.getLong("firstBrokenSeq")),
                    "detection must point at seq " + target + ", got "
                            + after.getLong("firstBrokenSeq"));
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "tamper-evidence",
                    "out-of-band mutation of stored ledger data (DBA-level attack)",
                    "per-wallet SHA-256 hash chains over the financial columns; "
                            + "full recompute via GET /ledger/verify",
                    "5 deposits, then UPDATE ledger_entries SET amount = amount + 1 "
                            + "on the 3rd entry via direct SQL",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
