package com.nnviet.payment.common.errors;

/**
 * Base class for expected domain failures. Carries a machine-readable code
 * and the HTTP status it maps to, so ErrorMapper needs no instanceof ladder.
 * Domain code never throws bare RuntimeException.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    protected DomainException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
