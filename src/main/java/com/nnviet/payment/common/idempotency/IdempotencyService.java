package com.nnviet.payment.common.idempotency;

import com.nnviet.payment.common.chaos.Chaos;
import com.nnviet.payment.common.db.Tx;
import com.nnviet.payment.common.errors.IdempotencyKeyReuseException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

/**
 * The retry-duplicate defense, wrapped around every money-moving workflow:
 *
 * 1. Redis fast path: a cached finished response is returned immediately.
 * 2. Otherwise, inside the SAME transaction as the workflow's work:
 *    - claim the key (INSERT .. ON CONFLICT DO NOTHING). A losing duplicate
 *      blocks on the unique index until the winner commits, then replays the
 *      winner's stored response.
 *    - run the work, then attach the response to the claimed row before
 *      commit. A crash anywhere pre-commit rolls back claim + work together,
 *      so a retry starts clean - there is no window where the money moved
 *      but the key is unknown, or vice versa.
 * 3. Same key with a different payload is rejected (422), never replayed.
 *
 * Failed work (e.g. insufficient funds) rolls back the claim too: only
 * successful movements are recorded for replay, and retrying a failed
 * request re-attempts it - which is safe, because it failed atomically.
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
