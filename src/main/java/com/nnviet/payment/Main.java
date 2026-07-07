package com.nnviet.payment;

import io.vertx.mutiny.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entrypoint: create the Vert.x instance and deploy the single verticle. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle())
                .subscribe().with(
                        deploymentId -> log.info("service started (deployment {})", deploymentId),
                        failure -> {
                            log.error("service failed to start", failure);
                            System.exit(1);
                        });
    }
}
