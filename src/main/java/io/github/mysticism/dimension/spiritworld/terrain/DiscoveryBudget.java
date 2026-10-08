package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.Predicate;

/** Bounded discovery retention with an explicit render-distance coverage contract.
 *  Discovered source samples are never discarded by one all-at-once radius cut that silently
 *  shrinks visible terrain. While a window holds more than {@link #MAX_TILES} samples,
 *  eviction is farthest-first, prefers far AIR whose negative coverage can be retained,
 *  protects the exact support radius whenever farther victims exist, and caps removals per pass.
 *  The hard admission ceiling bounds the scan/sort input even if safe eviction is exhausted.
 *  Publication separately preserves the render-distance footprint within its wire budget;
 *  the absolute support floor survives even under last-resort retention pressure. */
final class DiscoveryBudget {
    /** Render/collision coverage contract in blocks; shared by the mesh append cull and the geometry-stream cull. */
    static final int RENDER_DISTANCE=128;
    /** Exact samples at or inside this radius are always retained while any farther sample can absorb the pressure. */
    static final int EXACT_RETENTION_RADIUS=24;
    /** Absolute retention floor: even last-resort eviction never reaches inside this radius. */
    static final int MIN_RETENTION_RADIUS=8;
    /** Retained source sample cap per window. */
    static final int MAX_TILES=32768;
    /** Hard admission ceiling, including staged patches while bounded eviction catches up. */
    static final int MAX_STAGED_TILES=2*MAX_TILES;
    /** Bounded per-pass eviction work; at least the combined discovery ingestion rate per tick. */
    static final int MAX_EVICTED_PER_PASS=512;
    private DiscoveryBudget() {}
    enum Admission { UPDATED, UNCHANGED, STALE, FULL }
    /** All ingestion paths use the hard ceiling. Known live updates remain admissible at the
     * ceiling, while a stored snapshot cannot contradict an evicted negative observation. */
    static Admission admit(Map<BlockPos,SourceMeshBuilder.Tile> tiles,Set<BlockPos> liveTiles,NegativeCoverage negative,
            BlockPos at,SourceMeshBuilder.Tile tile,boolean live) {
        if(!live && !tile.air() && negative.contains(at))return Admission.STALE;
        if(!live && liveTiles.contains(at))return Admission.UNCHANGED;
        if(!tiles.containsKey(at) && tiles.size()>=MAX_STAGED_TILES)return Admission.FULL;
        at=at.toImmutable();if(live){liveTiles.add(at);if(!tile.air())negative.remove(at);}
        return tile.equals(tiles.put(at,tile))?Admission.UNCHANGED:Admission.UPDATED;
    }

    /** Farthest-first victims beyond the retention radii, preservable far AIR first, bounded removals per pass.
     *  Empty while the window is within cap. Deterministic: distance descending, then position. */
    static List<BlockPos> evictionPlan(int size,Set<BlockPos> positions,Vec3d focus,Predicate<BlockPos> air) {
        return evictionPlan(size,positions,focus,air,p->true);
    }
    static List<BlockPos> evictionPlan(int size,Set<BlockPos> positions,Vec3d focus,Predicate<BlockPos> air,Predicate<BlockPos> eligible) {
        if(size<=MAX_TILES || positions.isEmpty())return List.of();
        int excess=size-MAX_TILES;
        for(int floor:new int[]{EXACT_RETENTION_RADIUS,MIN_RETENTION_RADIUS}) {
            List<BlockPos> airVictims=new ArrayList<>(),solidVictims=new ArrayList<>();
            double limit=(double)floor*floor;
            for(BlockPos p:positions) {
                if(p.getSquaredDistance(focus)<=limit || !eligible.test(p))continue;
                (air.test(p)?airVictims:solidVictims).add(p);
            }
            if(airVictims.isEmpty() && solidVictims.isEmpty())continue; // No eligible victim at this floor; try the absolute floor.
            int budget=Math.min(excess,MAX_EVICTED_PER_PASS);
            List<BlockPos> plan=new ArrayList<>(Math.min(budget,airVictims.size()+solidVictims.size()));
            take(airVictims,focus,budget,plan); // Caller must preserve negative coverage before removing AIR.
            take(solidVictims,focus,budget-plan.size(),plan);
            return plan;
        }
        return List.of(); // Everything is inside the minimum floor; support wins over the cap.
    }
    private static void take(List<BlockPos> candidates,Vec3d focus,int budget,List<BlockPos> plan) {
        if(budget<=0 || candidates.isEmpty())return;
        candidates.sort(Comparator.comparingDouble((BlockPos p)->p.getSquaredDistance(focus)).reversed()
                .thenComparingLong(BlockPos::asLong));
        plan.addAll(candidates.subList(0,Math.min(budget,candidates.size())));
    }
}
