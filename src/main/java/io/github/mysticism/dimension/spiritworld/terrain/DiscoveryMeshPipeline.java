package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.BlockPalette;
import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SourceLandmarks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Bounded owner-thread shape evaluation between detached observation indexing and mesh building.
 * Air/duplicate scans and repeated static material expansion never consume server ticks. Registry
 * and dynamic, position-dependent collision callbacks stay on the owner thread under one shared
 * deadline. No callback, saturation or poll path performs a synchronous mesh rebuild. */
final class DiscoveryMeshPipeline {
    /** These allowances count shape evaluations, NOT source voxels (including air). */
    static final int CELLS_PER_TICK=256, CELLS_PER_WINDOW=64;
    static final long NANOS_PER_TICK=1_000_000;
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
    /** Reuse only Minecraft's non-dynamic shape contract, never infer it from equal sample shapes. */
    record Resolved(SourceMeshBuilder.Tile tile,boolean reusable) {}
    private record Group(BlockPalette.State material,List<SourceLandmarks.Cell> cells) {}
    private final Thread owner=Thread.currentThread();
    private final String dimension;
    private final SourceDiscovery.Scheduler scheduler;
    private final Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests;
    private final Map<Bounds,List<SourceMeshBuilder.Node>> retained;
    private final BiFunction<SourceLandmarks.Cell,BlockPos,Resolved> materialize;
    private final Map<Bounds,Job> jobs=new LinkedHashMap<>();
    private Vec3d center=Vec3d.ZERO;
    private long revision;
    private boolean dirty,focusSet;
    private List<SourceMeshBuilder.Node> nodes=List.of();
    private Work work;
    private static final class Job {
        final Bounds tile;final CompletableFuture<SourceLandmarks.Region> request;final SourceLandmarks.Region region;
        CompletableFuture<List<Group>> indexing;List<Group> groups;int cursor,position;
        final Map<BlockPalette.State,SourceMeshBuilder.Tile> palette=new HashMap<>();
        final Map<BlockPos,SourceMeshBuilder.Tile> dynamic=new HashMap<>();
        List<SourceMeshBuilder.Node> compacted;
        Job(Bounds tile,CompletableFuture<SourceLandmarks.Region> request,SourceLandmarks.Region region){this.tile=tile;this.request=request;this.region=region;}
        void cancel(){if(indexing!=null)indexing.cancel(false);}
        boolean ready(){return groups!=null&&cursor==groups.size();}
    }
    private record Prepared(List<SourceMeshBuilder.Node> compacted,SourceDiscovery.Retention retained) {}
    private record Work(Job job,long revision,CompletableFuture<Prepared> future) {}

