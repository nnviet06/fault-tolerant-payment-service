package com.nnviet.payment.common.errors;

import java.util.UUID;

public class WalletNotFoundException extends DomainException {

    public WalletNotFoundException(UUID walletId) {
        super("WALLET_NOT_FOUND", 404, "wallet not found: " + walletId);
    }
}
