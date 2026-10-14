package io.github.mysticism.landmark;

import net.minecraft.util.math.BlockPos;
import java.util.LinkedHashSet;
import java.util.Objects;

/** Server-thread hint admission for the existing Ensure pipeline. An entry backfill has a
 * reserved, bounded lane so a full chunk/edit/frontier queue cannot discard its whole plan.
 * Neither lane drains until Session's existing readiness gate permits an Ensure. */
final class SourceDiscoveryHints {
    static final int ORDINARY_CAPACITY = 128;
    static final int BACKFILL_CAPACITY = LoadedSourceBackfill.MAX_SEEDS;
    record Hint(String dimension, BlockPos pos) {
        Hint {
            Objects.requireNonNull(dimension);
            pos = Objects.requireNonNull(pos).toImmutable();
        }
    }
    private final LinkedHashSet<Hint> ordinary = new LinkedHashSet<>();
    private final LinkedHashSet<Hint> backfill = new LinkedHashSet<>();
    private boolean backfillTurn = true;

    boolean offer(String dimension, BlockPos pos) {
        Hint hint = new Hint(dimension, pos);
        if (contains(hint)) return true;
        if (ordinary.size() >= ORDINARY_CAPACITY) return false;
        return ordinary.add(hint);
    }

    /** Returns true only if the seed is retained (including already queued duplicates).
     * Overflow beyond the reserved lane may use ordinary space, but never evicts old work. */
    boolean offerBackfill(String dimension, BlockPos pos) {
        Hint hint = new Hint(dimension, pos);
        if (contains(hint)) return true;
        if (backfill.size() < BACKFILL_CAPACITY) return backfill.add(hint);
        return offer(dimension, pos);
    }

    private boolean contains(Hint hint) { return ordinary.contains(hint) || backfill.contains(hint); }

    /** FIFO within each lane; alternate when both have work so neither starves. */
    Hint poll() {
        if (isEmpty()) return null;
        boolean takeBackfill = !backfill.isEmpty() && (backfillTurn || ordinary.isEmpty());
        var lane = takeBackfill ? backfill : ordinary;
        Hint hint = lane.iterator().next();
        lane.remove(hint);
        backfillTurn = !takeBackfill;
        return hint;
    }

    int size() { return ordinary.size() + backfill.size(); }
    boolean isEmpty() { return ordinary.isEmpty() && backfill.isEmpty(); }
    void unload(String dimension) {
        ordinary.removeIf(h -> h.dimension().equals(dimension));
        backfill.removeIf(h -> h.dimension().equals(dimension));
    }
    void clear() {
        ordinary.clear();
        backfill.clear();
        backfillTurn = true;
    }
}
