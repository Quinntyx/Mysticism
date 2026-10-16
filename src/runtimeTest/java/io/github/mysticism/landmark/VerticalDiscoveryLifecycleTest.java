package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.VerticalDiscoveryLifecycle;
import io.github.mysticism.landmark.extract.VerticalScanQueue;
import io.github.mysticism.landmark.extract.VerticalSeeds;
import java.util.*;

/** The production STARTING -> early chunk events -> STARTED Session adoption -> tick
 * admission path, without bootstrapping a Minecraft world or inventing source ownership. */
public final class VerticalDiscoveryLifecycleTest {
    private static int checks;
    private record Key(String dimension, int chunk) {}
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static VerticalSeeds.Scan scan(Key key) {
        return new VerticalSeeds.Scan(List.of(new VerticalSeeds.Column(key.chunk * 16L + 2, 2, 156)), -64, 320);
    }
    public static void main(String[] args) {
        earlySpawnLoadsThenSession();
        unloadStopAndRestart();
        System.out.println("VerticalDiscoveryLifecycleTest: " + checks + " checks passed (lifecycle fixtures; no game bootstrap)");
    }

    private static void earlySpawnLoadsThenSession() {
        int capacity = SourceLandmarks.VERTICAL_QUEUE;
        var lifecycle = new VerticalDiscoveryLifecycle<Object, Key>(capacity);
        Object server = new Object();
        // Fabric SERVER_STARTING precedes world preparation and spawn CHUNK_LOAD events.
        var early = lifecycle.start(server);
        int total = capacity * 2 + 3;
        List<Key> expected = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            var key = new Key("minecraft:overworld", i);
            expected.add(key);
            lifecycle.loaded(server, key);
            lifecycle.loaded(server, key); // duplicate callbacks cannot reset/dequeue work
        }
        Key unloaded = expected.remove(1);
        lifecycle.unloaded(server, unloaded);
        Key removedWorld = new Key("minecraft:the_nether", -1);
        lifecycle.loaded(server, removedWorld);
        lifecycle.unloadWorld(server, key -> key.dimension.equals("minecraft:the_nether"));
        check(early.activeCount() == 0 && early.deferredCount() == total - 1,
                "pre-Session loads must retain distinct keys with no snapshots or block reads");
        // The original bug lost every early key because SESSIONS.get(server) was null.
        // SERVER_STARTED must adopt, not replace or cap away these still-resident spawn chunks.
        VerticalScanQueue<Key> session = lifecycle.start(server);
        check(session == early && session.size() == expected.size(), "Session startup erased spawn discovery backlog");
        Map<Key, Integer> snapshots = new LinkedHashMap<>();
        int[] reads = {0};
        // Hold the sink while model readiness is pending. Snapshots are bounded independently
        // of readiness; no model work or block reads may escape this backpressure.
        for (int tick = 0; tick < capacity + 2; tick++) {
            int before = snapshots.size();
            check(session.advance(SourceLandmarks.VERTICAL_PROBE_BUDGET, key -> {
                snapshots.merge(key, 1, Integer::sum);
                return scan(key);
            }, key -> p -> { reads[0]++; return true; }, () -> false,
                    (key, p) -> { throw new AssertionError("full sink accepted a seed"); }) == 0,
                    "blocked discovery read source cells");
            check(snapshots.size() - before <= 1 && session.activeCount() <= capacity,
                    "startup admission exceeded one snapshot per tick / active scan cap");
        }
        check(reads[0] == 0 && session.size() == expected.size(), "model wait lost early spawn work");
        Key late = new Key("minecraft:overworld", total);
        expected.add(late);
        lifecycle.loaded(server, late);
        Map<Key, List<Long>> levels = new LinkedHashMap<>();
        // No further CHUNK_LOAD event, no player visit and no surface-connected path is needed.
        for (int tick = 0; session.size() > 0; tick++) {
            check(tick < total * 5, "persistent spawn backlog never drained after readiness");
            int beforeSnapshots = snapshots.values().stream().mapToInt(Integer::intValue).sum();
            int beforeReads = reads[0];
            int sampled = session.advance(SourceLandmarks.VERTICAL_PROBE_BUDGET, key -> {
                check(expected.contains(key), "read unloaded source world/chunk");
                snapshots.merge(key, 1, Integer::sum);
                return scan(key);
            }, key -> p -> {
                reads[0]++;
                return Set.of(-55L, 40L, 150L).contains(p.y()); // isolated deep/middle/high cave levels
            }, () -> true, (key, p) -> levels.computeIfAbsent(key, ignored -> new ArrayList<>()).add(p.y()));
            check(reads[0] - beforeReads == sampled && sampled <= SourceLandmarks.VERTICAL_PROBE_BUDGET,
                    "startup reads escaped shared source tick accounting");
            check(snapshots.values().stream().mapToInt(Integer::intValue).sum() - beforeSnapshots <= 1,
                    "unbounded startup snapshot sweep");
        }
        check(new ArrayList<>(levels.keySet()).equals(expected), "early keys lost, duplicated or bypassed by later loads");
        check(levels.values().stream().allMatch(ys -> ys.equals(List.of(-55L, 40L, 150L))),
                "persistent spawn chunks failed to discover independent underground height bands");
        check(snapshots.size() == expected.size() && snapshots.values().stream().allMatch(n -> n == 1),
                "early chunk duplicate restarted a scan");
        check(reads[0] == expected.size() * 215, "not every retained spawn column was scanned exactly once");
        lifecycle.stop(server);
    }

    private static void unloadStopAndRestart() {
        var lifecycle = new VerticalDiscoveryLifecycle<String, Key>(2);
        // Identity ownership: two server lifetimes with equal labels must never share queues.
        String first = new String("integrated"), second = new String("integrated");
        Key a = new Key("minecraft:overworld", 0), b = new Key("minecraft:overworld", 1);
        var one = lifecycle.start(first);
        var two = lifecycle.start(second);
        lifecycle.loaded(first, a);
        lifecycle.loaded(second, b);
        check(one != two && one.size() == 1 && two.size() == 1, "server queues shared by equals instead of identity");
        one.advance(1, key -> scan(key), key -> p -> false, () -> true, (key, p) -> {});
        lifecycle.unloaded(first, a); // active scan unload, including before Session creation
        check(one.size() == 0 && two.size() == 1, "early active unload leaked/cross-cancelled work");
        lifecycle.loaded(first, a); // a real reload must be observed afresh
        check(one.deferredCount() == 1, "unloaded startup chunk could not be reloaded");
        lifecycle.stop(first); // also covers stopping before SERVER_STARTED
        check(one.size() == 0 && two.size() == 1, "startup abort retained keys or stopped another server");
        lifecycle.loaded(first, a); // late callback during world teardown must not recreate state
        lifecycle.unloaded(first, a);
        lifecycle.unloadWorld(first, key -> true);
        lifecycle.stop(first);
        var restarted = lifecycle.start(first);
        check(restarted != one && restarted.size() == 0, "stop/late callbacks contaminated next server lifecycle");
        lifecycle.loaded(first, a);
        check(restarted.deferredCount() == 1, "next integrated-server lifecycle failed to discover");
        lifecycle.unloadWorld(first, key -> key.dimension.equals("minecraft:overworld"));
        check(restarted.size() == 0, "pre-Session world unload did not remove deferred keys");
        lifecycle.stop(first);
        lifecycle.stop(second);
        check(two.size() == 0, "second server stop retained backlog");
    }
}
