package com.nnviet.payment.torture.scenarios;

import com.nnviet.payment.torture.ServiceProcess;
import com.nnviet.payment.torture.TortureHarness;
import com.nnviet.payment.torture.TortureReport;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nnviet.payment.torture.TortureHarness.check;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversary 3, second window: the process dies AFTER handing the event to the
 * publisher but BEFORE marking it published. The mark rolls back, so the
 * restarted relay publishes the same event again: the sink shows the raw
 * duplicate (at-least-once is real, not claimed away), and deduplication by
 * event_id reduces it to exactly one distinct event.
 */
class KillAfterPublishTortureIT {

    private static final int PORT = 8186;

    @Test
    void killAfterPublishBeforeMark() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        Path sink = Path.of("target", "torture-sink", "kill-after-publish.jsonl");
        Files.createDirectories(sink.getParent());
        Files.deleteIfExists(sink);
        try {
            // phase 1: relay armed to halt between publish and mark
            try (ServiceProcess armed = ServiceProcess.start(PORT, Map.of(
                    "CHAOS_HALT_AT", "relay-after-publish-before-mark",
                    "PUBLISHER", "file",
                    "EVENT_SINK_PATH", sink.toString(),
                    "RELAY_INTERVAL_MS", "200"))) {
                armed.awaitHealthy(Duration.ofSeconds(30));
                String wallet = TortureHarness.createWallet(PORT, "dup-victim");
                TortureHarness.HttpResult deposit =
                        TortureHarness.deposit(PORT, wallet, 3_000, "kill-publish-1");
                check(failures, deposit.status() == 201,
                        "the deposit itself must succeed, got " + deposit.status());
                boolean died = armed.awaitDeath(Duration.ofSeconds(15));
                metrics.put("process halted in relay window", String.valueOf(died));
                check(failures, died, "the relay must reach the chaos point and halt");
            }

            int publishedBeforeCrash = TortureHarness.readSink(sink).size();
            long pendingAfterCrash = TortureHarness.queryLong(
                    "SELECT count(*) FROM outbox WHERE published_at IS NULL");
            metrics.put("events published before crash", String.valueOf(publishedBeforeCrash));
            metrics.put("outbox still pending after crash (mark rolled back)",
                    String.valueOf(pendingAfterCrash));
            check(failures, publishedBeforeCrash == 1,
                    "exactly one publish must precede the crash, got " + publishedBeforeCrash);
            check(failures, pendingAfterCrash == 1,
                    "the mark must have rolled back with the crash");

            // phase 2: clean restart - the same event goes out again
            try (ServiceProcess restarted = ServiceProcess.start(PORT, Map.of(
                    "PUBLISHER", "file",
                    "EVENT_SINK_PATH", sink.toString(),
                    "RELAY_INTERVAL_MS", "200"))) {
                restarted.awaitHealthy(Duration.ofSeconds(30));
                boolean republished = TortureHarness.awaitTrue(Duration.ofSeconds(15),
                        () -> TortureHarness.readSink(sink).size() >= 2
                                && TortureHarness.queryLong(
                                "SELECT count(*) FROM outbox WHERE published_at IS NULL") == 0);
                check(failures, republished,
                        "the pending event must be republished and marked after restart");

                List<JsonObject> events = TortureHarness.readSink(sink);
                long distinctEventIds = events.stream()
                        .map(e -> e.getString("eventId")).distinct().count();
                long ledgerEntries = TortureHarness.queryLong(
                        "SELECT count(*) FROM ledger_entries");
                metrics.put("raw events in sink (at-least-once, duplicates expected)",
                        String.valueOf(events.size()));
                metrics.put("distinct event ids (consumer view after dedup)",
                        String.valueOf(distinctEventIds));
                metrics.put("ledger entries (money applied once)",
                        String.valueOf(ledgerEntries));
                check(failures, events.size() >= 2,
                        "the duplicate must be observable raw, got " + events.size());
                check(failures, distinctEventIds == 1,
                        "dedup by event_id must yield 1, got " + distinctEventIds);
                check(failures, ledgerEntries == 1, "money must have moved exactly once");
            }
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "kill-after-publish-before-mark",
                    "crash dual-write: process death between event publish and mark-as-sent",
                    "at-least-once relay (unmarked rows republish) + consumer dedup on event_id",
                    "CHAOS_HALT_AT=relay-after-publish-before-mark; deposit of 3000; "
                            + "hard kill mid-relay; clean restart",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
