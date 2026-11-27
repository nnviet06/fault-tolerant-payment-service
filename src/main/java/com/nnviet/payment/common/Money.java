package com.nnviet.payment.common;

import com.nnviet.payment.common.errors.ValidationException;

/**
 * Money is a long count of minor units. Every request amount enters through here:
 * only positive JSON integers pass. No double, float or BigDecimal anywhere near money.
 */
public final class Money {

    private Money() {
    }

    /** Vert.x parses whole numbers as Integer/Long and fractions as Double, so instanceof is enough. */
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
