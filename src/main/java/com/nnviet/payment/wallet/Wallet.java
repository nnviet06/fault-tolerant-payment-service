package com.nnviet.payment.wallet;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Row mirror of the wallets table. balance is the total owned; heldAmount is
 * the portion reserved by active holds; available is what movements may spend.
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
