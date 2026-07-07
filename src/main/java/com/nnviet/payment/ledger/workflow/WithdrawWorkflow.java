package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.ledger.LedgerEntry;
import com.nnviet.payment.ledger.task.AppendLedgerEntryTask;
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
 * Withdrawal: money leaves to the external world (NULL credit side).
 * One transaction: lock wallet -> validate available -> debit -> ledger entry
 * -> outbox event. The row lock makes validate-then-debit race-free; the
 * schema CHECK (balance >= 0) backs it up if code ever regresses.
 */
public class WithdrawWorkflow {

    private final WalletRepository wallets;
    private final ValidateBalanceTask validateBalance;
    private final DebitTask debit;
    private final AppendLedgerEntryTask appendEntry;
    private final OutboxWriter outbox;

    public WithdrawWorkflow(WalletRepository wallets, ValidateBalanceTask validateBalance,
                            DebitTask debit, AppendLedgerEntryTask appendEntry,
                            OutboxWriter outbox) {
        this.wallets = wallets;
        this.validateBalance = validateBalance;
        this.debit = debit;
        this.appendEntry = appendEntry;
        this.outbox = outbox;
    }

    public JsonObject run(Connection conn, UUID walletId, long amount, String idemKey)
            throws SQLException {
        Wallet wallet = wallets.lockById(conn, walletId);
        validateBalance.requireAvailable(wallet, amount);
        debit.debit(conn, walletId, amount);
        LedgerEntry entry = appendEntry.append(conn, LedgerEntry.WITHDRAWAL,
                walletId, null, amount, null, idemKey);
        outbox.write(conn, "withdrawal.recorded", entry.toJson());
        return new JsonObject()
                .put("entry", entry.toJson())
                .put("balanceAfter", wallet.balance() - amount);
    }
}
