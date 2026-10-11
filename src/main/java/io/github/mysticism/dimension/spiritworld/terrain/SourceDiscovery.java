package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SourceLandmarks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
public final class SourceDiscovery {
    /** One bounded SourceLandmarks region volume per tile (checkBounds cap is 32768 cells). */
    static final int TILE = 32;
    static final int MIN_RADIUS = TILE;
    /** Bounded worst case (a 16-chunk view distance). Larger client view distances still discover
     * progressively outward to this extent; nothing depends on the far tail being complete. */
    static final int MAX_RADIUS = 32 * 16; // Full vanilla client slider, including 32-chunk views.
    static final int MAX_VERTICAL_RADIUS = 64;
    /** Mesh cull tracks the visible horizon instead of a fixed 128-block locality. */
    static final int MIN_HORIZON = 128, MAX_HORIZON = MAX_RADIUS + 16;
    /** Concurrent region reads per window; SourceLandmarks serializes reads behind one live operation. */
    static final int MAX_INFLIGHT = 2;
    /** Maximum discovered far-field mesh nodes retained per window (octree-merged, then exact prism-batched). */
    static final int MAX_NODES = 65536;
    /** Cells within this radius of the viewer keep the full unchanged near allowance; beyond it,
     * far-field discovery admission shares a reserved bounded budget (see {@link LocalAdmission}). */
    static final double NEAR_EXACT_RADIUS = 32;
    static final int RETRY_INCOMPLETE_TICKS = 200, RETRY_ERROR_TICKS = 600;

    private SourceDiscovery() {}

