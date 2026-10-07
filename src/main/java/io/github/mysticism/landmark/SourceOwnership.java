package io.github.mysticism.landmark;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.*;

/** Exact, resumable source-mask lookup. AABBs only admit IO; only known octree leaves
 * assign ownership. Both AIR and SOLID belong to their source owner; UNKNOWN does not.
 * No semantic gate, source-world access, chunk identity, generation or model work. */
public final class SourceOwnership {
    private SourceOwnership(){}
    /** Immutable completed snapshot. Missing entries are unowned/unknown, NOT retained-owner
     * permission. Use isCurrent on the server thread before mesh/exit/support publication. */
    public static final class Region {
        private final LandmarkStore store;
        private final String dimension;
        private final Bounds bounds;
        private final long revision;
        private final Map<BlockPos,String> owners;
        private Region(Request request){
            store=request.store;dimension=request.dimension;bounds=request.bounds;revision=request.revision;
            // Request is terminal before this is exposed and never edits the map again.
            owners=Collections.unmodifiableMap(request.owners);
        }
        public String dimension(){return dimension;}
        public Bounds bounds(){return bounds;}
        public long ownershipRevision(){return revision;}
        public Map<BlockPos,String> owners(){return owners;}
        public Optional<String> ownerAt(BlockPos position){return Optional.ofNullable(owners.get(position));}
        public boolean isCurrent(MinecraftServer server){
            if(!server.isOnThread())throw new IllegalStateException("source ownership server thread");
            return LandmarkStore.get(server)==store && store.ownershipRevision()==revision;
        }
    }
    /** Driven only by SourceLandmarks' bounded lifecycle-owned queue, one phase per tick. */
    static final class Request {
        final String dimension;final Bounds bounds;final CompletableFuture<Region> future;
        final LandmarkStore store;final Map<BlockPos,String> owners=new HashMap<>();
        final ArrayDeque<LandmarkMetadata> candidates=new ArrayDeque<>();
        long revision;boolean started,end,done;String cursor,owner;
        LandmarkStore.GeometryRead read;
        GeometryPage page;SparseOctree<BlockSample>.Cursor cells;
        final ArrayDeque<Bounds> leaves=new ArrayDeque<>();Bounds leaf;long x,y,z;
        Request(LandmarkStore store,String dimension,Bounds bounds,CompletableFuture<Region> future){this.store=store;this.dimension=dimension;this.bounds=bounds;this.future=future;}
        void advance(){
            if(done)return;
            if(future.isCancelled()){cancel(new CancellationException("source ownership cancelled"));return;}
            if(!started){revision=store.ownershipRevision();started=true;}
            if(store.ownershipRevision()!=revision)throw new CancellationException("source ownership changed; request again");
            if(leaf!=null||!leaves.isEmpty()){
                // A coarse leaf may be a large uniform cube: expansion is resumable too.
                for(int n=0;n<256;n++){
                    if(leaf==null){if(leaves.isEmpty())break;leaf=leaves.removeFirst();x=leaf.minX();y=leaf.minY();z=leaf.minZ();}
                    var position=new BlockPos(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z));
                    String prior=owners.putIfAbsent(position,owner);
                    if(prior!=null&&!prior.equals(owner))throw new IllegalStateException("conflicting source masks at "+position);
                    if(++x==leaf.maxX()){x=leaf.minX();if(++y==leaf.maxY()){y=leaf.minY();if(++z==leaf.maxZ())leaf=null;}}
                }
                return;
            }
            if(cells!=null){
                for(var cell:cells.advance(256,128)){
                    Bounds b=cell.bounds();
                    leaves.addLast(new Bounds(Math.max(b.minX(),bounds.minX()),Math.max(b.minY(),bounds.minY()),Math.max(b.minZ(),bounds.minZ()),Math.min(b.maxX(),bounds.maxX()),Math.min(b.maxY(),bounds.maxY()),Math.min(b.maxZ(),bounds.maxZ())));
                }
                if(cells.complete()){cells=null;page=null;}
                return;
            }
            if(read!=null){
                read.advance(1,512);var batch=read.drain();
                if(!batch.isEmpty()){page=batch.getFirst();cells=page.cells().cursor(bounds);}
                if(read.complete())read=null;
                return;
            }
            if(!candidates.isEmpty()){
                if(!store.geometryReadAvailable())return;
                var candidate=candidates.removeFirst();owner=candidate.id();read=store.beginGeometryRead(owner,bounds);return;
            }
            if(!end){var batch=store.sourceRangePage(dimension,bounds,cursor,4,4);cursor=batch.nextId();end=batch.end();candidates.addAll(batch.landmarks());return;}
            done=true;future.complete(new Region(this));
        }
        void cancel(Throwable failure){
            if(done)return;done=true;if(read!=null)read.cancel();read=null;cells=null;page=null;leaf=null;leaves.clear();candidates.clear();owners.clear();future.completeExceptionally(failure);
        }
    }
}
