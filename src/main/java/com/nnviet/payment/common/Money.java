package com.nnviet.payment.common;

import com.nnviet.payment.common.errors.ValidationException;

/**
 * Money is a long count of minor units, end to end. This class is the single
 * gate through which request amounts enter the system: it rejects anything
 * that is not a positive JSON integer (doubles, strings, nulls, zero,
 * negatives) and provides overflow-safe arithmetic.
 *
 * There is deliberately no double/float/BigDecimal anywhere near money.
 */
public final class Money {

    private Money() {
    }

    /**
     * Validates a raw JSON value as an amount. Vert.x parses JSON integrals as
     * Integer/Long and anything fractional as Double, so an instanceof check
     * is a strict "integer minor units only" filter.
     */
    public static long requireAmount(Object jsonValue) {
        if (jsonValue == null) {
            throw new ValidationException("amount is required");
        }
        if (!(jsonValue instanceof Integer) && !(jsonValue instanceof Long)) {
            throw new ValidationException(
                    "amount must be an integer number of minor units (no decimals)");
        }
        long amount = ((Number) jsonValue).longValue();
        if (amount <= 0) {
            throw new ValidationException("amount must be positive");
        }
        return amount;
    }

    /** Overflow-safe addition for balance arithmetic. */
    public static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new ValidationException("amount overflows balance capacity");
        }
    }
}
