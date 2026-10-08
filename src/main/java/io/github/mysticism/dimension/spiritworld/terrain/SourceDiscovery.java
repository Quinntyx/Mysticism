package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SourceLandmarks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Real source discovery extent derived from the player's render/view distance.
 *
 * <p>Entry and shallow movement keep their immediate bounded near-field requests; this planner
 * schedules the remaining source observation across the whole render/view distance instead of a
 * fixed tiny neighborhood. One aligned side-32 tile is the largest single bounded
 * {@link SourceLandmarks#region} volume (32^3 = 32768 cells), so coverage is a center-out schedule
 * of tiles, each a real generated-source read, rather than one oversized query.</p>
 *
 * <p>Pure geometry/scheduling only: no world access, registry work or threading. The caller
 * (SpiritTerrainService) owns issue rate, ingestion and window lifecycle.</p> */
final class SourceDiscovery {
    /** One bounded SourceLandmarks region volume per tile (checkBounds cap is 32768 cells). */
    static final int TILE = 32;
    static final int MIN_RADIUS = TILE;
    /** Bounded worst case (a 16-chunk view distance). Larger client view distances still discover
     * progressively outward to this extent; nothing depends on the far tail being complete. */
    static final int MAX_RADIUS = 256;
    static final int MAX_VERTICAL_RADIUS = 64;
    /** Mesh cull tracks the visible horizon instead of a fixed 128-block locality. */
    static final int MIN_HORIZON = 128, MAX_HORIZON = 192;
    /** Concurrent region reads per window; SourceLandmarks serializes reads behind one live operation. */
    static final int MAX_INFLIGHT = 2;
    /** Maximum discovered far-field nodes retained per window (compacted, octree-merged). */
    static final int MAX_NODES = 65536;
    /** Cells within this radius of the viewer keep the full unchanged near allowance; beyond it,
     * far-field discovery admission shares a reserved bounded budget (see {@link LocalAdmission}). */
    static final double NEAR_EXACT_RADIUS = 32;
    static final int RETRY_INCOMPLETE_TICKS = 200, RETRY_ERROR_TICKS = 600;

    private SourceDiscovery() {}

    static int horizontalRadius(int viewDistanceChunks) {
        int raw = Math.max(1, viewDistanceChunks) * 16;
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, raw));
    }

    static int verticalRadius(int horizontal) {
        return Math.min(horizontal, MAX_VERTICAL_RADIUS);
    }

    static int meshHorizon(int viewDistanceChunks) {
        return Math.max(MIN_HORIZON, Math.min(MAX_HORIZON, horizontalRadius(viewDistanceChunks) + 16));
    }

    /** Retention radius for covered tiles and discovered nodes around the discovery center. */
    static int retentionRadius(int viewDistanceChunks) {
        return horizontalRadius(viewDistanceChunks) + TILE;
    }

    static Bounds tile(Vec3d center, int tileX, int tileY, int tileZ) {
        int x = MathHelper.floor(center.x), y = MathHelper.floor(center.y), z = MathHelper.floor(center.z);
        long baseX = (long) Math.floorDiv(x, TILE) + tileX, baseY = (long) Math.floorDiv(y, TILE) + tileY,
                baseZ = (long) Math.floorDiv(z, TILE) + tileZ;
        return new Bounds(baseX * TILE, baseY * TILE, baseZ * TILE, baseX * TILE + TILE, baseY * TILE + TILE, baseZ * TILE + TILE);
    }

    private static Vec3d center(Bounds b) {
        return new Vec3d(b.minX() + TILE / 2.0, b.minY() + TILE / 2.0, b.minZ() + TILE / 2.0);
    }

    /** Center-out ordered source tiles covering the player's render/view distance box around
     * {@code center}. Tiles are aligned to a global grid so coverage is stable across replans.
     * Deterministic: distance order, then position tiebreak. */
    static List<Bounds> plan(Vec3d center, int viewDistanceChunks) {
        int horizontal = horizontalRadius(viewDistanceChunks), vertical = verticalRadius(horizontal);
        int rangeX = Math.ceilDiv(horizontal, TILE), rangeY = Math.ceilDiv(vertical, TILE);
        List<Bounds> tiles = new ArrayList<>();
        for (int ty = -rangeY; ty <= rangeY; ty++)
            for (int tz = -rangeX; tz <= rangeX; tz++)
                for (int tx = -rangeX; tx <= rangeX; tx++) {
                    Bounds b = tile(center, tx, ty, tz);
                    if (b.minX() > Math.floor(center.x) + horizontal || b.maxX() - 1 < Math.floor(center.x) - horizontal) continue;
                    if (b.minZ() > Math.floor(center.z) + horizontal || b.maxZ() - 1 < Math.floor(center.z) - horizontal) continue;
                    if (b.minY() > Math.floor(center.y) + vertical || b.maxY() - 1 < Math.floor(center.y) - vertical) continue;
                    tiles.add(b);
                }
        Vec3d eye = center;
        Comparator<Bounds> order = Comparator.comparingDouble((Bounds b) -> center(b).squaredDistanceTo(eye))
                .thenComparingLong(Bounds::minX).thenComparingLong(Bounds::minY).thenComparingLong(Bounds::minZ);
        tiles.sort(order);
        // No truncation: the full render-distance box is scheduled. The tile queue is cheap; the
        // bounded issue rate and node budget are what keep discovery progressive and bounded.
        return List.copyOf(tiles);
    }

    /** True when every cell of the tile lies outside the retained discovery extent. */
    static boolean beyond(Bounds tile, Vec3d center, int viewDistanceChunks) {
        return center(tile).squaredDistanceTo(center) > Math.pow(retentionRadius(viewDistanceChunks), 2);
    }

    /** Convert observed source cells into far-field octree nodes. Air never produces geometry, and
     * uniform unowned material merges so render-distance coverage stays a bounded node budget. */
    static List<SourceMeshBuilder.Node> nodes(List<SourceLandmarks.Cell> cells, Vec3d tileCenter) {
        return farNodes(observedSolids(cells), tileCenter);
    }

    /** Observed solids only: air is dropped (it never produces geometry) and duplicates collapse. */
    static Map<BlockPos,SourceMeshBuilder.Tile> observedSolids(List<SourceLandmarks.Cell> cells) {
        Map<BlockPos,SourceMeshBuilder.Tile> observed = new HashMap<>();
        for (var cell : cells) {
            if (cell.air()) continue;
            BlockPos at = new BlockPos(Math.toIntExact(cell.position().x()), Math.toIntExact(cell.position().y()), Math.toIntExact(cell.position().z()));
            if (observed.containsKey(at)) continue;
            observed.put(at, SourceMeshBuilder.stored(cell.material(), at));
        }
        return observed;
    }

    /** Far-field conversion of an observed tile: uniform unowned material merges into octree nodes. */
    static List<SourceMeshBuilder.Node> farNodes(Map<BlockPos,SourceMeshBuilder.Tile> observed, Vec3d tileCenter) {
        if (observed.isEmpty()) return List.of();
        return SourceMeshBuilder.compact(observed, tileCenter, Map.of(), true);
    }

    /** Local-window mesh cell admission: exact near samples keep the unchanged allowance, while a
     * bounded reserved budget admits distant discovered terrain even when near samples would
     * otherwise exhaust the whole allowance first. Pure and testable. */
    static final class LocalAdmission {
        static final int FAR_ADMISSION = 256;
        private final int nearLimit, farLimit;
        private final double nearSquared;
        int near, far;
        LocalAdmission(boolean shallow, int nearLimit) {
            this.nearLimit = nearLimit;
            this.farLimit = FAR_ADMISSION;
            this.nearSquared = NEAR_EXACT_RADIUS * NEAR_EXACT_RADIUS;
        }
        /** True when the cell (at the given viewer distance) may enter the frame. */
        boolean admit(double distanceSquared) {
            if (distanceSquared <= nearSquared) {
                if (near >= nearLimit) return false;
                near++;return true;
            }
            if (far >= farLimit) return false;
            far++;return true;
        }
        /** Both lanes spent: no remaining cell can be admitted, so scanning may stop. */
        boolean exhausted() {return near >= nearLimit && far >= farLimit;}
        int nearAdmitted() {return near;}
        int farAdmitted() {return far;}
    }

    /** Deterministic per-window discovery schedule: pending queue, coverage, retries. Server-thread only. */
    static final class Scheduler {
        private final ArrayDeque<Bounds> queue = new ArrayDeque<>();
        private final Set<Bounds> covered = new HashSet<>();
        private final Map<Bounds, Long> retryAt = new HashMap<>();
        private final Set<Bounds> inFlight = new HashSet<>();
        Vec3d center;
        int viewDistanceChunks;

        /** Re-plan around the current source position; retains prior coverage inside the extent and
         * re-queues everything else center-out. Returns tiles whose retention expired (caller drops data). */
        List<Bounds> replan(Vec3d source, int viewChunks) {
            center = source;
            viewDistanceChunks = viewChunks;
            List<Bounds> expired = new ArrayList<>();
            for (java.util.Iterator<Bounds> it = covered.iterator(); it.hasNext(); ) {
                Bounds tile = it.next();
                if (beyond(tile, center, viewChunks)) {expired.add(tile); it.remove();}
            }
            retryAt.keySet().removeIf(tile -> beyond(tile, center, viewChunks));
            inFlight.removeIf(tile -> beyond(tile, center, viewChunks));
            queue.clear();
            for (Bounds tile : plan(center, viewChunks)) if (!covered.contains(tile) && !inFlight.contains(tile)) queue.add(tile);
            return expired;
        }

        /** Next tile to read, or null. Never repeats a covered tile; respects retry cooldowns. */
        Bounds next(long tick) {
            for (var it = queue.iterator(); it.hasNext(); ) {
                Bounds tile = it.next();
                if (covered.contains(tile) || inFlight.contains(tile)) {it.remove(); continue;}
                Long at = retryAt.get(tile);
                if (at != null && tick < at) continue;
                return tile;
            }
            return null;
        }

        void pending(Bounds tile) {inFlight.add(tile);}

        /** Region result recorded. Complete regions permanently cover the tile; partial reads keep
         * their sampled data but retry after a cooldown. */
        void completed(Bounds tile, boolean complete, long tick) {
            inFlight.remove(tile);
            if (complete) {covered.add(tile); retryAt.remove(tile);}
            else retryAt.put(tile, tick + RETRY_INCOMPLETE_TICKS);
        }

        void failed(Bounds tile, long tick) {
            inFlight.remove(tile);
            retryAt.put(tile, tick + RETRY_ERROR_TICKS);
        }

        /** Explicit cancellation (window teardown / retained loss). */
        void cancel(Bounds tile) {inFlight.remove(tile);}

        boolean isEmpty() {return queue.isEmpty() && covered.isEmpty() && inFlight.isEmpty() && retryAt.isEmpty();}
    }
}
