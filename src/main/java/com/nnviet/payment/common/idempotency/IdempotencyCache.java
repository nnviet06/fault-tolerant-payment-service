package com.nnviet.payment.common.idempotency;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Redis fast path in front of the Postgres idempotency store. Strictly an
 * optimization: entries are immutable finished responses, every failure or
 * timeout degrades to a cache miss, and the Postgres path alone upholds the
 * exactly-once guarantee (proven by the redis-blackout torture scenario).
 */
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);

    /** An unreachable Redis must cost at most this much latency, then we fall through to Postgres. */
    private static final Duration LOOKUP_TIMEOUT = Duration.ofMillis(250);

    private final RedisAPI redis;
    private final int ttlSeconds;

    public IdempotencyCache(RedisAPI redis, int ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    public record CachedResponse(String requestHash, int status, JsonObject body) {
    }

    /** Emits null on miss, timeout, or any Redis failure. */
    public Uni<CachedResponse> lookup(String idemKey) {
        return redis.get(key(idemKey))
                .ifNoItem().after(LOOKUP_TIMEOUT).fail()
                .map(response -> response == null ? null : decode(response.toString()))
                .onFailure().recoverWithItem(failure -> {
                    log.debug("idempotency cache lookup degraded to miss: {}", failure.toString());
                    return null;
                });
    }

    /** Best-effort, fire-and-forget: a failed store only costs a future cache miss. */
    public void store(String idemKey, String requestHash, int status, JsonObject body) {
        String value = new JsonObject()
                .put("h", requestHash)
                .put("s", status)
                .put("b", body)
                .encode();
        redis.set(List.of(key(idemKey), value, "EX", String.valueOf(ttlSeconds)))
                .subscribe().with(
                        ok -> {
                        },
                        failure -> log.debug("idempotency cache store skipped: {}",
                                failure.toString()));
    }

    private static CachedResponse decode(String value) {
        JsonObject json = new JsonObject(value);
        return new CachedResponse(json.getString("h"), json.getInteger("s"),
                json.getJsonObject("b"));
    }

    private static String key(String idemKey) {
        return "idem:" + idemKey;
    }
}
