package com.nnviet.payment.common.errors;

/**
 * Available balance (balance - held_amount) is lower than the requested amount.
 * Thrown after the wallet row is locked, so the check is race-free.
 */
public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(long available, long requested) {
        super("INSUFFICIENT_FUNDS", 422,
                "insufficient funds: available " + available + ", requested " + requested);
    }
}
