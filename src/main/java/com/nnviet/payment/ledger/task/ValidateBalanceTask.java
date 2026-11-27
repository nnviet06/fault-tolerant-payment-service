package com.nnviet.payment.ledger.task;

import com.nnviet.payment.common.errors.InsufficientFundsException;
import com.nnviet.payment.wallet.Wallet;

/**
 * Checks available funds. The wallet must be locked in this transaction, so the balance cannot
 * change before the debit.
 */
public class ValidateBalanceTask {

    public void requireAvailable(Wallet lockedWallet, long amount) {
        if (lockedWallet.available() < amount) {
            throw new InsufficientFundsException(lockedWallet.available(), amount);
        }
    }
}
