package io.github.mysticism.embedding;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Latest-world readiness subscription. Delivery is explicitly marshalled to the UI/server
 * executor; the provider's callback thread never touches world/UI state. No game references
 * are retained after completion or invalidation. A stopped/replaced world cannot notify later. */
public final class EmbeddingReadinessFeedback<S> {
    private static final class Ticket {
        Consumer<Throwable> notice;
        Ticket(Consumer<Throwable> notice) { this.notice = notice; }
    }
    private S owner;
    private Ticket current;

    public void watch(S server, CompletableFuture<Void> readiness, Executor delivery, Consumer<Throwable> notice) {
        Objects.requireNonNull(server); Objects.requireNonNull(readiness);
        Objects.requireNonNull(delivery); Objects.requireNonNull(notice);
        Ticket ticket = new Ticket(notice);
        synchronized (this) {
            clear(); owner = server; current = ticket;
        }
        // The future captures only a ticket, not the server. Pending callbacks can outlive a
        // disconnect, but never prevent the old server from being collected through this gate.
        readiness.whenComplete((ignored, error) -> {
            try {
                delivery.execute(() -> {
                    Consumer<Throwable> callback;
                    synchronized (this) {
                        if (current != ticket) return;
                        callback = ticket.notice;
                        clear();
                    }
                    callback.accept(error);
                });
            } catch (RuntimeException rejected) {
                synchronized (this) { if (current == ticket) clear(); }
                System.getLogger("Mysticism").log(System.Logger.Level.WARNING,
                        "Embedding readiness notification could not reach its owning executor", rejected);
            }
        });
    }

    /** Identity, not equals: a delayed stop from the previous world cannot clear its replacement. */
    public synchronized void stop(S server) {
        if (owner == server) clear();
    }
    public synchronized void clear() {
        if (current != null) current.notice = null;
        owner = null; current = null;
    }
}
