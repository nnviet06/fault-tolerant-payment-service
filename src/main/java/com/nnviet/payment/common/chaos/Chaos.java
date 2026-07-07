package com.nnviet.payment.common.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named crash-injection points for the torture suite. When the process is
 * started with CHAOS_HALT_AT=&lt;point-name&gt;, reaching that point calls
 * Runtime.halt() - a hard kill with no shutdown hooks, equivalent to kill -9.
 *
 * In a normal run (env var unset) every point is a no-op. This class exists
 * only so that crash-window scenarios ("kill between commit and publish")
 * are reproducible on demand; it is documented in docs/technical-design.md.
 */
public final class Chaos {

    /** Fires after a money workflow's transaction committed, before the HTTP response. */
    public static final String AFTER_COMMIT = "after-commit";

    /** Fires in the relay after an event was handed to the publisher, before it is marked sent. */
    public static final String RELAY_AFTER_PUBLISH_BEFORE_MARK = "relay-after-publish-before-mark";

    private static final Logger log = LoggerFactory.getLogger(Chaos.class);
    private static final String HALT_AT = System.getenv("CHAOS_HALT_AT");

    private Chaos() {
    }

    public static void point(String name) {
        if (name.equals(HALT_AT)) {
            log.warn("CHAOS: halting process at point '{}'", name);
            Runtime.getRuntime().halt(137);
        }
    }
}
