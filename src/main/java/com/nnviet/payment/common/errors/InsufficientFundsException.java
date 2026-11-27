package com.nnviet.payment.common.errors;

/**
 * Available balance (balance - held) is below the amount. Checked under the row lock, so no race.
 */
public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(long available, long requested) {
        super("INSUFFICIENT_FUNDS", 422,
                "insufficient funds: available " + available + ", requested " + requested);
    }
}
