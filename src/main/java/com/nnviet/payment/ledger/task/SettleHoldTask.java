package com.nnviet.payment.ledger.task;

import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.wallet.WalletRepository;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Moves a hold out of ACTIVE and, if this call won, releases its reservation.
 * False if another settle won first; the caller decides if that is an error.
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
