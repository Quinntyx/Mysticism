package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Drives the actual Context.advanceDiscovery / Window completion path with full source tiles,
 * delayed real detached compaction/retention, and server-thread registry adapter instrumentation.
 * Only registry resolution is substituted (bare JVM has no game registry). No source generation,
 * embeddings, Minecraft launch or wall-clock performance claim is part of these checks. */
public final class DiscoveryMeshCompletionSelfTest {
    private static int checks;
    private static final String DIM="minecraft:overworld";
    private static final Class<?> WINDOW=type("Window"),SESSION=type("Session"),CONTEXT=type("Context");
    private static final Method ADVANCE=method("advanceDiscovery",CONTEXT),CANCEL=method("cancelWindow",SpiritTerrainService.class,WINDOW);
    private static final SourceMeshBuilder.Tile STONE=tile("minecraft:stone"),DIRT=tile("minecraft:dirt");
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Class<?> type(String name){try{return Class.forName(SpiritTerrainService.class.getName()+"$"+name);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static Field field(Class<?> type,String name) throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Method method(String name,Class<?> type,Class<?>... args){try{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static void invoke(Method method,Object target,Object... args) throws Exception {
        try{method.invoke(target,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;if(e.getCause() instanceof Error x)throw x;throw e;}
    }
    private static SourceMeshBuilder.Tile tile(String name){return new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material(name,Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0xf000f0,false,true);}
    /** A bounded held executor: no work runs inline; saturation is the same rejection contract as
     * production. Running one task on a separate real thread proves detached work cannot publish. */
    private static final class Worker extends AbstractExecutorService {
        final Thread owner=Thread.currentThread();final ArrayDeque<Runnable> queue=new ArrayDeque<>();boolean closed,reject;int ran;
        public void execute(Runnable task){check(Thread.currentThread()==owner,"only owner submits detached snapshots");if(closed||reject||queue.size()>=2)throw new RejectedExecutionException("held worker full");queue.addLast(task);}
        void runOne() throws Exception {
            if(queue.isEmpty())return;
            Runnable task=queue.removeFirst();
            Thread t=Thread.ofPlatform().daemon().name("discovery-completion-test-worker").start(()->{if(Thread.currentThread()==owner)throw new AssertionError("worker ran inline");task.run();});
            t.join(10000);check(!t.isAlive(),"detached test task completed without an owner-thread join in production");ran++;
        }
        public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;var rest=List.copyOf(queue);queue.clear();return rest;}
        public boolean isShutdown(){return closed;}
        public boolean isTerminated(){return closed&&queue.isEmpty();}
        public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
    private static final class Harness {
        final Object context;final Worker worker=new Worker();final Map<UUID,Object> sessions=new LinkedHashMap<>();long tick;
        Harness() throws Exception {
            Unsafe allocator=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);context=allocator.allocateInstance(CONTEXT);
            field(CONTEXT,"sessions").set(context,sessions);field(CONTEXT,"discoveryWorker").set(context,worker);
        }
        void step() throws Exception {field(CONTEXT,"tick").setLong(context,++tick);invoke(ADVANCE,context);}
        Fixture window() throws Exception {
            var wc=WINDOW.getDeclaredConstructor(String.class,Vec3d.class,Vec384f.class,Basis384f.class,String.class);wc.setAccessible(true);
            Object w=wc.newInstance(DIM,Vec3d.ZERO,Vec384f.ZERO(),new Basis384f(),"");
            var sc=SESSION.getDeclaredConstructor(WINDOW,Vec3d.class);sc.setAccessible(true);sessions.put(UUID.randomUUID(),sc.newInstance(w,Vec3d.ZERO));
            return new Fixture(w);
        }
    }
    private static final class Fixture {
        final Object window;final Map<Bounds,CompletableFuture<SourceLandmarks.Region>> requests;
        final Map<Bounds,List<SourceMeshBuilder.Node>> retained;final SourceDiscovery.Scheduler scheduler;final DiscoveryMeshPipeline pipeline;
        int materialized;boolean fail,reusable;final Thread owner=Thread.currentThread();
        @SuppressWarnings("unchecked") Fixture(Object window) throws Exception {
            this.window=window;
            requests=(Map<Bounds,CompletableFuture<SourceLandmarks.Region>>)field(WINDOW,"discoveryPending").get(window);
            retained=(Map<Bounds,List<SourceMeshBuilder.Node>>)field(WINDOW,"discovered").get(window);
            scheduler=(SourceDiscovery.Scheduler)field(WINDOW,"discovery").get(window);
            pipeline=new DiscoveryMeshPipeline(DIM,scheduler,requests,retained,(cell,at)->{
                check(Thread.currentThread()==owner,"registry/collision-shape materialization stays on the server thread");materialized++;
                if(fail)throw new IllegalArgumentException("bad material fixture");
                return new DiscoveryMeshPipeline.Resolved(cell.material().blockId().equals("minecraft:stone")?STONE:DIRT,reusable);
            });
            field(WINDOW,"discoveryMeshes").set(window,pipeline);pipeline.refresh(Vec3d.ZERO,false);
        }
        CompletableFuture<SourceLandmarks.Region> source(Bounds tile,List<SourceLandmarks.Cell> cells,boolean complete){
            return source(tile,new SourceLandmarks.Region(DIM,tile,cells,complete));
        }
        CompletableFuture<SourceLandmarks.Region> source(Bounds tile,SourceLandmarks.Region region){
            var f=new CompletableFuture<SourceLandmarks.Region>();requests.put(tile,f);scheduler.pending(tile);
            f.complete(region);return f;
        }
        @SuppressWarnings("unchecked") List<SourceMeshBuilder.Node> nodes() throws Exception{return (List<SourceMeshBuilder.Node>)field(WINDOW,"discoveredNodes").get(window);}
        boolean nodesUncheckedEmpty(){try{return nodes().isEmpty();}catch(Exception e){throw new AssertionError(e);}}
    }
    private static List<SourceLandmarks.Cell> cells(Bounds tile,int count){
        var cells=new ArrayList<SourceLandmarks.Cell>();
        for(int i=0;i<count;i++)cells.add(new SourceLandmarks.Cell(new BlockPoint(tile.minX()+i%32,tile.minY()+i/1024,tile.minZ()+(i/32)%32),
                new BlockPalette.State(((i%32+(i/32)%32+i/1024)&1)==0?"minecraft:stone":"minecraft:dirt",Map.of()),"minecraft:plains",true));
        return List.copyOf(cells);
    }
    private static List<SourceMeshBuilder.Node> nodes(Bounds tile){
        var nodes=new ArrayList<SourceMeshBuilder.Node>();
        for(int i=0;i<32768;i++)nodes.add(new SourceMeshBuilder.Node(new BlockPos((int)tile.minX()+i%32,(int)tile.minY()+i/1024,(int)tile.minZ()+(i/32)%32),1,((i%32+(i/32)%32+i/1024)&1)==0?STONE:DIRT));
        return List.copyOf(nodes);
    }
    private static DiscoveryMeshPipeline.Budget unlimited(){return new DiscoveryMeshPipeline.Budget(256,1,()->0);}
    private static void index(Harness h,Fixture f,long tick) throws Exception {
        int before=f.materialized;f.pipeline.advance(unlimited(),h.worker,tick);h.worker.runOne();
        check(f.materialized==before,"detached indexing never evaluates registry/shape callbacks");
    }

    private static void fullTileThroughProductionContext() throws Exception {
        var h=new Harness();var f=h.window();Bounds tile=Bounds.cube(0,0,0,32),near=Bounds.cube(64,0,0,32),far=Bounds.cube(256,0,0,32);
        f.retained.put(near,nodes(near));f.retained.put(far,nodes(far));f.scheduler.completed(near,true,0);f.scheduler.completed(far,true,0);
        var source=f.source(tile,cells(tile,32768),true);
        check(source.isDone()&&f.materialized==0&&f.retained.size()==2,"source future completion does no registry or mesh work inline");
        int simulatedMovementTicks=0;
        for(int tick=0;tick<3000&&!f.requests.isEmpty();tick++){
            int before=f.materialized;h.step();simulatedMovementTicks++;
            check(f.materialized-before<=DiscoveryMeshPipeline.CELLS_PER_WINDOW,"actual Context bounds each materialization advance");
            check(f.requests.size()<=SourceDiscovery.MAX_INFLIGHT,"completed regions keep their bounded request slots");
            if(f.materialized==32768&&f.requests.containsKey(tile)){
                // Both expensive sorts remain held: owner ticks returned without doing them.
                check(f.nodes().size()<=65536,"only previously published nodes are visible before worker publication");
            }
            if(!h.worker.queue.isEmpty()){
                var beforeNodes=f.nodes();var beforeTiles=new LinkedHashMap<>(f.retained);h.worker.runOne();
                check(f.nodes()==beforeNodes&&f.retained.equals(beforeTiles),"worker compaction/retention cannot publish to live Window state");
            }
        }
        check(simulatedMovementTicks>=512&&f.materialized==32768,"all 32768 real observed cells materialized across bounded owner ticks, not truncated");
        check(f.requests.isEmpty()&&f.retained.containsKey(tile)&&f.retained.containsKey(near)&&!f.retained.containsKey(far),"detached 98304-node retention keeps the closest 65536 and evicts far data");
        check(f.nodes().size()==65536&&f.retained.get(tile).size()==32768,"checkerboard materials survive detached compaction without filling or dropping cells");
        check(f.scheduler.next(h.tick)==null,"completed tile is covered and evicted tile has cooldown");
        check(f.scheduler.next(h.tick+SourceDiscovery.RETRY_ERROR_TICKS).equals(far),"worker eviction is applied to real scheduler coverage for rediscovery");
        check(h.worker.ran>=2,"both observation indexing and full-tile compaction used detached work");
    }

    private static void sharedBudgetAndHeldWorker() throws Exception {
        var h=new Harness();var fs=new ArrayList<Fixture>();
        for(int i=0;i<8;i++){var f=h.window();fs.add(f);f.source(Bounds.cube(0,0,0,32),cells(Bounds.cube(0,0,0,32),256),true);}
        for(var f:fs)index(h,f,0);
        for(int tick=0;tick<40;tick++){
            int before=fs.stream().mapToInt(f->f.materialized).sum();h.step();int used=fs.stream().mapToInt(f->f.materialized).sum()-before;
            check(used<=DiscoveryMeshPipeline.CELLS_PER_TICK,"actual Context shares one global materialization allowance across eight players");
            check(h.worker.queue.size()<=2,"held worker queue stays bounded under completion pressure");
            // Simulated movement can run every tick even while the worker is deliberately paused.
        }
        check(fs.stream().allMatch(f->f.materialized>0),"server window rotation prevents registry-work starvation under a held worker");
        check(fs.stream().allMatch(f->f.nodesUncheckedEmpty()),"held worker never triggers synchronous fallback compaction");
        for(int tick=0;tick<1000&&fs.stream().anyMatch(f->!f.requests.isEmpty());tick++){h.worker.runOne();h.step();}
        check(fs.stream().allMatch(f->f.requests.isEmpty()&&f.retained.size()==1),"backpressured windows publish real geometry once worker turns resume");
    }

    private static void revisionsCancellationAndFailure() throws Exception {
        var h=new Harness();var f=h.window();Bounds tile=Bounds.cube(0,0,0,32);var source=f.source(tile,cells(tile,8),false);
        index(h,f,0);h.worker.reject=true;
        f.pipeline.advance(unlimited(),h.worker,0);
        check(f.materialized==8&&f.requests.get(tile)==source&&f.retained.isEmpty(),"worker rejection preserves completed cells/request identity without inline fallback");
        h.worker.reject=false;f.pipeline.advance(unlimited(),h.worker,1);h.worker.runOne();
        f.pipeline.refresh(new Vec3d(1,0,0),false); // retention snapshot became stale while worker was running
        f.pipeline.advance(unlimited(),h.worker,2);
        check(f.retained.isEmpty()&&f.requests.get(tile)==source,"replan invalidates detached retention before publication");
        for(int poll=0;poll<10;poll++)f.pipeline.refresh(new Vec3d(1,0,0),false);
        h.worker.runOne();f.pipeline.advance(unlimited(),h.worker,3);
        check(f.materialized==8&&f.retained.containsKey(tile)&&f.requests.isEmpty(),"replan reuses detached tile compaction and stationary replans cannot starve worker publication");
        f.scheduler.replan(Vec3d.ZERO,2);
        Bounds queued;while((queued=f.scheduler.next(3))!=null){check(!queued.equals(tile),"partial observation respects its retry cooldown");f.scheduler.pending(queued);}
        check(tile.equals(f.scheduler.next(3+SourceDiscovery.RETRY_INCOMPLETE_TICKS)),"partial observation is retried rather than granting permanent coverage");

        var old=f.source(tile,cells(tile,8),true);index(h,f,4);f.pipeline.advance(unlimited(),h.worker,4);
        invoke(CANCEL,null,f.window); // actual window cleanup, while compaction is queued
        var replacement=f.source(tile,cells(tile,1),true);
        h.worker.runOne();f.pipeline.advance(unlimited(),h.worker,5);
        check(f.requests.get(tile)==replacement&&old.isDone()&&f.retained.isEmpty(),"late cancelled work cannot displace fresh same-tile request or restore retired data");
        h.worker.runOne();f.pipeline.advance(unlimited(),h.worker,6);
        h.worker.runOne();f.pipeline.advance(unlimited(),h.worker,6);
        check(f.requests.isEmpty()&&f.retained.get(tile).size()==1,"fresh request still publishes after window cancellation");
        f.fail=true;f.source(Bounds.cube(32,0,0,32),cells(Bounds.cube(32,0,0,32),8),true);
        index(h,f,7);f.pipeline.advance(unlimited(),h.worker,7);
        check(f.requests.isEmpty()&&f.retained.size()==1,"registry failure cannot publish a partial tile or drop prior valid terrain");
    }
    private static void deadlineAndAirBudget() throws Exception {
        var h=new Harness();var f=h.window();Bounds tile=Bounds.cube(0,0,0,32);f.source(tile,cells(tile,20),true);
        index(h,f,0);long[] clock={0};var budget=new DiscoveryMeshPipeline.Budget(256,3,()->clock[0]++);
        f.pipeline.advance(budget,h.worker,0);
        check(f.materialized==2&&budget.remaining()==254,"elapsed budget stops materialization before the cell-count cap");
        invoke(CANCEL,null,f.window);var air=new BlockPalette.State("minecraft:air",Map.of());
        var cells=new ArrayList<SourceLandmarks.Cell>();for(int i=0;i<1000;i++)cells.add(new SourceLandmarks.Cell(new BlockPoint(i%32,i/1024,(i/32)%32),air,"minecraft:plains",true));
        f.source(tile,cells,true);index(h,f,1);budget=unlimited();f.pipeline.advance(budget,h.worker,1);
        check(budget.remaining()==256,"air scans stay on bounded detached jobs, consuming zero owner-thread shape budget");
        check(f.materialized==2,"air never invokes the registry/collision adapter");
    }
    private static void dimensionScopedCompletion() throws Exception {
        var h=new Harness();var f=h.window();Bounds tile=Bounds.cube(0,0,0,32),prior=Bounds.cube(64,0,0,32);
        f.retained.put(prior,List.of(new SourceMeshBuilder.Node(new BlockPos(64,0,0),1,STONE)));
        f.scheduler.replan(Vec3d.ZERO,4);
        var observed=cells(tile,8);
        // Identical source coordinates/material cannot make a foreign completed region local.
        f.source(tile,new SourceLandmarks.Region("minecraft:the_nether",tile,observed,true));
        h.step();
        check(f.materialized==0 && f.requests.isEmpty(),"foreign completed region rejected before registry work");
        h.worker.runOne();h.step();
        check(!f.retained.containsKey(tile) && f.retained.containsKey(prior),"foreign completion cannot publish or replace local terrain");
        Bounds next;while((next=f.scheduler.next(h.tick))!=null){
            check(!tile.equals(next),"foreign result schedules real error cooldown, not false coverage");f.scheduler.pending(next);
        }
        check(tile.equals(f.scheduler.next(h.tick+SourceDiscovery.RETRY_ERROR_TICKS)),"foreign result never grants scheduler coverage");
        f.source(tile,new SourceLandmarks.Region(DIM,prior,observed,true));h.step();
        check(f.materialized==0 && f.requests.isEmpty(),"wrong source range rejected before registry work");
        f.source(tile,observed,true);
        for(int i=0;i<20&&!f.requests.isEmpty();i++){h.step();h.worker.runOne();}
        check(f.materialized==8 && f.requests.isEmpty() && f.retained.containsKey(tile),"correct namespace still publishes after rejected completion");

        // Exercise the real Window constructor too (the fixture otherwise replaces its adapter).
        var wc=WINDOW.getDeclaredConstructor(String.class,Vec3d.class,Vec384f.class,Basis384f.class,String.class);wc.setAccessible(true);
        Object w=wc.newInstance("minecraft:the_end",Vec3d.ZERO,Vec384f.ZERO(),new Basis384f(),"");
        var production=(DiscoveryMeshPipeline)field(WINDOW,"discoveryMeshes").get(w);
        check(field(DiscoveryMeshPipeline.class,"dimension").get(production).equals("minecraft:the_end"),"window binds discovery pipeline to its immutable source namespace");
    }
    private static void paletteThroughputAndHeldIndex() throws Exception {
        var h=new Harness();var f=h.window();f.reusable=true;Bounds tile=Bounds.cube(0,0,0,32);
        f.source(tile,cells(tile,32768),true);h.worker.reject=true;
        for(int i=0;i<20;i++)h.step();
        check(f.materialized==0&&f.retained.isEmpty()&&f.requests.size()==1,"index saturation never scans/materializes/publishes inline or loses the slot");
        h.worker.reject=false;h.step();
        for(int i=0;i<20;i++)h.step();
        check(f.materialized==0&&f.retained.isEmpty(),"a held index cannot block owner ticks or invoke registry work");
        int turns=0;
        while(!f.requests.isEmpty()&&turns++<12){h.worker.runOne();h.step();}
        check(f.requests.isEmpty()&&turns<=6,"full-size static tile publishes within six worker turns, not 512 voxel ticks");
        check(f.materialized==2&&f.retained.get(tile).size()==32768,"two static palette evaluations preserve ALL checkerboard cells/materials, no truncation or invented coarse cubes");
        check(f.retained.get(tile).stream().allMatch(n->n.tile().equals(((n.position().getX()+n.position().getY()+n.position().getZ())&1)==0?STONE:DIRT)),"palette expansion associates exact source positions with actual material");

        // Cancellation during indexing must invalidate its result just like cancellation during compaction.
        f.source(tile,cells(tile,32768),true);f.pipeline.advance(unlimited(),h.worker,0);
        invoke(CANCEL,null,f.window);f.source(tile,cells(tile,1),true);
        for(int i=0;i<8;i++){h.worker.runOne();f.pipeline.advance(unlimited(),h.worker,i);}
        check(f.requests.isEmpty()&&f.retained.get(tile).size()==1,"late index cannot restore retired 32768-cell data over fresh same-tile request");
    }
    public static void main(String[] args) throws Exception {
        paletteThroughputAndHeldIndex();fullTileThroughProductionContext();sharedBudgetAndHeldWorker();revisionsCancellationAndFailure();deadlineAndAirBudget();dimensionScopedCompletion();
        System.out.println("DiscoveryMeshCompletionSelfTest: "+checks+" checks passed");
    }
}
