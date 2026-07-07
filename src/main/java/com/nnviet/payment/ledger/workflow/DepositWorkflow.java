package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.common.Money;
import com.nnviet.payment.ledger.LedgerEntry;
import com.nnviet.payment.ledger.task.AppendLedgerEntryTask;
import com.nnviet.payment.ledger.task.CreditTask;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Deposit: money enters from the external world (NULL debit side).
 * One transaction: lock wallet -> credit -> ledger entry -> outbox event.
 */
public class DepositWorkflow {

    private final WalletRepository wallets;
    private final CreditTask credit;
    private final AppendLedgerEntryTask appendEntry;
    private final OutboxWriter outbox;

    public DepositWorkflow(WalletRepository wallets, CreditTask credit,
                           AppendLedgerEntryTask appendEntry, OutboxWriter outbox) {
        this.wallets = wallets;
        this.credit = credit;
        this.appendEntry = appendEntry;
        this.outbox = outbox;
    }

    public JsonObject run(Connection conn, UUID walletId, long amount, String idemKey)
            throws SQLException {
        Wallet wallet = wallets.lockById(conn, walletId);
        credit.credit(conn, walletId, amount);
        LedgerEntry entry = appendEntry.append(conn, LedgerEntry.DEPOSIT,
                null, walletId, amount, null, idemKey);
        outbox.write(conn, "deposit.recorded", entry.toJson());
        return new JsonObject()
                .put("entry", entry.toJson())
                .put("balanceAfter", Money.add(wallet.balance(), amount));
    }
}
