package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.task.AcquireHoldTask;
import com.nnviet.payment.ledger.task.ValidateBalanceTask;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Creates a hold: reserves funds (held_amount += amount) with a TTL.
 * No ledger entry - a reservation is not a movement; the commit is.
 */
public class HoldWorkflow {

    private final WalletRepository wallets;
    private final ValidateBalanceTask validateBalance;
    private final AcquireHoldTask acquireHold;
    private final OutboxWriter outbox;

    public HoldWorkflow(WalletRepository wallets, ValidateBalanceTask validateBalance,
                        AcquireHoldTask acquireHold, OutboxWriter outbox) {
        this.wallets = wallets;
        this.validateBalance = validateBalance;
        this.acquireHold = acquireHold;
        this.outbox = outbox;
    }

    public JsonObject run(Connection conn, UUID walletId, long amount, int ttlSeconds,
                          String idemKey) throws SQLException {
        Wallet wallet = wallets.lockById(conn, walletId);
        validateBalance.requireAvailable(wallet, amount);
        Hold hold = acquireHold.acquire(conn, wallet, amount, ttlSeconds);
        outbox.write(conn, "hold.created", hold.toJson());
        return new JsonObject().put("hold", hold.toJson());
    }
}
