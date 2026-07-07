package com.nnviet.payment.common.errors;

/**
 * The same Idempotency-Key arrived with a different request payload.
 * Replaying the stored response would be wrong (it belongs to another
 * request), so this is surfaced as a client error instead.
 */
public class IdempotencyKeyReuseException extends DomainException {

    public IdempotencyKeyReuseException() {
        super("IDEMPOTENCY_KEY_REUSE", 422,
                "Idempotency-Key was already used with a different request payload");
    }
}
