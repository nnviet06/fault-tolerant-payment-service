package com.nnviet.payment.torture.scenarios;

import com.nnviet.payment.torture.ServiceProcess;
import com.nnviet.payment.torture.TortureHarness;
import com.nnviet.payment.torture.TortureReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nnviet.payment.torture.TortureHarness.check;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry storm with Redis unreachable (port 1): must still hold, because Postgres decides
 * and Redis only caches.
 */
class RedisBlackoutTortureIT {

    private static final int PORT = 8184;

    @Test
    void redisBlackoutRetryStorm() throws Exception {
        TortureHarness.requireInfrastructure();
        List<String> failures = new ArrayList<>();
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        try (ServiceProcess service = ServiceProcess.start(PORT,
                Map.of("REDIS_PORT", "1"))) {
            service.awaitHealthy(Duration.ofSeconds(30));

            String redisStatus = TortureHarness.get(PORT, "/health").json()
                    .getString("redis");
            metrics.put("health.redis (must be DOWN)", redisStatus);
            check(failures, "DOWN".equals(redisStatus),
                    "blackout not in effect - health reports redis=" + redisStatus);

            RetryStormTortureIT.runStorm(PORT, failures, metrics);
        } catch (Exception e) {
            e.printStackTrace();
            failures.add("scenario error: " + e);
        } finally {
            TortureReport.record(new TortureReport.Scenario(
                    "redis-blackout-retry-storm",
                    "retry duplicate while the idempotency cache is unavailable",
                    "Postgres idempotency_keys as the sole correctness authority; "
                            + "Redis degradation must cost latency only",
                    "service started with REDIS_PORT=1 (unreachable); "
                            + "then the full retry-storm scenario",
                    failures.isEmpty(), metrics, String.join("; ", failures)));
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
