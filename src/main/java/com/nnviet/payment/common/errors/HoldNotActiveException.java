package com.nnviet.payment.common.errors;

import java.util.UUID;

/**
 * No ACTIVE hold to settle: already settled, past its TTL, or never existed (409).
 */
public class HoldNotActiveException extends DomainException {

    public HoldNotActiveException(UUID holdId) {
        super("HOLD_NOT_ACTIVE", 409,
                "hold is not active (already settled, expired, or unknown): " + holdId);
    }
}
