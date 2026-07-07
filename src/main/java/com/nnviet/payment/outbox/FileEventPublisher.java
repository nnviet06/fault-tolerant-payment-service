package com.nnviet.payment.outbox;

import io.smallrye.mutiny.Uni;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JSON-lines sink used by the torture suite: one event envelope per line.
 * The suite reads the file back and deduplicates by eventId to prove
 * "at-least-once delivery + consumer dedup = effectively once".
 */
public class FileEventPublisher implements EventPublisher {

    private final Path path;

    public FileEventPublisher(String path) {
        this.path = Path.of(path);
        try {
            if (this.path.getParent() != null) {
                Files.createDirectories(this.path.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create event sink directory", e);
        }
    }

    @Override
    public Uni<Void> publish(OutboxEvent event) {
        return Uni.createFrom().item(() -> {
            synchronized (this) {
                try {
                    Files.writeString(path, event.toJson().encode() + "\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    // surfaces to the relay, which rolls back and retries the batch
                    throw new UncheckedIOException("event sink write failed", e);
                }
            }
            return (Void) null;
        });
    }
}
