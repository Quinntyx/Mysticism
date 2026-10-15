package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.VerticalScanQueue;
import io.github.mysticism.landmark.extract.VerticalSeeds;
import java.util.*;
import java.util.function.Predicate;

/** Exercises the same admission/scan/drain scheduler used by SourceLandmarks ticks. */
public final class VerticalSchedulerTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static VerticalSeeds.Scan scan(int key) {
        return new VerticalSeeds.Scan(List.of(new VerticalSeeds.Column(key * 16L + 2, 2, 156)), -64, 320);
    }
    public static void main(String[] args) {
        overflowThenDrain();
        backpressure();
        unloadAndReload();
        System.out.println("VerticalSchedulerTest: " + checks + " checks passed");
    }

    private static void overflowThenDrain() {
        int capacity = SourceLandmarks.VERTICAL_QUEUE;
        int total = capacity * 3 + 1; // exceeds both the original queue and a second queue of that size
        var queue = new VerticalScanQueue<Integer>(capacity);
        Map<Integer, Integer> snapshots = new HashMap<>();
        for (int key = 0; key < total; key++) {
            int k = key;
            queue.offer(k, () -> { snapshots.merge(k, 1, Integer::sum); return scan(k); });
        }
        check(queue.activeCount() == capacity && queue.deferredCount() == total - capacity,
                "overflow is retained as keys without exceeding the full-scan cap");
        check(snapshots.size() == capacity, "no eager snapshot for overflow chunks");
        queue.offer(0, () -> { throw new AssertionError("duplicate active snapshot"); });
        queue.offer(total - 1, () -> { throw new AssertionError("duplicate deferred snapshot"); });
        check(queue.size() == total, "duplicate load events are coalesced");

        Map<Integer, Set<BlockPoint>> reads = new HashMap<>();
        Map<Integer, List<Long>> levels = new LinkedHashMap<>();
        int ticks = 0, sampled = 0;
        boolean lateArrival = false;
        while (queue.size() > 0) {
            check(++ticks < total * 5, "finite loaded overflow must eventually drain without another load event");
            int priorSnapshots = snapshots.values().stream().mapToInt(Integer::intValue).sum();
            int[] actualReads = {0};
            sampled += queue.advance(SourceLandmarks.VERTICAL_PROBE_BUDGET, key -> {
                snapshots.merge(key, 1, Integer::sum);
                return scan(key);
            }, key -> p -> {
                actualReads[0]++;
                check(reads.computeIfAbsent(key, k -> new HashSet<>()).add(p), "duplicate event did not restart scan");
                return Set.of(-55L, 40L, 150L).contains(p.y());
            }, () -> true, (key, p) -> levels.computeIfAbsent(key, k -> new ArrayList<>()).add(p.y()));
            check(actualReads[0] <= SourceLandmarks.VERTICAL_PROBE_BUDGET, "strict shared per-tick read limit");
            check(queue.activeCount() <= capacity, "active scan cap holds while promoting overflow");
            check(snapshots.values().stream().mapToInt(Integer::intValue).sum() - priorSnapshots <= 1,
                    "at most one deferred snapshot per tick");
            if (!lateArrival && queue.activeCount() < capacity && queue.deferredCount() > 0) {
                queue.offer(total, () -> { throw new AssertionError("new arrival bypassed deferred FIFO"); });
                lateArrival = true;
            }
        }
        check(lateArrival, "tested new load during draining");
        check(sampled == (total + 1) * 215, "deferred scans report every block read to the shared source budget");
        check(snapshots.size() == total + 1 && snapshots.values().stream().allMatch(n -> n == 1),
                "all overflow chunks eventually snapshotted exactly once");
        check(new ArrayList<>(levels.keySet()).equals(java.util.stream.IntStream.rangeClosed(0, total).boxed().toList()),
                "new arrivals cannot starve earlier deferred chunks");
        check(levels.values().stream().allMatch(ys -> ys.equals(List.of(-55L, 40L, 150L))),
                "every overflow chunk discovers deep, middle and high terrain");
    }

    private static void backpressure() {
        var queue = new VerticalScanQueue<Integer>(1);
        queue.offer(0, () -> scan(0));
        queue.offer(1, () -> { throw new AssertionError("overflow captured live snapshot"); });
        List<BlockPoint> hints = new ArrayList<>();
        int[] reads = {0}, snapshots = {0};
        java.util.function.Function<Integer, Predicate<BlockPoint>> reader = key -> p -> {
            reads[0]++;
            return Set.of(-55L, 40L, 150L).contains(p.y());
        };
        java.util.function.Function<Integer, VerticalSeeds.Scan> snapshot = key -> { snapshots[0]++; return scan(key); };
        hints.add(new BlockPoint(0, 0, 0)); // sink starts full
        for (int i = 0; i < 10; i++)
            check(queue.advance(128, snapshot, reader, () -> hints.isEmpty(), (key, p) -> hints.add(p)) == 0,
                    "full hint queue pauses reads");
        check(reads[0] == 0 && snapshots[0] == 0 && queue.size() == 2, "backpressure loses no active/deferred work");
        hints.clear();
        List<Long> emitted = new ArrayList<>();
        for (int tick = 0; queue.size() > 0; tick++) {
            check(tick < 20, "draining sink eventually releases scans and admits overflow");
            queue.advance(128, snapshot, reader, () -> hints.isEmpty(), (key, p) -> hints.add(p));
            if (!hints.isEmpty()) {
                int priorReads = reads[0];
                for (int i = 0; i < 3; i++)
                    check(queue.advance(128, snapshot, reader, () -> hints.isEmpty(), (key, p) -> hints.add(p)) == 0,
                            "completed seeds wait without resampling");
                check(reads[0] == priorReads, "no reads under seed backpressure");
                emitted.add(hints.removeFirst().y());
            }
        }
        check(emitted.equals(List.of(-55L, 40L, 150L, -55L, 40L, 150L)), "completed and overflow seeds all delivered once");
        check(snapshots[0] == 1 && reads[0] == 430, "only one deferred snapshot and no repeated reads");
    }

    private record Key(String dimension, int chunk) {}
    private static void unloadAndReload() {
        var queue = new VerticalScanQueue<Key>(2);
        Key a0 = new Key("a", 0), a1 = new Key("a", 1), a2 = new Key("a", 2);
        Key b0 = new Key("b", 0), b1 = new Key("b", 1);
        for (Key key : List.of(a0, a1, a2, b0, b1)) queue.offer(key, () -> scan(key.chunk()));
        queue.remove(a1); // active chunk unload
        queue.remove(a2); // deferred chunk unload
        check(queue.activeCount() == 1 && queue.deferredCount() == 2, "chunk unload removes both forms of work");
        queue.offer(a2, () -> { throw new AssertionError("reload skipped deferred keys"); });
        queue.removeIf(key -> key.dimension().equals("a")); // dimension unload
        check(queue.activeCount() == 0 && queue.deferredCount() == 2, "dimension unload clears active and reloaded deferred keys");
        List<Key> attempted = new ArrayList<>();
        queue.advance(128, key -> { attempted.add(key); return null; }, key -> {
            throw new AssertionError("read of stale deferred chunk");
        }, () -> true, (key, p) -> { throw new AssertionError("invented seed"); });
        check(attempted.equals(List.of(b0)) && queue.size() == 1, "stale lookup discarded with one attempt, not an unbounded sweep");
        queue.advance(128, key -> { attempted.add(key); return scan(key.chunk()); }, key -> null,
                () -> true, (key, p) -> { throw new AssertionError("unloaded active seed"); });
        check(attempted.equals(List.of(b0, b1)) && queue.size() == 0, "active non-loading miss removed without inventing air");
        queue.offer(a2, () -> scan(2));
        check(queue.activeCount() == 1, "a later real chunk load can be scanned again");
        queue.offer(b0, () -> scan(0));
        queue.offer(b1, () -> scan(1));
        queue.clear(); // server stop
        check(queue.size() == 0 && queue.activeCount() == 0 && queue.deferredCount() == 0, "shutdown drops all deferred state");
        try { new VerticalScanQueue<>(0); throw new AssertionError("invalid capacity accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
}
