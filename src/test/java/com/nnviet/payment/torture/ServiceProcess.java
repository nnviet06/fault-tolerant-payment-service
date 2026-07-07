package com.nnviet.payment.torture;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the shaded jar as a REAL operating-system process, so kill scenarios
 * are genuine process deaths (Runtime.halt inside, destroyForcibly outside),
 * not simulated ones. Requires `mvn -Ptorture verify`, which packages the jar
 * first and passes its path via the torture.jar system property.
 */
public final class ServiceProcess implements AutoCloseable {

    private static final HttpClient PROBE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build();

    private final Process process;
    private final int port;
    private final File logFile;

    private ServiceProcess(Process process, int port, File logFile) {
        this.process = process;
        this.port = port;
        this.logFile = logFile;
    }

    public static ServiceProcess start(int port, Map<String, String> extraEnv)
            throws IOException {
        String jar = System.getProperty("torture.jar");
        if (jar == null || !new File(jar).isFile()) {
            throw new IllegalStateException(
                    "torture.jar not found - run the suite via: mvn -Ptorture verify");
        }
        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", jar);
        pb.environment().put("HTTP_PORT", String.valueOf(port));
        pb.environment().putAll(extraEnv);
        File log = new File("target/torture-logs/service-" + port + "-"
                + System.nanoTime() + ".log");
        Files.createDirectories(log.getParentFile().toPath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        return new ServiceProcess(pb.start(), port, log);
    }

    public void awaitHealthy(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "service died during startup, log: " + logFile.getAbsolutePath());
            }
            try {
                HttpResponse<String> response = PROBE.send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://localhost:" + port + "/health"))
                                .timeout(Duration.ofSeconds(1)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // not listening yet
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new IllegalStateException(
                "service not healthy within " + timeout + ", log: " + logFile.getAbsolutePath());
    }

    /** Waits for the process to die on its own (chaos halt); true if it did. */
    public boolean awaitDeath(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
