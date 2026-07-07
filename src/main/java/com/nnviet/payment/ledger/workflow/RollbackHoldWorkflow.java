package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.common.errors.HoldNotActiveException;
import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.ledger.task.SettleHoldTask;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.WalletRepository;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Rolls back a hold: releases the reservation. No ledger entry (no money
 * moved). No expiry guard: rolling back an expired-but-unswept hold releases
 * the funds exactly like the sweeper would, just with a different label.
 */
public class RollbackHoldWorkflow {

    private final WalletRepository wallets;
    private final HoldRepository holds;
    private final SettleHoldTask settleHold;
    private final OutboxWriter outbox;

    public RollbackHoldWorkflow(WalletRepository wallets, HoldRepository holds,
                                SettleHoldTask settleHold, OutboxWriter outbox) {
        this.wallets = wallets;
        this.holds = holds;
        this.settleHold = settleHold;
        this.outbox = outbox;
    }

    public JsonObject run(Connection conn, UUID holdId, String idemKey) throws SQLException {
        Hold hold = holds.findById(conn, holdId)
                .orElseThrow(() -> new HoldNotActiveException(holdId));
        wallets.lockById(conn, hold.walletId());
        boolean won = settleHold.settle(conn, hold, Hold.ROLLED_BACK,
                HoldRepository.ExpiryGuard.NONE);
        if (!won) {
            throw new HoldNotActiveException(holdId);
        }
        outbox.write(conn, "hold.rolled_back", new JsonObject()
                .put("holdId", holdId.toString())
                .put("walletId", hold.walletId().toString())
                .put("amount", hold.amount()));
        return new JsonObject()
                .put("holdId", holdId.toString())
                .put("status", Hold.ROLLED_BACK);
    }
}
