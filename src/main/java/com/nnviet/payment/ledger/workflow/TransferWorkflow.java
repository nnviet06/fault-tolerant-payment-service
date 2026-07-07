package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.common.errors.ValidationException;
import com.nnviet.payment.ledger.LedgerEntry;
import com.nnviet.payment.ledger.task.AppendLedgerEntryTask;
import com.nnviet.payment.ledger.task.CreditTask;
import com.nnviet.payment.ledger.task.DebitTask;
import com.nnviet.payment.ledger.task.ValidateBalanceTask;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.Wallet;
import com.nnviet.payment.wallet.WalletRepository;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Transfer between two wallets in one transaction. Both wallet rows are
 * locked in deterministic id order, so crossed transfers (A->B while B->A)
 * queue on the same first lock instead of deadlocking.
 */
public class TransferWorkflow {

    private final WalletRepository wallets;
    private final ValidateBalanceTask validateBalance;
    private final DebitTask debit;
    private final CreditTask credit;
    private final AppendLedgerEntryTask appendEntry;
    private final OutboxWriter outbox;

    public TransferWorkflow(WalletRepository wallets, ValidateBalanceTask validateBalance,
                            DebitTask debit, CreditTask credit,
                            AppendLedgerEntryTask appendEntry, OutboxWriter outbox) {
        this.wallets = wallets;
        this.validateBalance = validateBalance;
        this.debit = debit;
        this.credit = credit;
        this.appendEntry = appendEntry;
        this.outbox = outbox;
    }

    public JsonObject run(Connection conn, UUID fromWalletId, UUID toWalletId, long amount,
                          String idemKey) throws SQLException {
        if (fromWalletId.equals(toWalletId)) {
            throw new ValidationException("cannot transfer to the same wallet");
        }
        Wallet from;
        if (fromWalletId.compareTo(toWalletId) < 0) {
            from = wallets.lockById(conn, fromWalletId);
            wallets.lockById(conn, toWalletId);
        } else {
            wallets.lockById(conn, toWalletId);
            from = wallets.lockById(conn, fromWalletId);
        }
        validateBalance.requireAvailable(from, amount);
        debit.debit(conn, fromWalletId, amount);
        credit.credit(conn, toWalletId, amount);
        LedgerEntry entry = appendEntry.append(conn, LedgerEntry.TRANSFER,
                fromWalletId, toWalletId, amount, null, idemKey);
        outbox.write(conn, "transfer.recorded", entry.toJson());
        return new JsonObject().put("entry", entry.toJson());
    }
}
