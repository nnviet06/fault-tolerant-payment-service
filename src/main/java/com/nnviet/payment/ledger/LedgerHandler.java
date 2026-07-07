package com.nnviet.payment.ledger;

import com.nnviet.payment.common.Hashes;
import com.nnviet.payment.common.Money;
import com.nnviet.payment.common.db.Tx;
import com.nnviet.payment.common.errors.ValidationException;
import com.nnviet.payment.common.idempotency.IdempotencyService;
import com.nnviet.payment.common.web.Web;
import com.nnviet.payment.ledger.workflow.CommitHoldWorkflow;
import com.nnviet.payment.ledger.workflow.DepositWorkflow;
import com.nnviet.payment.ledger.workflow.HoldWorkflow;
import com.nnviet.payment.ledger.workflow.RollbackHoldWorkflow;
import com.nnviet.payment.ledger.workflow.TransferWorkflow;
import com.nnviet.payment.ledger.workflow.WithdrawWorkflow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;

import java.util.List;
import java.util.UUID;

/**
 * HTTP surface of the ledger domain - every money movement enters here.
 * Each money-moving POST requires an Idempotency-Key header and runs as
 * IdempotencyService.execute(workflow), i.e. one database transaction
 * containing the idempotency claim, the movement, the ledger entry and the
 * outbox event.
 *
 * The request hash is built from the PARSED parameters (semantic
 * canonicalization), so the same logical request re-encoded with different
 * JSON key order still replays instead of failing as key reuse.
 */
public class LedgerHandler {

    private final Tx tx;
    private final IdempotencyService idempotency;
    private final LedgerRepository ledger;
    private final DepositWorkflow deposit;
    private final WithdrawWorkflow withdraw;
    private final TransferWorkflow transfer;
    private final HoldWorkflow hold;
    private final CommitHoldWorkflow commitHold;
    private final RollbackHoldWorkflow rollbackHold;

    public LedgerHandler(Tx tx, IdempotencyService idempotency, LedgerRepository ledger,
                         DepositWorkflow deposit, WithdrawWorkflow withdraw,
                         TransferWorkflow transfer, HoldWorkflow hold,
                         CommitHoldWorkflow commitHold, RollbackHoldWorkflow rollbackHold) {
        this.tx = tx;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.deposit = deposit;
        this.withdraw = withdraw;
        this.transfer = transfer;
        this.hold = hold;
        this.commitHold = commitHold;
        this.rollbackHold = rollbackHold;
    }

    public void register(Router router) {
        router.post("/deposits").handler(this::depositHandler);
        router.post("/withdrawals").handler(this::withdrawHandler);
        router.post("/transfers").handler(this::transferHandler);
        router.post("/holds").handler(this::holdHandler);
        router.post("/holds/:id/commit").handler(this::commitHoldHandler);
        router.post("/holds/:id/rollback").handler(this::rollbackHoldHandler);
        router.get("/ledger/entries").handler(this::listEntries);
        router.get("/ledger/verify").handler(this::verifyChain);
    }

    private void depositHandler(RoutingContext ctx) {
        String idemKey = requireIdemKey(ctx);
        JsonObject body = Web.requireJsonBody(ctx);
        UUID walletId = Web.requireUuid(body.getString("walletId"), "walletId");
        long amount = Money.requireAmount(body.getValue("amount"));
        String requestHash = Hashes.sha256Hex("POST|/deposits|" + walletId + "|" + amount);
        idempotency.execute(idemKey, requestHash, 201,
                        conn -> deposit.run(conn, walletId, amount, idemKey))
                .subscribe().with(outcome -> respond(ctx, outcome), ctx::fail);
    }

    private void withdrawHandler(RoutingContext ctx) {
        String idemKey = requireIdemKey(ctx);
        JsonObject body = Web.requireJsonBody(ctx);
        UUID walletId = Web.requireUuid(body.getString("walletId"), "walletId");
        long amount = Money.requireAmount(body.getValue("amount"));
        String requestHash = Hashes.sha256Hex("POST|/withdrawals|" + walletId + "|" + amount);
        idempotency.execute(idemKey, requestHash, 201,
                        conn -> withdraw.run(conn, walletId, amount, idemKey))
                .subscribe().with(outcome -> respond(ctx, outcome), ctx::fail);
    }

