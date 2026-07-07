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
import java.util.UUID;

import static com.nnviet.payment.torture.TortureHarness.check;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversary 3, first window: the process is hard-killed (Runtime.halt, no
 * shutdown hooks) right AFTER the money transaction committed and BEFORE any
 * event was published or a response sent. The outbox row committed atomically
 * with the ledger entry, so after a clean restart the relay must deliver the
 * event - nothing is lost - and the client's retry must replay the stored
 * response instead of double-applying.
 */
class KillAfterCommitTortureIT {

    private static final int PORT = 8185;

    @Test
    void killAfterCommitBeforePublish() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        Path sink = Path.of("target", "torture-sink", "kill-after-commit.jsonl");
        Files.createDirectories(sink.getParent());
        Files.deleteIfExists(sink);
        try {
            String wallet;
            // phase 1: service armed to halt after the workflow's commit;
            // relay effectively disabled so the crash window stays open
            try (ServiceProcess armed = ServiceProcess.start(PORT, Map.of(
                    "CHAOS_HALT_AT", "after-commit",
                    "PUBLISHER", "file",
                    "EVENT_SINK_PATH", sink.toString(),
                    "RELAY_INTERVAL_MS", "3600000"))) {
                armed.awaitHealthy(Duration.ofSeconds(30));
                wallet = TortureHarness.createWallet(PORT, "crash-victim");

                String killedRequestOutcome;
                try {
                    TortureHarness.HttpResult r =
                            TortureHarness.deposit(PORT, wallet, 7_000, "kill-commit-1");
                    killedRequestOutcome = "got HTTP " + r.status() + " (unexpected)";
                } catch (Exception e) {
                    killedRequestOutcome =
                            "no response - connection died (" + e.getClass().getSimpleName() + ")";
                }
                boolean died = armed.awaitDeath(Duration.ofSeconds(15));
                metrics.put("killed request outcome", killedRequestOutcome);
                metrics.put("process halted at chaos point", String.valueOf(died));
                check(failures, died, "the process must halt at the after-commit point");
            }

            // forensics: the commit survived, the event is pending, nothing published
            long entries = TortureHarness.queryLong("SELECT count(*) FROM ledger_entries");
            long pendingOutbox = TortureHarness.queryLong(
                    "SELECT count(*) FROM outbox WHERE published_at IS NULL");
            long balanceAfterCrash = TortureHarness.queryLong(
                    "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(wallet));
            int sinkLinesAfterCrash = TortureHarness.readSink(sink).size();
            metrics.put("ledger entries after crash", String.valueOf(entries));
            metrics.put("balance after crash (commit survived)", String.valueOf(balanceAfterCrash));
            metrics.put("outbox pending after crash", String.valueOf(pendingOutbox));
            metrics.put("events published before crash", String.valueOf(sinkLinesAfterCrash));
            check(failures, entries == 1, "the committed entry must survive the crash");
            check(failures, balanceAfterCrash == 7_000, "the committed balance must survive");
            check(failures, pendingOutbox == 1, "the event must be waiting in the outbox");
            check(failures, sinkLinesAfterCrash == 0, "nothing may be published pre-crash");

            // phase 2: clean restart - the relay must deliver the pending event
            try (ServiceProcess restarted = ServiceProcess.start(PORT, Map.of(
                    "PUBLISHER", "file",
                    "EVENT_SINK_PATH", sink.toString(),
                    "RELAY_INTERVAL_MS", "200"))) {
                restarted.awaitHealthy(Duration.ofSeconds(30));
                boolean published = TortureHarness.awaitTrue(Duration.ofSeconds(15),
                        () -> !TortureHarness.readSink(sink).isEmpty());
                check(failures, published, "the event must be published after restart");

                List<JsonObject> events = TortureHarness.readSink(sink);
                long distinctEventIds = events.stream()
                        .map(e -> e.getString("eventId")).distinct().count();
                metrics.put("events in sink after restart", String.valueOf(events.size()));
                metrics.put("distinct event ids", String.valueOf(distinctEventIds));
                check(failures, distinctEventIds == 1,
                        "exactly one distinct event, got " + distinctEventIds);

                // the client retry: must replay, not double-apply
                TortureHarness.HttpResult retry =
                        TortureHarness.deposit(PORT, wallet, 7_000, "kill-commit-1");
                long entriesAfterRetry = TortureHarness.queryLong(
                        "SELECT count(*) FROM ledger_entries");
                long finalBalance = TortureHarness.queryLong(
                        "SELECT balance FROM wallets WHERE id = ?", UUID.fromString(wallet));
                metrics.put("retry response", "HTTP " + retry.status()
                        + ", X-Idempotent-Replay: " + retry.replayHeader());
                metrics.put("ledger entries after retry", String.valueOf(entriesAfterRetry));
                metrics.put("final balance", String.valueOf(finalBalance));
                check(failures, retry.status() == 201 && "true".equals(retry.replayHeader()),
                        "retry must replay the stored response");
                check(failures, entriesAfterRetry == 1, "retry must not double-apply");
                check(failures, finalBalance == 7_000, "balance must stay exactly-once");
            }
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "kill-after-commit-before-publish",
                    "crash dual-write: process death between DB commit and event publish",
                    "transactional outbox (event row committed with the ledger entry) "
                            + "+ relay recovery after restart + stored-response replay",
                    "CHAOS_HALT_AT=after-commit; deposit of 7000; hard kill; clean restart",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
