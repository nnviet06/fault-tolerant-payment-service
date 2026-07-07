package com.nnviet.payment.ledger.task;

import com.nnviet.payment.wallet.WalletRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** balance -= amount on a wallet row the caller has locked in this transaction. */
public class DebitTask {

    private final WalletRepository wallets;

    public DebitTask(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public void debit(Connection conn, UUID walletId, long amount) throws SQLException {
        wallets.applyDeltas(conn, walletId, -amount, 0L);
    }
}
