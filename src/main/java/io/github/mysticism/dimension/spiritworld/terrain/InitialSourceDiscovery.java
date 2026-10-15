package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Server-thread-owned initial discovery state, independent of octree snapshot readiness.
 * A current empty ownership snapshot is provisional while discovery is still outstanding.
 * This state grants walking-mode permission only, never source ownership or exit authority. */
final class InitialSourceDiscovery {
    private boolean pending;

    boolean pending() { return pending; }

    boolean groundPending(boolean ownershipPending) { return pending || ownershipPending; }

    /** Keep the permission until the result is applied on the server, not just until the worker
     * future completes. The callback may itself invalidate the earlier ownership snapshot. */
    <T> CompletableFuture<T> await(Supplier<CompletableFuture<T>> request, Executor server,
            BiConsumer<T, Throwable> apply) {
        if (pending) throw new IllegalStateException("initial discovery already pending");
        pending = true;
        try {
            return request.get().whenComplete((result, error) -> server.execute(() -> {
                try { apply.accept(result, error); }
                finally { pending = false; }
            }));
        } catch (RuntimeException | Error failure) {
            pending = false;
            throw failure;
        }
    }
}
