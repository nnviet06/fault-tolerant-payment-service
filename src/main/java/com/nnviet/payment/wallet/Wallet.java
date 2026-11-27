package com.nnviet.payment.wallet;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * One wallets row. balance = total owned, heldAmount = reserved by holds, available = spendable.
 */
public record Wallet(UUID id, String ownerName, long balance, long heldAmount) {

    public long available() {
        return balance - heldAmount;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id.toString())
                .put("ownerName", ownerName)
                .put("balance", balance)
                .put("heldAmount", heldAmount)
                .put("available", available());
    }
}
