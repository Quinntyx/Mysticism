package io.github.mysticism.landmark;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import java.util.*;

/** Exercises the production queue used by discoverLoaded and Session.tick, without booting
 * Minecraft, making chunk requests, or substituting a fake extraction/model pipeline. */
public final class SourceDiscoveryHintsTest {
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static BlockPos ordinary(int index) { return new BlockPos(10000 + index, 80, 10000); }
    private static void saturate(SourceDiscoveryHints queue) {
        for (int i = 0; i < SourceDiscoveryHints.ORDINARY_CAPACITY; i++)
            check(queue.offer(OVERWORLD, ordinary(i)), "ordinary seed retained " + i);
        check(!queue.offer(OVERWORLD, ordinary(128)), "ordinary queue remains bounded");
    }
    private static List<BlockPos> entryPlan() {
        return LoadedSourceBackfill.plan(new ChunkPos(0, 0), LoadedSourceBackfill.RADIUS,
                LoadedSourceBackfill.MAX_SEEDS, packed -> true).stream()
                .map(p -> new BlockPos(p.getStartX() + 8, 80, p.getStartZ() + 8)).toList();
    }

    private static void saturatedEntry() {
        var queue = new SourceDiscoveryHints();
        saturate(queue);
        var plan = entryPlan();
        int accepted = 0;
        for (var seed : plan) if (queue.offerBackfill(OVERWORLD, seed)) accepted++;
        check(accepted == plan.size() && accepted == 25, "full entry plan retained despite saturated ordinary hints");
        check(queue.size() == 153, "128 ordinary + 25 reserved seeds, no eviction");
        // No poll while readiness/active operations prevent extraction: all work remains retained.
        for (int i = 0; i < 20; i++) {
            for (var seed : plan) check(queue.offerBackfill(OVERWORLD, seed), "repeat entry acknowledged at capacity");
            check(queue.size() == 153, "repeat entries deduplicate without growth or loss");
        }
        check(queue.offer(OVERWORLD, plan.getFirst()), "ordinary duplicate of reserved seed accepted at cap");
        check(queue.offerBackfill(OVERWORLD, ordinary(0)), "backfill duplicate of ordinary seed accepted at cap");
        check(!queue.offerBackfill(OVERWORLD, new BlockPos(9000, 80, 9000)), "overflow is not reported as retained");
        List<BlockPos> backfill = new ArrayList<>(), original = new ArrayList<>();
        Set<SourceDiscoveryHints.Hint> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            var hint = queue.poll();
            check(seen.add(hint), "each unique seed drains exactly once");
            if (plan.contains(hint.pos())) backfill.add(hint.pos()); else original.add(hint.pos());
        }
        check(backfill.equals(plan), "every pre-entry surface seed drains in closest-first plan order");
        check(original.size() == 128, "no ordinary work evicted to make room for entry");
        for (int i = 0; i < original.size(); i++) check(original.get(i).equals(ordinary(i)), "ordinary FIFO retained");
        check(queue.poll() == null && queue.size() == 0, "drained queue is empty");
    }

    private static void continuousOrdinaryPressure() {
        var queue = new SourceDiscoveryHints();
        saturate(queue);
        var plan = entryPlan();
        for (var seed : plan) check(queue.offerBackfill(OVERWORLD, seed), "reserved admission");
        List<BlockPos> extracted = new ArrayList<>();
        int next = 128, ordinaryDrains = 0;
        for (int step = 0; step < 2 * plan.size(); step++) {
            var hint = queue.poll();
            check(hint != null, "ready operation gets a hint");
            if (plan.contains(hint.pos())) extracted.add(hint.pos());
            else {
                ordinaryDrains++;
                check(queue.offer(OVERWORLD, ordinary(next++)), "continuous chunk/edit/frontier refill");
            }
        }
        check(extracted.equals(plan), "sustained ordinary pressure cannot starve entry backfill");
        check(ordinaryDrains == 25, "backfill cannot starve ordinary discovery either");
        check(queue.size() == 128, "ordinary cap remains intact after backfill drains");
    }

    private static void overflowAndLifecycle() {
        var queue = new SourceDiscoveryHints();
        var plan = entryPlan();
        for (var seed : plan) check(queue.offerBackfill(OVERWORLD, seed), "fill reserved lane");
        var extra = new BlockPos(7000, 90, 7000);
        check(queue.offerBackfill(OVERWORLD, extra), "additional backfill uses free ordinary capacity");
        var first = queue.poll();
        check(first.pos().equals(plan.getFirst()), "backfill starts first");
        check(queue.offerBackfill(NETHER, plan.getFirst()), "same coordinates in another dimension are distinct");
        check(queue.offer(NETHER, ordinary(0)), "other dimension ordinary seed");
        queue.unload(OVERWORLD);
        check(queue.size() == 2, "dimension unload clears both lanes but retains other worlds");
        while (!queue.isEmpty()) check(queue.poll().dimension().equals(NETHER), "no stale unloaded-world hint drains");
        check(queue.offerBackfill(OVERWORLD, extra), "unload frees reserved capacity");
        queue.clear();
        check(queue.isEmpty() && queue.poll() == null, "server shutdown clears all retained work");
        check(queue.offer(OVERWORLD, ordinary(0)) && queue.offerBackfill(OVERWORLD, extra), "restart can enqueue both lanes");
        check(queue.poll().pos().equals(extra), "clear resets first-backfill preference");

        var mutable = new BlockPos.Mutable(1, 2, 3);
        queue.clear();
        check(queue.offerBackfill(OVERWORLD, mutable), "mutable seed admitted");
        mutable.set(4, 5, 6);
        check(queue.poll().pos().equals(new BlockPos(1, 2, 3)), "retained positions are immutable snapshots");
    }

    public static void main(String[] args) {
        saturatedEntry();
        continuousOrdinaryPressure();
        overflowAndLifecycle();
        System.out.println("SourceDiscoveryHintsTest: " + checks + " checks passed");
    }
}
