package com.nnviet.payment.common;

import com.nnviet.payment.common.errors.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void acceptsIntegerAndLongMinorUnits() {
        assertEquals(100L, Money.requireAmount(100));
        assertEquals(9_000_000_000L, Money.requireAmount(9_000_000_000L));
    }

    @Test
    void rejectsMissingAmount() {
        assertThrows(ValidationException.class, () -> Money.requireAmount(null));
    }

    @Test
    void rejectsFractionalAmounts() {
        // Vert.x parses 10.5 as Double - any floating point near money is a bug
        assertThrows(ValidationException.class, () -> Money.requireAmount(10.5d));
        assertThrows(ValidationException.class, () -> Money.requireAmount(10.0d));
    }

    @Test
    void rejectsNonNumericAmounts() {
        assertThrows(ValidationException.class, () -> Money.requireAmount("100"));
    }

    @Test
    void rejectsZeroAndNegative() {
        assertThrows(ValidationException.class, () -> Money.requireAmount(0));
        assertThrows(ValidationException.class, () -> Money.requireAmount(-5));
    }

    @Test
    void addIsOverflowSafe() {
        assertEquals(300L, Money.add(100L, 200L));
        assertThrows(ValidationException.class, () -> Money.add(Long.MAX_VALUE, 1L));
    }
}
