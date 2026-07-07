package com.nnviet.payment.ledger.task;

import com.nnviet.payment.common.errors.InsufficientFundsException;
import com.nnviet.payment.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateBalanceTaskTest {

    private final ValidateBalanceTask task = new ValidateBalanceTask();

    @Test
    void allowsSpendingUpToAvailable() {
        Wallet wallet = new Wallet(UUID.randomUUID(), "a", 100, 40); // available = 60
        assertDoesNotThrow(() -> task.requireAvailable(wallet, 60));
    }

    @Test
    void heldFundsAreNotSpendable() {
        Wallet wallet = new Wallet(UUID.randomUUID(), "a", 100, 40);
        InsufficientFundsException e = assertThrows(InsufficientFundsException.class,
                () -> task.requireAvailable(wallet, 61));
        assertTrue(e.getMessage().contains("available 60"),
                "error must state the true available amount");
    }

    @Test
    void zeroAvailableRejectsEverything() {
        Wallet wallet = new Wallet(UUID.randomUUID(), "a", 40, 40);
        assertThrows(InsufficientFundsException.class,
                () -> task.requireAvailable(wallet, 1));
    }
}
