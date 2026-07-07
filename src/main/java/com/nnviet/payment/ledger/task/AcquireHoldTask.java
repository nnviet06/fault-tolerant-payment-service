package com.nnviet.payment.ledger.task;

import com.nnviet.payment.common.id.Ids;
import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Reserves funds on a locked wallet: raises held_amount and inserts the
 * ACTIVE hold row in the same transaction. No ledger entry - a reservation
 * is not a movement.
 */
public class AcquireHoldTask {

    private final WalletRepository wallets;
    private final HoldRepository holds;

    public AcquireHoldTask(WalletRepository wallets, HoldRepository holds) {
        this.wallets = wallets;
        this.holds = holds;
    }

    public Hold acquire(Connection conn, Wallet lockedWallet, long amount, int ttlSeconds)
            throws SQLException {
        wallets.applyDeltas(conn, lockedWallet.id(), 0L, amount);
        return holds.insert(conn, Ids.newId(), lockedWallet.id(), amount, ttlSeconds);
    }
}
