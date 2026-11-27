package com.nnviet.payment.wallet;

import com.nnviet.payment.common.web.Web;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;

import java.util.UUID;

/**
 * Wallet HTTP routes. Creating a wallet needs no Idempotency-Key: no money moves,
 * so a duplicate is just an empty wallet.
 */
public class WalletHandler {

    private final WalletService service;

    public WalletHandler(WalletService service) {
        this.service = service;
    }

    public void register(Router router) {
        router.post("/wallets").handler(this::create);
        router.get("/wallets/:id").handler(this::get);
    }

    private void create(RoutingContext ctx) {
        JsonObject body = Web.requireJsonBody(ctx);
        String ownerName = body.getString("ownerName");
        service.create(ownerName)
                .subscribe().with(w -> Web.respondJson(ctx, 201, w.toJson()), ctx::fail);
    }

    private void get(RoutingContext ctx) {
        UUID id = Web.requireUuid(ctx.pathParam("id"), "id");
        service.get(id)
                .subscribe().with(w -> Web.respondJson(ctx, 200, w.toJson()), ctx::fail);
    }
}
