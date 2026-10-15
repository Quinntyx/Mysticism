package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SourceLandmarks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Owner-thread completion path for real render-distance observations. Registry/block shape
 * access is incremental; only detached immutable geometry reaches the bounded worker. No callback
 * performs materialization, compaction, retention sorting or publication on the completing thread.
 * One worker result per window prevents competing retained snapshots from dropping newer tiles. */
final class DiscoveryMeshPipeline {
    static final int CELLS_PER_TICK=256, CELLS_PER_WINDOW=64;
    static final long NANOS_PER_TICK=1_000_000;
    /** Shared across all windows/players in a server tick, not a separate allowance per player. */
    static final class Budget {
        private final LongSupplier clock;
        private final long deadline;
        private int remaining,submissions=2;
        Budget(){this(CELLS_PER_TICK,NANOS_PER_TICK,System::nanoTime);}
        Budget(int cells,long nanos,LongSupplier clock){this.remaining=cells;this.clock=clock;deadline=clock.getAsLong()+nanos;}
        private boolean take(){if(remaining<=0||clock.getAsLong()-deadline>=0)return false;remaining--;return true;}
        int remaining(){return remaining;}
        private boolean submit(){if(submissions==0)return false;submissions--;return true;}
    }
    private final Thread owner=Thread.currentThread();
    private final String dimension;
    private final SourceDiscovery.Scheduler scheduler;
    private final Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests;
    private final Map<Bounds,List<SourceMeshBuilder.Node>> retained;
    private final BiFunction<SourceLandmarks.Cell,BlockPos,SourceMeshBuilder.Tile> materialize;
    private final Map<Bounds,Job> jobs=new LinkedHashMap<>();
    private Vec3d center=Vec3d.ZERO;
    private long revision;
    private boolean dirty,focusSet;
    private List<SourceMeshBuilder.Node> nodes=List.of();
    private Work work;
    private static final class Job {
        final Bounds tile;final CompletableFuture<SourceLandmarks.Region> request;final SourceLandmarks.Region region;
        Map<BlockPos,SourceMeshBuilder.Tile> observed=new HashMap<>();int cursor;
        List<SourceMeshBuilder.Node> compacted;
        Job(Bounds tile,CompletableFuture<SourceLandmarks.Region> request,SourceLandmarks.Region region){this.tile=tile;this.request=request;this.region=region;}
    }
    private record Prepared(List<SourceMeshBuilder.Node> compacted,SourceDiscovery.Retention retained) {}
    private record Work(Job job,long revision,CompletableFuture<Prepared> future) {}

