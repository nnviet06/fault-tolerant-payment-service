package com.nnviet.payment.common.errors;

/**
 * Same Idempotency-Key with a different request; replaying would return another request's response.
 */
public class IdempotencyKeyReuseException extends DomainException {

    public IdempotencyKeyReuseException() {
        super("IDEMPOTENCY_KEY_REUSE", 422,
                "Idempotency-Key was already used with a different request payload");
    }
}
