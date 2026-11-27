package com.nnviet.payment.common.errors;

/**
 * Base of every expected domain failure; carries its error code and HTTP status for ErrorMapper.
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
