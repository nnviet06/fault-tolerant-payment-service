package com.nnviet.payment.common.id;

import java.util.UUID;

/**
 * Single place where identifiers are minted. Random UUIDs are fine here:
 * ordering never comes from ids (ledger ordering is the seq column,
 * outbox ordering is BIGSERIAL).
 */
public final class Ids {

    private Ids() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }
}
