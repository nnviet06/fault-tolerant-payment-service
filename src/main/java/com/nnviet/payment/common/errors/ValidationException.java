package com.nnviet.payment.common.errors;

/** Bad input: malformed JSON, missing Idempotency-Key, non-positive amount, etc. */
public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", 400, message);
    }
}