    static int effectiveViewDistance(int serverChunks,int playerChunks) {
        return Math.max(1,Math.min(serverChunks,playerChunks));
    }
    static int horizontalRadius(int viewDistanceChunks) {
        int raw = Math.max(1, viewDistanceChunks) * 16;
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, raw));
    }

    static int verticalRadius(int horizontal) {
        return Math.min(horizontal, MAX_VERTICAL_RADIUS);
    }

    public static int meshHorizon(int viewDistanceChunks) {
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
        int horizontal=retentionRadius(viewDistanceChunks),vertical=verticalRadius(horizontalRadius(viewDistanceChunks))+TILE;
        return tile.minX()>center.x+horizontal || tile.maxX()<center.x-horizontal
                || tile.minZ()>center.z+horizontal || tile.maxZ()<center.z-horizontal
                || tile.minY()>center.y+vertical || tile.maxY()<center.y-vertical;
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
        var nodes=new ArrayList<>(coalesce(SourceMeshBuilder.compact(observed, tileCenter, Map.of(), true)));
        nodes.sort(Comparator.comparingDouble((SourceMeshBuilder.Node n)->SourceMeshBuilder.distanceSquared(SourceMeshBuilder.nodeBox(n),tileCenter))
                .thenComparingLong(n->n.position().asLong()));
        return List.copyOf(nodes);
    }

    /** Exact mesh-only batching of adjacent equal opaque cubes. Thin layers cannot octree-merge,
     * but their rectangular unions have precisely the same solid volume, source extent and owner.
     * Never combine partial shapes, differing material/light/tint/owners, or bridge unknown/air cells. */
    static List<SourceMeshBuilder.Node> coalesce(List<SourceMeshBuilder.Node> nodes) {
        List<SourceMeshBuilder.Node> result=nodes;
        for(int axis=0;axis<3;axis++) {
            final int dimension=axis;
            record Row(SourceMeshBuilder.Tile tile,String owner,int a,int b,int width,int height) {}
            Map<Row,List<SourceMeshBuilder.Node>> rows=new LinkedHashMap<>();
            List<SourceMeshBuilder.Node> next=new ArrayList<>();
            for(var n:result) {
                if(!n.tile().cube() || !n.tile().collision().equals(List.of(SourceMeshBuilder.UNIT))){next.add(n);continue;}
                int[] p={n.position().getX(),n.position().getY(),n.position().getZ()};
                int[] size={(int)n.size().x,(int)n.size().y,(int)n.size().z};
                int a=(axis+1)%3,b=(axis+2)%3;
                rows.computeIfAbsent(new Row(n.tile(),n.ownerId(),p[a],p[b],size[a],size[b]),ignored->new ArrayList<>()).add(n);
            }
            for(var row:rows.values()) {
                row.sort(Comparator.comparingDouble(n->coordinate(n.position(),dimension)));
                SourceMeshBuilder.Node merged=null;
                for(var n:row) {
                    if(merged!=null && coordinate(merged.position(),axis)+coordinate(merged.size(),axis)==coordinate(n.position(),axis)
                            && coordinate(merged.size(),axis)+coordinate(n.size(),axis)<=TILE) {
                        Vec3d size=merged.size().add(axis==0?n.size().x:0,axis==1?n.size().y:0,axis==2?n.size().z:0);
                        merged=new SourceMeshBuilder.Node(merged.position(),size,merged.tile(),merged.ownerId());
                    } else {if(merged!=null)next.add(merged);merged=n;}
                }
                if(merged!=null)next.add(merged);
            }
            result=next;
        }
        result.sort(Comparator.comparingLong((SourceMeshBuilder.Node n)->n.position().asLong())
                .thenComparingDouble(n->n.size().x).thenComparingDouble(n->n.size().y).thenComparingDouble(n->n.size().z));
        return List.copyOf(result);
    }
    private static double coordinate(BlockPos p,int axis){return axis==0?p.getX():axis==1?p.getY():p.getZ();}
    private static double coordinate(Vec3d p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}

    /** A candidate projects source extent and collision through exactly the same affine columns. */
    record Candidate(SourceMeshBuilder.Node node,Vec3d source,Vec3d min,Vec3d x,Vec3d y,Vec3d z,double distance) {
        TerrainMeshFrame.Cell cell(int material,long key,float alpha) {
            var tile=node.tile();
            return new TerrainMeshFrame.Cell(key,material,node.ownerId(),source,min,node.size(),x,y,z,tile.color(),tile.light(),alpha,tile.collision());
        }
    }
    static Candidate project(SourceMeshBuilder.Node node,Vec3d sourceOrigin,Vec3d root,Vec3d ax,Vec3d ay,Vec3d az,Vec3d viewer) {
        var at=node.position();Vec3d source=new Vec3d(at.getX(),at.getY(),at.getZ()),offset=source.subtract(sourceOrigin);
        Vec3d min=root.add(ax.multiply(offset.x)).add(ay.multiply(offset.y)).add(az.multiply(offset.z));
        Vec3d ex=ax.multiply(node.size().x),ey=ay.multiply(node.size().y),ez=az.multiply(node.size().z);
        Box bounds=new Box(min.x+Math.min(0,ex.x)+Math.min(0,ey.x)+Math.min(0,ez.x),
                min.y+Math.min(0,ex.y)+Math.min(0,ey.y)+Math.min(0,ez.y),min.z+Math.min(0,ex.z)+Math.min(0,ey.z)+Math.min(0,ez.z),
                min.x+Math.max(0,ex.x)+Math.max(0,ey.x)+Math.max(0,ez.x),
                min.y+Math.max(0,ex.y)+Math.max(0,ey.y)+Math.max(0,ez.y),min.z+Math.max(0,ex.z)+Math.max(0,ey.z)+Math.max(0,ez.z));
        return new Candidate(node,source,min,ex,ey,ez,SourceMeshBuilder.distanceSquared(bounds,viewer));
    }

    /** Reorder the FAR lane spatially, not by nearest distance. One candidate from every occupied
     * projected bucket precedes any bucket's second candidate, so dense nearby layers cannot spend
     * the whole budget before the horizon. Within buckets keep nearest surfaces first. Pure geometry:
     * palettes/keys and final budget accounting still happen only for actually emitted cells. */
    static List<Candidate> localOrder(List<Candidate> candidates,Vec3d viewer,int horizon) {
        record Bucket(int x,int y,int z) {}
        int stride=Math.max(TILE,Math.ceilDiv(horizon,3));
        Map<Bucket,ArrayDeque<Candidate>> far=new TreeMap<>(Comparator
                .comparingLong((Bucket b)->SourceMeshBuilder.key("discovery-bucket",new Vec3d(b.x,b.y,b.z),1))
                .thenComparingInt(Bucket::x).thenComparingInt(Bucket::y).thenComparingInt(Bucket::z));
        var sorted=new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::distance).thenComparingLong(c->c.node().position().asLong())
                .thenComparingDouble(c->c.node().size().x).thenComparingDouble(c->c.node().size().y).thenComparingDouble(c->c.node().size().z));
        List<Candidate> result=new ArrayList<>();
        for(var c:sorted) {
            if(c.distance()<=NEAR_EXACT_RADIUS*NEAR_EXACT_RADIUS){result.add(c);continue;}
            Vec3d midpoint=c.min().add(c.x().add(c.y()).add(c.z()).multiply(.5)).subtract(viewer);
            var bucket=new Bucket(MathHelper.floor(midpoint.x/stride),MathHelper.floor(midpoint.y/stride),MathHelper.floor(midpoint.z/stride));
            far.computeIfAbsent(bucket,ignored->new ArrayDeque<>()).add(c);
        }
        while(!far.isEmpty())for(var it=far.values().iterator();it.hasNext();) {
            var bucket=it.next();result.add(bucket.removeFirst());if(bucket.isEmpty())it.remove();
        }
        return result;
    }

    /** Local near samples retain their original exact allowance, with a separate bounded far lane. */
    static final class LocalAdmission {
        static final int FAR_ADMISSION=256;
        private final int nearLimit;
        private int near,far;
        LocalAdmission(int nearLimit){this.nearLimit=nearLimit;}
        boolean admit(double distanceSquared) {
            if(distanceSquared<=NEAR_EXACT_RADIUS*NEAR_EXACT_RADIUS) {
                if(near>=nearLimit)return false;near++;return true;
            }
            if(far>=FAR_ADMISSION)return false;far++;return true;
        }
        boolean exhausted(){return near>=nearLimit && far>=FAR_ADMISSION;}
        int nearAdmitted(){return near;}
        int farAdmitted(){return far;}
    }

    /** Eviction and coverage are one lifecycle operation. Bounded node retention prefers nearby tiles;
     * evicted data must never remain marked covered. A cooldown prevents stationary budget thrash. */
    static List<SourceMeshBuilder.Node> retain(Map<Bounds,List<SourceMeshBuilder.Node>> discovered,Scheduler scheduler,
                                              Vec3d center,int budget,long tick) {
        var tiles=new ArrayList<>(discovered.entrySet());
        tiles.sort(Comparator.comparingDouble((Map.Entry<Bounds,List<SourceMeshBuilder.Node>> e)->e.getKey().distanceSquared(new io.github.mysticism.landmark.Point3(center.x,center.y,center.z)))
                .thenComparingLong(e->e.getKey().minX()).thenComparingLong(e->e.getKey().minY()).thenComparingLong(e->e.getKey().minZ()));
        List<SourceMeshBuilder.Node> merged=new ArrayList<>();int total=0;
        for(var e:tiles) {
            if(total+e.getValue().size()>budget){discovered.remove(e.getKey());scheduler.evicted(e.getKey(),tick);continue;}
            total+=e.getValue().size();merged.addAll(e.getValue());
        }
        merged.sort(Comparator.comparingDouble((SourceMeshBuilder.Node n)->SourceMeshBuilder.distanceSquared(SourceMeshBuilder.nodeBox(n),center))
                .thenComparingLong(n->n.position().asLong()));
        return List.copyOf(merged);
    }

    /** Deterministic per-window discovery schedule: pending queue, coverage, retries. Server-thread only. */
    static final class Scheduler {
        private final ArrayDeque<Bounds> queue = new ArrayDeque<>();
        private final Set<Bounds> covered = new HashSet<>();
        private final Map<Bounds, Long> retryAt = new HashMap<>();
        private final Set<Bounds> inFlight = new HashSet<>();
        private final Set<Bounds> budgetEvicted = new HashSet<>();
        private Vec3d budgetEvictionCenter;
        Vec3d center;
        int viewDistanceChunks;

        /** Re-plan around the current source position; retains prior coverage inside the extent and
         * re-queues everything else center-out. Returns tiles whose retention expired (caller drops data). */
        List<Bounds> replan(Vec3d source, int viewChunks) {
            // Movement promotes previously evicted tiles without waiting for stationary backoff.
            if(budgetEvictionCenter!=null && (budgetEvictionCenter.squaredDistanceTo(source)>256 || viewChunks!=viewDistanceChunks)) {
                for(var tile:budgetEvicted)retryAt.remove(tile);
                budgetEvicted.clear();budgetEvictionCenter=null;
            }
            center = source;
            viewDistanceChunks = viewChunks;
            List<Bounds> expired = new ArrayList<>();
            for (java.util.Iterator<Bounds> it = covered.iterator(); it.hasNext(); ) {
                Bounds tile = it.next();
                if (beyond(tile, center, viewChunks)) {expired.add(tile); it.remove();}
            }
            retryAt.keySet().removeIf(tile -> beyond(tile, center, viewChunks));
            budgetEvicted.removeIf(tile -> beyond(tile, center, viewChunks));
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
            if (complete) {covered.add(tile); retryAt.remove(tile);budgetEvicted.remove(tile);}
            else retryAt.put(tile, tick + RETRY_INCOMPLETE_TICKS);
        }

        void failed(Bounds tile, long tick) {
            inFlight.remove(tile);
            retryAt.put(tile, tick + RETRY_ERROR_TICKS);
        }

        void evicted(Bounds tile,long tick) {
            if(budgetEvicted.isEmpty())budgetEvictionCenter=center;
            covered.remove(tile);budgetEvicted.add(tile);retryAt.put(tile,tick+RETRY_ERROR_TICKS);
            if(!inFlight.contains(tile) && !queue.contains(tile))queue.addLast(tile);
        }

        /** Explicit cancellation (window teardown / retained loss). */
        void cancel(Bounds tile) {inFlight.remove(tile);}

        boolean isEmpty() {return queue.isEmpty() && covered.isEmpty() && inFlight.isEmpty() && retryAt.isEmpty();}
    }
}
