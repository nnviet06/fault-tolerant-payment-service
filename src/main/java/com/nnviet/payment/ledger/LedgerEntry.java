package com.nnviet.payment.ledger;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Row mirror of the append-only ledger_entries table. One row is one balanced
 * movement: it carries both the debit side and the credit side, so a partial
 * (unbalanced) movement cannot exist. A NULL side means the external world.
 *
 * Each present side is also one link of that wallet's hash chain:
 * {@code <side>WalletSeq} is the link's position in the wallet's chain and
 * {@code <side>PrevHash} the entry_hash of the wallet's previous link. An
 * absent side has all three fields null. {@code seq} is only a row id.
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