    DiscoveryMeshPipeline(String dimension,SourceDiscovery.Scheduler scheduler,Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests,
                          Map<Bounds,List<SourceMeshBuilder.Node>> retained){
        this(dimension,scheduler,requests,retained,(cell,at)->SourceMeshBuilder.storedDiscovery(cell.material(),at));
    }
    /** Only registry/shape resolution is substituted in bare-JVM tests; all stages remain real. */
    DiscoveryMeshPipeline(String dimension,SourceDiscovery.Scheduler scheduler,Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests,
                          Map<Bounds,List<SourceMeshBuilder.Node>> retained,
                          BiFunction<SourceLandmarks.Cell,BlockPos,Resolved> materialize){
        this.dimension=Objects.requireNonNull(dimension);this.scheduler=scheduler;this.requests=requests;this.retained=retained;this.materialize=materialize;
    }
    private void onOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("discovery mesh owner thread");}
    List<SourceMeshBuilder.Node> nodes(){return nodes;}
    boolean hasWork(){return dirty||work!=null||!jobs.isEmpty()||!requests.isEmpty();}
    void refresh(Vec3d center,boolean expired){
        onOwner();
        if(focusSet&&!expired&&this.center.equals(center))return;
        focusSet=true;this.center=center;revision++;dirty=true;
        if(expired)nodes=List.of();
    }
    void cancel(Bounds tile){
        onOwner();Job job=jobs.remove(tile);if(job!=null)job.cancel();
        if(work!=null&&work.job==job&&job!=null){work.future.cancel(false);work=null;}
        revision++;dirty=true;
    }
    void cancelAll(){
        onOwner();if(work!=null)work.future.cancel(false);work=null;
        jobs.values().forEach(Job::cancel);jobs.clear();nodes=List.of();revision++;dirty=false;
    }
    /** Request slots cover indexing, shape evaluation and detached mesh publication; at most two
     * region snapshots per window exist. Worker rejection retains progress without inline fallback. */
    boolean advance(Budget budget,Executor worker,long tick){
        onOwner();List<SourceMeshBuilder.Node> before=nodes;
        for(var it=jobs.values().iterator();it.hasNext();){
            Job job=it.next();if(requests.get(job.tile)==job.request)continue;
            job.cancel();if(work!=null&&work.job==job){work.future.cancel(false);work=null;}it.remove();
        }
        if(work!=null&&work.future.isDone())finish(tick);
        for(var entry:List.copyOf(requests.entrySet())){
            if(jobs.containsKey(entry.getKey())||!entry.getValue().isDone())continue;
            try{
                var region=entry.getValue().getNow(null);
                if(region==null || !dimension.equals(region.dimension()) || !entry.getKey().equals(region.bounds())
                        || region.cells().size()>32768)
                    throw new IllegalArgumentException("discovery observation namespace/range/volume mismatch");
                jobs.put(entry.getKey(),new Job(entry.getKey(),entry.getValue(),region));
            }catch(RuntimeException failed){requests.remove(entry.getKey(),entry.getValue());scheduler.failed(entry.getKey(),tick);}
        }
        int used=0;
        for(Job job:jobs.values()){
            try{
                if(job.groups==null){
                    if(job.indexing==null&&budget.submit()){
                        try{job.indexing=CompletableFuture.supplyAsync(()->index(job.region),worker);}
                        catch(RejectedExecutionException busy){/* retry without scanning on this thread */}
                    }
                    if(job.indexing==null||!job.indexing.isDone())continue;
                    job.groups=job.indexing.getNow(null);
                }
                while(!job.ready()&&used<CELLS_PER_WINDOW&&budget.take()){
                    Group group=job.groups.get(job.cursor);var cell=group.cells.get(job.position);
                    BlockPos at=position(cell);Resolved sample=materialize.apply(cell,at);used++;
                    if(job.position==0&&sample.reusable){
                        job.palette.put(group.material,sample.tile);job.cursor++;
                    }else{
                        job.dynamic.put(at,sample.tile);job.position++;
                        if(job.position==group.cells.size()){job.cursor++;job.position=0;}
                    }
                }
            }catch(RuntimeException failed){
                job.cancel();requests.remove(job.tile,job.request);scheduler.failed(job.tile,tick);
            }
        }
        jobs.values().removeIf(job->requests.get(job.tile)!=job.request);
        if(work==null){
            Job ready=null;for(Job job:jobs.values())if(job.ready()){ready=job;break;}
            if((ready!=null||(dirty&&jobs.isEmpty()))&&budget.submit())submit(ready,worker);
        }
        return before!=nodes;
    }
    private static BlockPos position(SourceLandmarks.Cell cell){
        return new BlockPos(Math.toIntExact(cell.position().x()),Math.toIntExact(cell.position().y()),Math.toIntExact(cell.position().z()));
    }
    /** Detached, bounded by the real region volume. Unknown cells stay absent; air has no mesh.
     * Duplicated observations cannot manufacture overlapping collision or repeat shape callbacks. */
    private static List<Group> index(SourceLandmarks.Region region){
        var grouped=new LinkedHashMap<BlockPalette.State,List<SourceLandmarks.Cell>>();var seen=new HashSet<BlockPos>();
        for(var cell:region.cells()){
            var p=cell.position();if(!region.bounds().contains(p.x(),p.y(),p.z()))throw new IllegalArgumentException("source cell outside observation");
            if(cell.air()||!seen.add(position(cell)))continue;
            grouped.computeIfAbsent(cell.material(),ignored->new ArrayList<>()).add(cell);
        }
        var result=new ArrayList<Group>();grouped.forEach((material,cells)->result.add(new Group(material,List.copyOf(cells))));return List.copyOf(result);
    }
    private static Map<BlockPos,SourceMeshBuilder.Tile> expand(Job job){
        var observed=new HashMap<>(job.dynamic);
        for(var group:job.groups){var tile=job.palette.get(group.material);if(tile!=null)for(var cell:group.cells)observed.put(position(cell),tile);}
        return observed;
    }
    private void submit(Job job,Executor worker){
        var snapshot=new LinkedHashMap<>(retained);Vec3d focus=center;
        try{
            var future=CompletableFuture.supplyAsync(()->{
                List<SourceMeshBuilder.Node> compacted=job==null?null:job.compacted;
                if(job!=null){
                    if(compacted==null)compacted=SourceDiscovery.farNodes(expand(job),new Vec3d(job.tile.minX()+16,job.tile.minY()+16,job.tile.minZ()+16));
                    snapshot.put(job.tile,compacted);
                }
                return new Prepared(compacted,SourceDiscovery.retention(snapshot,focus,SourceDiscovery.MAX_NODES));
            },worker);
            work=new Work(job,revision,future);
        }catch(RejectedExecutionException busy){/* no waiting, no synchronous fallback */}
    }
    private void finish(long tick){
        Work completed=work;work=null;Job job=completed.job;
        try{
            Prepared result=completed.future.getNow(null);
            if(job!=null){if(requests.get(job.tile)!=job.request)return;job.compacted=result.compacted;}
            if(completed.revision!=revision)return;
            if(job!=null){requests.remove(job.tile,job.request);jobs.remove(job.tile);scheduler.completed(job.tile,job.region.complete(),tick);}
            retained.clear();retained.putAll(result.retained.tiles());
            for(var tile:result.retained.evicted())scheduler.evicted(tile,tick);
            nodes=result.retained.nodes();dirty=false;
        }catch(RuntimeException failed){
            if(job!=null&&requests.remove(job.tile,job.request)){jobs.remove(job.tile);scheduler.failed(job.tile,tick);}
        }
    }
}
