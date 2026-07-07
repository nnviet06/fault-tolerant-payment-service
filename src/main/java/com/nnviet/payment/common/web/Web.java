package com.nnviet.payment.common.web;

import com.nnviet.payment.common.errors.ValidationException;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.ext.web.RoutingContext;

import java.util.UUID;

/** Request/response helpers shared by the HTTP handlers. */
public final class Web {

    private Web() {
    }

    public static JsonObject requireJsonBody(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (Exception e) {
            throw new ValidationException("request body must be valid JSON");
        }
        if (body == null) {
            throw new ValidationException("request body is required");
        }
        return body;
    }

    public static UUID requireUuid(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(fieldName + " is required");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(fieldName + " must be a UUID");
        }
    }

    public static void respondJson(RoutingContext ctx, int status, JsonObject body) {
        ctx.response()
                .setStatusCode(status)
                .putHeader("content-type", "application/json")
                .endAndForget(body.encode());
    }
}
