package com.nnviet.payment.common.idempotency;

import com.nnviet.payment.common.chaos.Chaos;
import com.nnviet.payment.common.db.Tx;
import com.nnviet.payment.common.errors.IdempotencyKeyReuseException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

/**
 * Runs each money workflow at most once per idempotency key; Postgres decides, Redis caches.
 * The key is claimed in the workflow's transaction, so a crash undoes both and a retry starts clean.
 */
public class IdempotencyService {

    public record IdemOutcome(int status, JsonObject body, boolean replayed) {
    }

    private final Tx tx;
    private final IdempotencyRepository repository;
    private final IdempotencyCache cache;

    public IdempotencyService(Tx tx, IdempotencyRepository repository, IdempotencyCache cache) {
        this.tx = tx;
        this.repository = repository;
        this.cache = cache;
    }

    /**
     * Replays the stored response for a repeated key; a different request under the same key is
     * rejected (422). Failed work stores nothing, so its retry runs again.
     */
    public Uni<IdemOutcome> execute(String idemKey, String requestHash, int successStatus,
                                    Tx.TxBlock<JsonObject> work) {
        return cache.lookup(idemKey).chain(cached -> {
            if (cached != null) {
                if (!cached.requestHash().equals(requestHash)) {
                    return Uni.createFrom().failure(new IdempotencyKeyReuseException());
                }
                return Uni.createFrom().item(
                        new IdemOutcome(cached.status(), cached.body(), true));
            }
            return executeAgainstPostgres(idemKey, requestHash, successStatus, work);
        });
    }

    // A concurrent duplicate waits on the key's unique index until the first commits, then replays.
    private Uni<IdemOutcome> executeAgainstPostgres(String idemKey, String requestHash,
                                                    int successStatus,
                                                    Tx.TxBlock<JsonObject> work) {
        return tx.inTx(conn -> {
            boolean claimed = repository.claim(conn, idemKey, requestHash);
            if (!claimed) {
                IdempotencyRepository.StoredResponse stored = repository.find(conn, idemKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency key conflicted but row is not readable"));
                if (!stored.requestHash().equals(requestHash)) {
                    throw new IdempotencyKeyReuseException();
                }
                if (stored.status() == null || stored.body() == null) {
                    // unreachable by construction: rows commit only with a response
                    throw new IllegalStateException(
                            "committed idempotency row has no stored response");
                }
                return new IdemOutcome(stored.status(), stored.body(), true);
            }
            JsonObject body = work.run(conn);
            repository.storeResponse(conn, idemKey, successStatus, body);
            return new IdemOutcome(successStatus, body, false);
        }).invoke(outcome -> {
            if (!outcome.replayed()) {
                // torture crash window: transaction committed, response not yet sent
                Chaos.point(Chaos.AFTER_COMMIT);
            }
            cache.store(idemKey, requestHash, outcome.status(), outcome.body());
        });
    }
}
