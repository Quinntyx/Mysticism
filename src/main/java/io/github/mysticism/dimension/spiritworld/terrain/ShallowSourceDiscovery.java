package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Source-identical outward acquisition, independent of semantic representative selection.
 * One bounded catalog/page/leaf phase per turn. Generated-only patches fill the holes even
 * before embeddings/landmark extraction are ready. No ownership is inferred from IO bounds. */
final class ShallowSourceDiscovery {
    static final int MAX_NODES=4096, CELL_BUDGET=128, PATCH_SIDE=16;
    interface Read {
        void advance(); List<GeometryPage> drain(); boolean complete(); boolean current(); int nextPageIndex(); void cancel();
    }
    interface Source {
        LandmarkStore.SourceRangePage catalog(Bounds range,String cursor);
        Read read(String id,Bounds range,int pageIndex); // null while the shared repository cursor is busy
        CompletableFuture<SourceLandmarks.Region> region(Bounds range);
    }
    private final Source source;
    private final Function<SourceLandmarks.Cell,SourceMeshBuilder.Tile> resolve;
    private final Function<BlockPalette.State,SourceMeshBuilder.Tile> stored;
    private Vec3d focus;
    private Bounds range;
    private String cursor;
    private boolean catalogEnd;
    private final ArrayDeque<LandmarkMetadata> headers=new ArrayDeque<>();
    private Read read;
    private SparseOctree<BlockSample>.Cursor leaves;
    private GeometryPage page;
    private String owner;
    private CompletableFuture<SourceLandmarks.Region> future;
    private Iterator<SourceLandmarks.Cell> cells;
    private final Map<BlockPos,SourceMeshBuilder.Tile> patch=new HashMap<>();
    private int yIndex, ring, edge, pageIndex, turn, radius;
    private boolean generatedEnd, budgetPaused;
    private long nextSweep;
    private List<SourceMeshBuilder.Node> nodes=List.of();
    // Both AIR and SOLID observations mask later persisted snapshots. Live near samples are
    // overlaid by the service after this reservoir; these masks grant no support/exit authority.
    private final ShallowObservationCoverage observed=new ShallowObservationCoverage();

