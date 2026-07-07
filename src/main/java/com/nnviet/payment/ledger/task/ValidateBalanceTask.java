package com.nnviet.payment.ledger.task;

import com.nnviet.payment.common.errors.InsufficientFundsException;
import com.nnviet.payment.wallet.Wallet;

/**
 * Available-funds check. The wallet passed in must have been loaded with
 * SELECT ... FOR UPDATE in the current transaction - that row lock is what
 * makes this check race-free (no concurrent movement can change the balance
 * between this check and the debit that follows it).
 */
public class ValidateBalanceTask {

    public void requireAvailable(Wallet lockedWallet, long amount) {
        if (lockedWallet.available() < amount) {
            throw new InsufficientFundsException(lockedWallet.available(), amount);
        }
    }
}
