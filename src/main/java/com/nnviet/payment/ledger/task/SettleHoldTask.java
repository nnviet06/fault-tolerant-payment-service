package com.nnviet.payment.ledger.task;

import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.wallet.WalletRepository;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Settles a hold: compare-and-set its status out of ACTIVE and, if this call
 * won the race, release the reservation (held_amount -= amount). Returns
 * false when a concurrent commit/rollback/expiry settled the hold first -
 * the caller decides whether that is an error (commit: 409) or a no-op
 * (expiry sweeper).
 */
public class SettleHoldTask {

    private final WalletRepository wallets;
    private final HoldRepository holds;

    public SettleHoldTask(WalletRepository wallets, HoldRepository holds) {
        this.wallets = wallets;
        this.holds = holds;
    }

    public boolean settle(Connection conn, Hold hold, String toStatus,
                          HoldRepository.ExpiryGuard guard) throws SQLException {
        boolean won = holds.transitionFromActive(conn, hold.id(), toStatus, guard);
        if (won) {
            wallets.applyDeltas(conn, hold.walletId(), 0L, -hold.amount());
        }
        return won;
    }
}
