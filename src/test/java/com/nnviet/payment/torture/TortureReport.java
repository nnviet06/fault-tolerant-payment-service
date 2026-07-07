package com.nnviet.payment.torture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects scenario results and renders target/torture-report.md after every
 * recorded scenario (so a crashed suite still leaves a report behind).
 *
 * Honesty rule: every number in the report is measured in the run that wrote
 * it; anything that cannot be measured is printed as UNMEASURED.
 */
public final class TortureReport {

    public record Scenario(
            String name,
            String adversary,
            String mechanism,
            String parameters,
            boolean pass,
            Map<String, String> metrics,
            String notes) {
    }

    private static final Path REPORT = Path.of("target", "torture-report.md");
    private static final List<Scenario> results =
            Collections.synchronizedList(new ArrayList<>());
    private static final Instant STARTED = Instant.now();

    private TortureReport() {
    }

    public static void record(Scenario scenario) {
        results.add(scenario);
        flush();
    }

    /** Convenience: ordered metric map from alternating key/value pairs. */
    public static LinkedHashMap<String, String> metrics(Object... keyValues) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
        }
        return map;
    }

    static synchronized void flush() {
        StringBuilder md = new StringBuilder();
        long passed = results.stream().filter(Scenario::pass).count();
        md.append("# Torture Report\n\n");
        md.append("Reproducible adversarial scenarios against the payment service. ")
                .append("Every number below was measured in this run; ")
                .append("UNMEASURED marks values this suite cannot observe.\n\n");
        md.append("- Generated: ").append(Instant.now()).append(" (suite started ")
                .append(STARTED).append(")\n");
        md.append("- Git revision: ").append(gitRevision()).append("\n");
        md.append("- Java: ").append(System.getProperty("java.version"))
                .append(", OS: ").append(System.getProperty("os.name")).append("\n");
        md.append("- Result: **").append(passed).append("/").append(results.size())
                .append(" scenarios passed**\n\n");
        synchronized (results) {
            for (Scenario s : results) {
                md.append("## ").append(s.name()).append(" — ")
                        .append(s.pass() ? "PASS" : "FAIL").append("\n\n");
                md.append("- Adversary: ").append(s.adversary()).append("\n");
                md.append("- Mechanism under test: ").append(s.mechanism()).append("\n");
                md.append("- Parameters: ").append(s.parameters()).append("\n\n");
                md.append("| metric | value |\n|---|---|\n");
                s.metrics().forEach((k, v) ->
                        md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
                md.append("\n");
                if (s.notes() != null && !s.notes().isBlank()) {
                    md.append("Notes: ").append(s.notes()).append("\n\n");
                }
            }
        }
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write torture report", e);
        }
    }

    private static String gitRevision() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !out.isBlank() ? out : "UNMEASURED";
        } catch (Exception e) {
            return "UNMEASURED";
        }
    }
}
