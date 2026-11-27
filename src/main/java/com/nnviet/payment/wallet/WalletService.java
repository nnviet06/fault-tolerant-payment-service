package com.nnviet.payment.wallet;

import com.nnviet.payment.common.db.Tx;
import com.nnviet.payment.common.errors.ValidationException;
import com.nnviet.payment.common.errors.WalletNotFoundException;
import com.nnviet.payment.common.id.Ids;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

/**
 * Creates and reads wallets. Never moves money; that is the ledger workflows' job.
 */
public class WalletService {

    private final Tx tx;
    private final WalletRepository repository;

    public WalletService(Tx tx, WalletRepository repository) {
        this.tx = tx;
        this.repository = repository;
    }

    public Uni<Wallet> create(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            throw new ValidationException("ownerName is required");
        }
        Wallet wallet = new Wallet(Ids.newId(), ownerName.trim(), 0L, 0L);
        return tx.inTx(conn -> {
            repository.insert(conn, wallet);
            return wallet;
        });
    }

    public Uni<Wallet> get(UUID id) {
        return tx.inTx(conn ->
                repository.findById(conn, id).orElseThrow(() -> new WalletNotFoundException(id)));
    }
}