    private void transferHandler(RoutingContext ctx) {
        String idemKey = requireIdemKey(ctx);
        JsonObject body = Web.requireJsonBody(ctx);
        UUID fromWalletId = Web.requireUuid(body.getString("fromWalletId"), "fromWalletId");
        UUID toWalletId = Web.requireUuid(body.getString("toWalletId"), "toWalletId");
        long amount = Money.requireAmount(body.getValue("amount"));
        String requestHash = Hashes.sha256Hex(
                "POST|/transfers|" + fromWalletId + "|" + toWalletId + "|" + amount);
        idempotency.execute(idemKey, requestHash, 201,
                        conn -> transfer.run(conn, fromWalletId, toWalletId, amount, idemKey))
                .subscribe().with(outcome -> respond(ctx, outcome), ctx::fail);
    }

    private void holdHandler(RoutingContext ctx) {
        String idemKey = requireIdemKey(ctx);
        JsonObject body = Web.requireJsonBody(ctx);
        UUID walletId = Web.requireUuid(body.getString("walletId"), "walletId");
        long amount = Money.requireAmount(body.getValue("amount"));
        int ttlSeconds = requireTtlSeconds(body.getValue("ttlSeconds"));
        String requestHash = Hashes.sha256Hex(
                "POST|/holds|" + walletId + "|" + amount + "|" + ttlSeconds);
        idempotency.execute(idemKey, requestHash, 201,
                        conn -> hold.run(conn, walletId, amount, ttlSeconds, idemKey))
                .subscribe().with(outcome -> respond(ctx, outcome), ctx::fail);
    }

    private void commitHoldHandler(RoutingContext ctx) {
        String idemKey = requireIdemKey(ctx);
        UUID holdId = Web.requireUuid(ctx.pathParam("id"), "hold id");
        JsonObject body = Web.requireJsonBody(ctx);
        UUID destinationWalletId =
                Web.requireUuid(body.getString("destinationWalletId"), "destinationWalletId");
        String requestHash = Hashes.sha256Hex(
                "POST|/holds/commit|" + holdId + "|" + destinationWalletId);
        idempotency.execute(idemKey, requestHash, 200,
                        conn -> commitHold.run(conn, holdId, destinationWalletId, idemKey))
                .subscribe().with(outcome -> respond(ctx, outcome), ctx::fail);
    }

    private void rollbackHoldHandler(RoutingContext ctx) {
        String idemKey = requireIdemKey(ctx);
        UUID holdId = Web.requireUuid(ctx.pathParam("id"), "hold id");
        String requestHash = Hashes.sha256Hex("POST|/holds/rollback|" + holdId);
        idempotency.execute(idemKey, requestHash, 200,
                        conn -> rollbackHold.run(conn, holdId, idemKey))
                .subscribe().with(outcome -> respond(ctx, outcome), ctx::fail);
    }

    private void listEntries(RoutingContext ctx) {
        List<String> walletParams = ctx.queryParam("walletId");
        int limit = parseLimit(ctx.queryParam("limit"));
        tx.inTx(conn -> walletParams.isEmpty()
                        ? ledger.listRecent(conn, limit)
                        : ledger.listByWallet(conn,
                                Web.requireUuid(walletParams.get(0), "walletId"), limit))
                .subscribe().with(
                        entries -> {
                            JsonArray json = new JsonArray();
                            entries.forEach(e -> json.add(e.toJson()));
                            Web.respondJson(ctx, 200, new JsonObject().put("entries", json));
                        },
                        ctx::fail);
    }

    private void verifyChain(RoutingContext ctx) {
        tx.inTx(ledger::verifyChain)
                .subscribe().with(
                        verification -> Web.respondJson(ctx, 200, verification.toJson()),
                        ctx::fail);
    }

    private static String requireIdemKey(RoutingContext ctx) {
        String key = ctx.request().getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new ValidationException(
                    "Idempotency-Key header is required on money operations");
        }
        return key.trim();
    }

    private static int requireTtlSeconds(Object jsonValue) {
        if (jsonValue == null) {
            throw new ValidationException("ttlSeconds is required");
        }
        if (!(jsonValue instanceof Integer ttl)) {
            throw new ValidationException("ttlSeconds must be an integer between 1 and 86400");
        }
        if (ttl < 1 || ttl > 86_400) {
            throw new ValidationException("ttlSeconds must be between 1 and 86400");
        }
        return ttl;
    }

    private static int parseLimit(List<String> params) {
        if (params.isEmpty()) {
            return 100;
        }
        try {
            int limit = Integer.parseInt(params.get(0));
            if (limit < 1 || limit > 500) {
                throw new ValidationException("limit must be between 1 and 500");
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new ValidationException("limit must be an integer");
        }
    }

    private static void respond(RoutingContext ctx, IdempotencyService.IdemOutcome outcome) {
        ctx.response()
                .setStatusCode(outcome.status())
                .putHeader("content-type", "application/json")
                .putHeader("X-Idempotent-Replay", String.valueOf(outcome.replayed()))
                .endAndForget(outcome.body().encode());
    }
}
