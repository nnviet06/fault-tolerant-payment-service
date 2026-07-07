package com.nnviet.payment.ledger;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.UUID;

/**
 * Row mirror of the holds table. A hold is a reservation, not a movement:
 * it raises the wallet's held_amount and writes no ledger entry. Only a
 * commit moves money (and that movement is the ledger entry).
 */
public record Hold(UUID id, UUID walletId, long amount, String status, Instant expiresAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String COMMITTED = "COMMITTED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String EXPIRED = "EXPIRED";

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id.toString())
                .put("walletId", walletId.toString())
                .put("amount", amount)
                .put("status", status)
                .put("expiresAt", expiresAt.toString());
    }
}
