package com.nnviet.payment.ledger;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * One ledger row = one balanced movement: debit and credit sides on one row, so it cannot be half
 * written. A NULL side is the outside world; each other side is a link in that wallet's hash chain.
 */
public record LedgerEntry(
        long seq,
        String entryType,
        UUID debitWalletId,
        Long debitWalletSeq,
        String debitPrevHash,
        UUID creditWalletId,
        Long creditWalletSeq,
        String creditPrevHash,
        long amount,
        UUID holdId,
        String idempotencyKey,
        long createdAtMs,
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
                .put("entryHash", entryHash);
        if (debitWalletId != null) {
            json.put("debitWalletId", debitWalletId.toString())
                    .put("debitWalletSeq", debitWalletSeq)
                    .put("debitPrevHash", debitPrevHash);
        }
        if (creditWalletId != null) {
            json.put("creditWalletId", creditWalletId.toString())
                    .put("creditWalletSeq", creditWalletSeq)
                    .put("creditPrevHash", creditPrevHash);
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
