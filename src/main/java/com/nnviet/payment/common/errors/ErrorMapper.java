package com.nnviet.payment.common.errors;

import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Router-wide failure handler: DomainException maps to its status/code,
 * everything else is an opaque 500 (details go to the log, not the client).
 */
public final class ErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(ErrorMapper.class);

    private ErrorMapper() {
    }

    public static void handle(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        DomainException domain = unwrap(failure);
        if (domain != null) {
            respond(ctx, domain.httpStatus(), domain.code(), domain.getMessage());
            return;
        }
        if (failure == null) {
            // ctx.fail(statusCode) without a throwable, e.g. vertx-web 404
            int status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
            respond(ctx, status, "HTTP_" + status, "request failed with status " + status);
            return;
        }
        log.error("unhandled failure on {} {}", ctx.request().method(), ctx.request().path(), failure);
        respond(ctx, 500, "INTERNAL_ERROR", "unexpected server error");
    }

    private static DomainException unwrap(Throwable t) {
        if (t instanceof DomainException de) {
            return de;
        }
        if (t != null && t.getCause() instanceof DomainException de) {
            return de;
        }
        return null;
    }

    private static void respond(RoutingContext ctx, int status, String code, String message) {
        JsonObject body = new JsonObject()
                .put("error", new JsonObject().put("code", code).put("message", message));
        ctx.response()
                .setStatusCode(status)
                .putHeader("content-type", "application/json")
                .endAndForget(body.encode());
    }
}