    DiscoveryMeshPipeline(String dimension,SourceDiscovery.Scheduler scheduler,Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests,
                          Map<Bounds,List<SourceMeshBuilder.Node>> retained){
        this(dimension,scheduler,requests,retained,(cell,at)->SourceMeshBuilder.stored(cell.material(),at));
    }
    /** Registry adapter seam only; tests drive this same production pipeline without a game registry. */
    DiscoveryMeshPipeline(String dimension,SourceDiscovery.Scheduler scheduler,Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests,
                          Map<Bounds,List<SourceMeshBuilder.Node>> retained,
                          BiFunction<SourceLandmarks.Cell,BlockPos,SourceMeshBuilder.Tile> materialize){
        this.dimension=Objects.requireNonNull(dimension);this.scheduler=scheduler;this.requests=requests;this.retained=retained;this.materialize=materialize;
    }
    private void onOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("discovery mesh owner thread");}
    List<SourceMeshBuilder.Node> nodes(){return nodes;}
    boolean hasWork(){return dirty||work!=null||!jobs.isEmpty()||!requests.isEmpty();}
    void refresh(Vec3d center,boolean expired){
        onOwner();
        if(focusSet&&!expired&&this.center.equals(center))return;
        focusSet=true;this.center=center;revision++;dirty=true;
        if(expired)nodes=List.of(); // expired observations cannot remain visible until a slow worker returns
    }
    void cancel(Bounds tile){
        onOwner();Job job=jobs.remove(tile);
        if(work!=null&&work.job==job&&job!=null){work.future.cancel(false);work=null;}
        revision++;dirty=true;
    }
    void cancelAll(){
        onOwner();if(work!=null)work.future.cancel(false);work=null;jobs.clear();nodes=List.of();revision++;dirty=false;
    }
    /** Polling is nonblocking even if chunk IO/compaction is paused. Returns true only when the
     * published far-node reference changed. Request identities survive through mesh publication,
     * so MAX_INFLIGHT also bounds completed-but-not-materialized region snapshots. */
    boolean advance(Budget budget,Executor worker,long tick){
        onOwner();List<SourceMeshBuilder.Node> before=nodes;
        for(var it=jobs.values().iterator();it.hasNext();){
            Job job=it.next();if(requests.get(job.tile)==job.request)continue;
            if(work!=null&&work.job==job){work.future.cancel(false);work=null;}it.remove();
        }
        if(work!=null&&work.future.isDone())finish(tick);
        for(var entry:List.copyOf(requests.entrySet())){
            if(jobs.containsKey(entry.getKey())||!entry.getValue().isDone())continue;
            try{
                var region=entry.getValue().getNow(null);
                // A completed future is not authority by itself. Keep the producing window's
                // immutable namespace through the polled/worker path, before registry or mesh work.
                if(region==null || !dimension.equals(region.dimension()) || !entry.getKey().equals(region.bounds()))
                    throw new IllegalArgumentException("discovery observation namespace/range mismatch");
                jobs.put(entry.getKey(),new Job(entry.getKey(),entry.getValue(),region));
            }catch(RuntimeException failed){requests.remove(entry.getKey(),entry.getValue());scheduler.failed(entry.getKey(),tick);}
        }
        int used=0;
        for(Job job:jobs.values()){
            try{
                while(job.cursor<job.region.cells().size()&&used<CELLS_PER_WINDOW&&budget.take()){
                    var cell=job.region.cells().get(job.cursor++);used++;
                    if(cell.air())continue;
                    BlockPos at=new BlockPos(Math.toIntExact(cell.position().x()),Math.toIntExact(cell.position().y()),Math.toIntExact(cell.position().z()));
                    if(!job.observed.containsKey(at))job.observed.put(at,materialize.apply(cell,at));
                }
            }catch(RuntimeException failed){
                // Retain prior valid terrain; failed materialization is a real retry, not coverage.
                requests.remove(job.tile,job.request);scheduler.failed(job.tile,tick);
            }
        }
        // Remove failures before selecting work; no half-materialized snapshot can be published.
        jobs.values().removeIf(job->requests.get(job.tile)!=job.request);
        if(work==null){
            Job ready=null;
            for(Job job:jobs.values())if(job.cursor==job.region.cells().size()){ready=job;break;}
            if((ready!=null||dirty)&&budget.submit())submit(ready,worker);
        }
        return before!=nodes;
    }
    private void submit(Job job,Executor worker){
        // This copies only tile metadata/list references, never the retained node arrays. All
        // node merging/sorting/copies and candidate budget decisions are detached worker work.
        var snapshot=new LinkedHashMap<>(retained);Vec3d focus=center;
        Bounds tile=job==null?null:job.tile;
        Map<BlockPos,SourceMeshBuilder.Tile> observed=job==null?null:job.observed;
        List<SourceMeshBuilder.Node> cached=job==null?null:job.compacted;
        try{
            var future=CompletableFuture.supplyAsync(()->{
                List<SourceMeshBuilder.Node> compacted=cached;
                if(tile!=null){
                    if(compacted==null)compacted=SourceDiscovery.farNodes(observed,new Vec3d(tile.minX()+16,tile.minY()+16,tile.minZ()+16));
                    snapshot.put(tile,compacted);
                }
                return new Prepared(compacted,SourceDiscovery.retention(snapshot,focus,SourceDiscovery.MAX_NODES));
            },worker);
            work=new Work(job,revision,future);
        }catch(RejectedExecutionException busy){/* bounded worker full: keep ready cells and retry next tick */}
    }
    private void finish(long tick){
        Work completed=work;work=null;Job job=completed.job;
        try{
            Prepared result=completed.future.getNow(null);
            if(job!=null){
                if(requests.get(job.tile)!=job.request)return;
                job.compacted=result.compacted;job.observed=null; // registry work is never repeated after a replan
            }
            if(completed.revision!=revision)return; // retain cached tile compaction, retry retention at current extent
            if(job!=null){requests.remove(job.tile,job.request);jobs.remove(job.tile);scheduler.completed(job.tile,job.region.complete(),tick);}
            retained.clear();retained.putAll(result.retained.tiles());
            for(var tile:result.retained.evicted())scheduler.evicted(tile,tick);
            nodes=result.retained.nodes();dirty=false;
        }catch(RuntimeException failed){
            if(job!=null&&requests.remove(job.tile,job.request)){jobs.remove(job.tile);scheduler.failed(job.tile,tick);}
        }
    }
}
