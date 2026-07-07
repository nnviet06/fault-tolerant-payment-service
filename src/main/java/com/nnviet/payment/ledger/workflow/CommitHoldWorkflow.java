package com.nnviet.payment.ledger.workflow;

import com.nnviet.payment.common.errors.HoldNotActiveException;
import com.nnviet.payment.common.errors.ValidationException;
import com.nnviet.payment.ledger.Hold;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.ledger.LedgerEntry;
import com.nnviet.payment.ledger.task.AppendLedgerEntryTask;
import com.nnviet.payment.ledger.task.CreditTask;
import com.nnviet.payment.ledger.task.DebitTask;
import com.nnviet.payment.ledger.task.SettleHoldTask;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.WalletRepository;
import io.vertx.core.json.JsonObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Commits a hold: the reserved funds actually move to the destination wallet.
 * This is the only hold operation that writes a ledger entry.
 *
 * The compare-and-set (status ACTIVE -> COMMITTED, and not yet expired) is
 * the single arbiter against a concurrent rollback or the expiry sweeper:
 * exactly one settlement ever wins. Funds are already reserved, so no
 * available-balance check is needed - the invariants guarantee
 * balance >= held_amount >= amount.
 */
public class CommitHoldWorkflow {

    private final WalletRepository wallets;
    private final HoldRepository holds;
    private final SettleHoldTask settleHold;
    private final DebitTask debit;
    private final CreditTask credit;
    private final AppendLedgerEntryTask appendEntry;
    private final OutboxWriter outbox;

    public CommitHoldWorkflow(WalletRepository wallets, HoldRepository holds,
                              SettleHoldTask settleHold, DebitTask debit, CreditTask credit,
                              AppendLedgerEntryTask appendEntry, OutboxWriter outbox) {
        this.wallets = wallets;
        this.holds = holds;
        this.settleHold = settleHold;
        this.debit = debit;
        this.credit = credit;
        this.appendEntry = appendEntry;
        this.outbox = outbox;
    }

    public JsonObject run(Connection conn, UUID holdId, UUID destinationWalletId, String idemKey)
            throws SQLException {
        Hold hold = holds.findById(conn, holdId)
                .orElseThrow(() -> new HoldNotActiveException(holdId));
        UUID sourceWalletId = hold.walletId();
        if (sourceWalletId.equals(destinationWalletId)) {
            throw new ValidationException("destinationWalletId must differ from the hold's wallet");
        }
        // same lock ordering discipline as transfers: deterministic by id
        if (sourceWalletId.compareTo(destinationWalletId) < 0) {
            wallets.lockById(conn, sourceWalletId);
            wallets.lockById(conn, destinationWalletId);
        } else {
            wallets.lockById(conn, destinationWalletId);
            wallets.lockById(conn, sourceWalletId);
        }
        boolean won = settleHold.settle(conn, hold, Hold.COMMITTED,
                HoldRepository.ExpiryGuard.MUST_NOT_BE_EXPIRED);
        if (!won) {
            throw new HoldNotActiveException(holdId);
        }
        debit.debit(conn, sourceWalletId, hold.amount());
        credit.credit(conn, destinationWalletId, hold.amount());
        LedgerEntry entry = appendEntry.append(conn, LedgerEntry.HOLD_COMMIT,
                sourceWalletId, destinationWalletId, hold.amount(), holdId, idemKey);
        outbox.write(conn, "hold.committed", entry.toJson());
        return new JsonObject()
                .put("entry", entry.toJson())
                .put("holdId", holdId.toString());
    }
}
