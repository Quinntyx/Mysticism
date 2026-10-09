package io.github.mysticism.landmark;

import net.minecraft.util.math.ChunkPos;
import java.util.*;
import java.util.function.LongPredicate;

/** Deterministic bounded discovery plan for already-loaded source chunks that predate spirit
 * entry. Chunk-load discovery only fires on load events, so source chunks that were loaded
 * before a spirit session (server spawn area, the player's standing view distance) never seed
 * landmark extraction on their own. This plan enumerates which loaded chunks to backfill:
 * loaded-only by contract, closest-first from the entry chunk, with stable coordinate
 * tie-breaking, a hard seed cap and the entry chunk included. It never loads or generates a
 * chunk, and it never invents geometry: the plan only selects real chunk seeds for the
 * existing hint -> Ensure extraction pipeline. */
public final class LoadedSourceBackfill {
    /** 5x5 chunk neighborhood: chunk-center surface seeds at 16-block spacing overlap the
     * local 32^3 source read and reach beyond it, so nearby generated terrain is useful
     * immediately after entry instead of only after unrelated chunk-load events. */
    public static final int RADIUS = 2;
    /** Bounded queue impact: 25 hints against the 128-hint budget, never saturating
     * source request or frontier-retry budgets on repeated entries. */
    public static final int MAX_SEEDS = 25;
    private LoadedSourceBackfill() {}
    /** Closest-first loaded chunk seeds around {@code center} (inclusive), at most
     * {@code maxSeeds}, ties broken by chunk X then chunk Z. {@code loaded} must answer
     * from current holder state only; a later recheck at use time remains required. */
    public static List<ChunkPos> plan(ChunkPos center, int radius, int maxSeeds, LongPredicate loaded) {
        if (center == null || radius < 0 || maxSeeds < 0 || loaded == null) throw new IllegalArgumentException("backfill plan bounds");
        record Candidate(ChunkPos pos, long distance) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
            if (!loaded.test(pos.toLong())) continue; // loaded-only: no holder, no seed, never a load request
            candidates.add(new Candidate(pos, (long) dx * dx + (long) dz * dz));
        }
        candidates.sort(Comparator.comparingLong(Candidate::distance)
                .thenComparingLong(c -> c.pos().x)
                .thenComparingLong(c -> c.pos().z));
        List<ChunkPos> plan = new ArrayList<>(Math.min(maxSeeds, candidates.size()));
        for (var candidate : candidates) {
            if (plan.size() >= maxSeeds) break;
            plan.add(candidate.pos());
        }
        return List.copyOf(plan);
    }
}
