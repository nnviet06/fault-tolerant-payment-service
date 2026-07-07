package com.nnviet.payment.common.errors;

import java.util.UUID;

/**
 * The hold's compare-and-set transition found no ACTIVE row: the hold was
 * already committed/rolled back/expired, has passed its TTL, or never existed.
 * 409 because the request conflicts with the hold's current state.
 */
public class HoldNotActiveException extends DomainException {

    public HoldNotActiveException(UUID holdId) {
        super("HOLD_NOT_ACTIVE", 409,
                "hold is not active (already settled, expired, or unknown): " + holdId);
    }
}
