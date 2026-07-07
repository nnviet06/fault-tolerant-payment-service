package com.nnviet.payment.ledger.task;

import com.nnviet.payment.ledger.LedgerEntry;
import com.nnviet.payment.ledger.LedgerRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Appends one hash-chained entry. Must run inside the same transaction as the
 * balance updates it records; the chain link (advisory lock + tail read) is
 * handled by LedgerRepository.
 */
public class AppendLedgerEntryTask {

    private final LedgerRepository ledger;

    public AppendLedgerEntryTask(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public LedgerEntry append(Connection conn, String entryType, UUID debitWalletId,
                              UUID creditWalletId, long amount, UUID holdId,
                              String idempotencyKey) throws SQLException {
        return ledger.append(conn, entryType, debitWalletId, creditWalletId, amount,
                holdId, idempotencyKey);
    }
}
