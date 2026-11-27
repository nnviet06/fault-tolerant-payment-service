package com.nnviet.payment.common.id;

import java.util.UUID;

/** Mints every id. Random UUIDs are fine: no data is sorted by id. */
public final class Ids {

    private Ids() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }
}
