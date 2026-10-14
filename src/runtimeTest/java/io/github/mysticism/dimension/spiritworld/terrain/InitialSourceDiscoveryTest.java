package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.navigation.WalkingModePolicy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Deterministic worker/server ordering regression; no Minecraft bootstrap or model calls. */
public final class InitialSourceDiscoveryTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static WalkingModePolicy.Shallow mode(InitialSourceDiscovery discovery, boolean ownershipPending) {
        // The live terrain path calls this only after accepting local mesh ground and provenance.
        return WalkingModePolicy.shallowSupport(false, discovery.groundPending(ownershipPending), false, 0, false);
    }

    private static void emptySnapshotBeforeDiscovery() {
        var discovery = new InitialSourceDiscovery();
        var server = new ArrayDeque<Runnable>();
        var worker = new CompletableFuture<Optional<String>>();
        boolean[] ownershipPending = {true};
        Map<?, ?>[] owners = {null};
        discovery.await(() -> worker, server::addLast, (found, error) -> {
            check(discovery.pending(), "discovery stays pending while applying the result");
            check(error == null && found.orElseThrow().equals("source"), "real result reaches application");
            // Publication of a discovered source invalidates the earlier empty octree snapshot.
            ownershipPending[0] = true;
        });
        var ownershipWorker = new CompletableFuture<Map<?, ?>>();
        ownershipWorker.thenAccept(snapshot -> server.addLast(() -> {
            owners[0] = snapshot;
            ownershipPending[0] = false;
        }));
        ownershipWorker.complete(Map.of());
        server.removeFirst().run(); // The review race: current EMPTY snapshot arrives first.
        check(owners[0].isEmpty() && !ownershipPending[0], "ownership lookup completed with empty snapshot");
        for (int tick = 0; tick < 240; tick++)
            check(mode(discovery, ownershipPending[0]) == WalkingModePolicy.Shallow.WALK,
                    "current empty snapshot must not enable deep flight during initial discovery");
        check(owners[0].isEmpty(), "walking permission does not invent source ownership");
        worker.complete(Optional.of("source"));
        check(discovery.pending() && server.size() == 1, "worker completion is not server application");
        check(mode(discovery, false) == WalkingModePolicy.Shallow.WALK, "queued discovery still holds walking");
        server.removeFirst().run();
        check(!discovery.pending(), "successful application settles discovery");
        check(mode(discovery, ownershipPending[0]) == WalkingModePolicy.Shallow.WALK,
                "invalidated ownership snapshot keeps walking until revalidated");
        ownershipPending[0] = false;
        check(mode(discovery, false) == WalkingModePolicy.Shallow.DEEP,
                "settled discovery and definitively unowned ground do not hold walking forever");
        check(WalkingModePolicy.shallowSupport(true, discovery.groundPending(false), false, 0, false)
                == WalkingModePolicy.Shallow.WALK, "exact owned support takes over after discovery");
    }

    private static void terminalResults() {
        for (int outcome = 0; outcome < 4; outcome++) {
            var discovery = new InitialSourceDiscovery();
            var worker = new CompletableFuture<Optional<String>>();
            var server = new ArrayDeque<Runnable>();
            int[] calls = {0};
            discovery.await(() -> worker, server::addLast, (found, error) -> calls[0]++);
            switch (outcome) {
                case 0 -> worker.complete(Optional.empty());
                case 1 -> worker.complete(null);
                case 2 -> worker.completeExceptionally(new IllegalStateException("discovery failed"));
                case 3 -> worker.cancel(false);
            }
            check(discovery.pending(), "terminal worker result waits for server application");
            server.removeFirst().run();
            check(calls[0] == 1 && !discovery.pending(), "empty/error/cancel/null result releases permission");
            check(mode(discovery, false) == WalkingModePolicy.Shallow.DEEP, "terminal result cannot stick walking");
        }
        var discovery = new InitialSourceDiscovery();
        try {
            discovery.await(() -> { throw new IllegalArgumentException("request rejected"); }, Runnable::run,
                    (result, error) -> { throw new AssertionError("must not apply failed submission"); });
            throw new AssertionError("submission failure must propagate");
        } catch (IllegalArgumentException expected) {
            check(!discovery.pending(), "synchronous request failure releases permission");
        }
        var server = new ArrayDeque<Runnable>();
        discovery.await(() -> CompletableFuture.completedFuture(Optional.empty()), server::addLast,
                (result, error) -> { throw new IllegalArgumentException("application failed"); });
        check(discovery.pending(), "already completed worker still waits for application");
        try {
            server.removeFirst().run();
            throw new AssertionError("application failure must propagate");
        } catch (IllegalArgumentException expected) {
            check(!discovery.pending(), "application failure cannot stick permission");
        }
    }

    private static void independentWindowsAndStaleCallback() {
        var first = new InitialSourceDiscovery();
        var second = new InitialSourceDiscovery();
        var server = new ArrayDeque<Runnable>();
        var worker = new CompletableFuture<Optional<String>>();
        first.await(() -> worker, server::addLast, (result, error) -> {}); // stale session guard returns
        second.await(() -> CompletableFuture.completedFuture(Optional.empty()), server::addLast,
                (result, error) -> {});
        server.removeFirst().run();
        check(first.pending() && !second.pending(), "one window completion cannot release another's permission");
        check(mode(first, false) == WalkingModePolicy.Shallow.WALK && mode(second, false) == WalkingModePolicy.Shallow.DEEP,
                "players/windows retain independent walking decisions");
        try {
            first.await(() -> worker, server::addLast, (result, error) -> {});
            throw new AssertionError("duplicate initial discovery must not replace outstanding state");
        } catch (IllegalStateException expected) {
            check(first.pending(), "duplicate request cannot release permission");
        }
        worker.complete(Optional.empty());
        server.removeFirst().run();
        check(!first.pending(), "stale guarded callback also releases its own discovery state");
    }

    public static void main(String[] args) {
        emptySnapshotBeforeDiscovery();
        terminalResults();
        independentWindowsAndStaleCallback();
        System.out.println("InitialSourceDiscoveryTest: " + checks + " checks passed");
    }
}
