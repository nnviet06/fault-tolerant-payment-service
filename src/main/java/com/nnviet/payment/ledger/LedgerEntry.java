package com.nnviet.payment.ledger;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Row mirror of the append-only ledger_entries table. One row is one balanced
 * movement: it carries both the debit side and the credit side, so a partial
 * (unbalanced) movement cannot exist. A NULL side means the external world.
 */
public record LedgerEntry(
        long seq,
        String entryType,
        UUID debitWalletId,
        UUID creditWalletId,
        long amount,
        UUID holdId,
        String idempotencyKey,
        long createdAtMs,
        String prevHash,
        String entryHash) {

    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAWAL = "WITHDRAWAL";
    public static final String TRANSFER = "TRANSFER";
    public static final String HOLD_COMMIT = "HOLD_COMMIT";

    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("seq", seq)
                .put("entryType", entryType)
                .put("amount", amount)
                .put("createdAtMs", createdAtMs)
                .put("prevHash", prevHash)
                .put("entryHash", entryHash);
        if (debitWalletId != null) {
            json.put("debitWalletId", debitWalletId.toString());
        }
        if (creditWalletId != null) {
            json.put("creditWalletId", creditWalletId.toString());
        }
        if (holdId != null) {
            json.put("holdId", holdId.toString());
        }
        if (idempotencyKey != null) {
            json.put("idempotencyKey", idempotencyKey);
        }
        return json;
    }
}