    ShallowSourceDiscovery(Source source,Function<SourceLandmarks.Cell,SourceMeshBuilder.Tile> resolve,
            Function<BlockPalette.State,SourceMeshBuilder.Tile> stored) {
        this.source=source;this.resolve=resolve;this.stored=stored;
    }
    void advance(Vec3d at,int bottom,int top,long tick) {
        advance(at,bottom,top,tick,DiscoveryBudget.RENDER_DISTANCE);
    }
    void advance(Vec3d at,int bottom,int top,long tick,int renderDistance) {
        if(focus==null || radius!=renderDistance || focus.squaredDistanceTo(at)>16*16) restart(at,bottom,top,renderDistance);
        if(leaves!=null) {
            var incoming=new ArrayList<SourceMeshBuilder.Node>();
            for(var cell:leaves.advance(CELL_BUDGET,32)) {
                if(cell.value().occupancy()!=BlockSample.Occupancy.SOLID)continue;
                Bounds b=cell.bounds();BlockPos pos=new BlockPos(Math.toIntExact(b.minX()),Math.toIntExact(b.minY()),Math.toIntExact(b.minZ()));
                var node=new SourceMeshBuilder.Node(pos,Math.toIntExact(b.maxX()-b.minX()),stored.apply(page.palette().state(cell.value().paletteIndex())),owner);
                if(distance(node)<=(double)radius*radius)incoming.add(node);
            }
            merge(SourceMeshBuilder.replaceObserved(incoming,List.of(),observed::contains,observed::bucketCount,focus));
            if(leaves.complete()){leaves=null;page=null;}
            return;
        }
        // Alternate persisted and generated work: model readiness or a very large catalog may
        // not starve the actual native source view. The service shares these turns across players.
        if((turn++&1)==0) { advanceGenerated(tick);return; }
        if(read!=null) {
            if(!read.current()){read.cancel();read=null;headers.removeFirst();pageIndex=0;return;}
            int before=read.nextPageIndex();read.advance();var pages=read.drain();
            pageIndex=read.nextPageIndex();
            if(!pages.isEmpty()){page=pages.getFirst();leaves=page.cells().cursor(range);}
            boolean complete=read.complete();
            if(complete || pageIndex>before){read.cancel();read=null;}
            if(complete){headers.removeFirst();pageIndex=0;}
            return;
        }
        if(!headers.isEmpty()) {
            LandmarkMetadata m=headers.peekFirst();read=source.read(m.id(),range,pageIndex);
            if(read!=null)owner=m.id();
            return;
        }
        if(!catalogEnd) {
            var batch=source.catalog(range,cursor);cursor=batch.nextId();catalogEnd=batch.end();headers.addAll(batch.landmarks());
        } else if(tick>=nextSweep) {cursor=null;catalogEnd=false;nextSweep=tick+200;
            if(generatedEnd && future==null && cells==null){yIndex=ring=edge=0;generatedEnd=false;}}
    }
    private void advanceGenerated(long tick) {
        if(cells!=null) {
            int budget=CELL_BUDGET;
            while(cells.hasNext() && budget-->0) {
                var cell=cells.next();BlockPoint p=cell.position();BlockPos at=new BlockPos(Math.toIntExact(p.x()),Math.toIntExact(p.y()),Math.toIntExact(p.z()));
                patch.put(at,resolve.apply(cell));
            }
            if(cells.hasNext())return;
            // Full patches cost one compact bit, independent of the fragmented-mask cap. A
            // fragmented patch that cannot fit is deferred atomically, not a permanent stop of
            // the outward cursor: subsequent full regions and periodic sweeps must still run.
            if(!observed.record(patch.keySet())){budgetPaused=true;patch.clear();cells=null;return;}
            var replacement=SourceMeshBuilder.replaceNear(nodes,SourceMeshBuilder.compact(patch,focus),patch.keySet(),focus);
            nodes=List.of();merge(replacement);patch.clear();cells=null;return;
        }
        if(future!=null) {
            if(!future.isDone())return;
            var completed=future;future=null;
            if(completed.isCompletedExceptionally() || completed.isCancelled())return;
            var region=completed.getNow(null);if(region!=null)cells=region.cells().iterator();
            return;
        }
        if(generatedEnd)return;
        Bounds query=nextPatch();if(query!=null)future=source.region(query);
    }
    private void restart(Vec3d at,int bottom,int top,int renderDistance) {
        // Walking moves the query center, not the outward cursor back to its first patch.
        // A teleport/radius change starts near-first; ordinary recentering preserves progress.
        boolean continuing=focus!=null && radius==renderDistance && !generatedEnd
                && focus.squaredDistanceTo(at)<=(double)radius*radius/4;
        int oldY=yIndex,oldRing=ring,oldEdge=edge;
        cancel();focus=at;radius=renderDistance;int r=radius;
        range=new Bounds(MathHelper.floor(at.x)-r,Math.max(bottom,MathHelper.floor(at.y)-r),MathHelper.floor(at.z)-r,
                MathHelper.floor(at.x)+r+1L,Math.min(top,MathHelper.floor(at.y)+r+1L),MathHelper.floor(at.z)+r+1L);
        observed.retain(range);budgetPaused=false;
        yIndex=continuing?oldY:0;ring=continuing?oldRing:0;edge=continuing?oldEdge:0;pageIndex=0;
        generatedEnd=false;cursor=null;catalogEnd=false;nextSweep=0;
        nodes=nodes.stream().filter(n->distance(n)<=(double)r*r).toList();
    }
    /** Constant-memory outward 3D cursor, with a bounded skip budget. Increasing render distance
     * must not allocate a radius-cubed request queue. Current-height rings precede vertical rings. */
    private Bounds nextPatch() {
        int maxRing=(radius+PATCH_SIDE-1)/PATCH_SIDE+1;
        long x0=Math.floorDiv(MathHelper.floor(focus.x),PATCH_SIDE)*PATCH_SIDE;
        // At an integer supported feet height, the floor belongs to the patch just below it.
        long y0=Math.floorDiv(MathHelper.floor(focus.y-.001),PATCH_SIDE)*PATCH_SIDE;
        long z0=Math.floorDiv(MathHelper.floor(focus.z),PATCH_SIDE)*PATCH_SIDE;
        for(int skipped=0;skipped<128 && !generatedEnd;skipped++) {
            int dy=yIndex==0?0:(yIndex+1)/2*(yIndex%2==0?1:-1);
            int dx=0,dz=0;
            if(ring>0) {
                int side=2*ring,k=edge%side;
                switch(edge/side) {
                    case 0 -> {dx=-ring+k;dz=-ring;}
                    case 1 -> {dx=ring;dz=-ring+k;}
                    case 2 -> {dx=ring-k;dz=ring;}
                    default -> {dx=-ring;dz=ring-k;}
                }
            }
            if(++edge>=(ring==0?1:8*ring)){edge=0;if(++ring>maxRing){ring=0;if(++yIndex>2*maxRing)generatedEnd=true;}}
            long x=x0+(long)dx*PATCH_SIDE,y=y0+(long)dy*PATCH_SIDE,z=z0+(long)dz*PATCH_SIDE;
            if(y>=range.maxY() || y+PATCH_SIDE<=range.minY())continue;
            Bounds b=new Bounds(x,Math.max(range.minY(),y),z,x+PATCH_SIDE,Math.min(range.maxY(),y+PATCH_SIDE),z+PATCH_SIDE);
            if(SourceMeshBuilder.distanceSquared(box(b),focus)<=(double)radius*radius)return b;
        }
        return null;
    }
    boolean budgetPaused(){return budgetPaused;}
    /** IO failure releases pending work but retains the coherent published reservoir/masks. */
    void retry(){cancel();focus=null;}
    private static Box box(Bounds b){return new Box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
    private double distance(SourceMeshBuilder.Node n) {BlockPos p=n.position();return SourceMeshBuilder.distanceSquared(new Box(p.getX(),p.getY(),p.getZ(),p.getX()+n.side(),p.getY()+n.side(),p.getZ()+n.side()),focus);}
    private void merge(List<SourceMeshBuilder.Node> incoming) {
        Map<SourceMeshBuilder.Key,SourceMeshBuilder.Node> merged=new HashMap<>();
        for(var list:List.of(nodes,incoming))for(var n:list)merged.put(new SourceMeshBuilder.Key(n.position().getX(),n.position().getY(),n.position().getZ(),n.side()),n);
        nodes=MeshPublication.coverageNodes(merged.values(),MAX_NODES,focus,this::distance);
    }
    List<SourceMeshBuilder.Node> snapshot(){return nodes;}
    void cancel(){if(read!=null)read.cancel();if(future!=null)future.cancel(false);read=null;future=null;leaves=null;page=null;cells=null;headers.clear();patch.clear();}
}
