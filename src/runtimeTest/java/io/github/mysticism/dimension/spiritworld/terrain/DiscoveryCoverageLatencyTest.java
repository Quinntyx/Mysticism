package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.*;

/** End-to-end tick-latency regression: actual horizon scheduler -> SourceLandmarks DiscoveryRead
 * and Session fairness -> four generated saved-chunk slices -> detached indexing -> bounded
 * owner-thread palette resolution -> real compaction/retention -> published cells. Only the
 * registry adapter and saved ChunkIo are fixtures. No server/model boot, IO wall-time or FPS claim. */
public final class DiscoveryCoverageLatencyTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Worker extends AbstractExecutorService implements AutoCloseable {
        final Thread owner=Thread.currentThread();final ArrayDeque<Runnable> queue=new ArrayDeque<>();final int capacity;
        final ExecutorService runner=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("coverage-fixture-worker").factory());boolean closed;
        Worker(int capacity){this.capacity=capacity;}
        public void execute(Runnable task){check(Thread.currentThread()==owner,"owner only submits bounded tasks");if(closed||queue.size()>=capacity)throw new RejectedExecutionException();queue.add(task);}
        void turn()throws Exception{
            var tasks=List.copyOf(queue);queue.clear();
            runner.submit(()->{if(Thread.currentThread()==owner)throw new AssertionError("inline worker");tasks.forEach(Runnable::run);}).get(20,TimeUnit.SECONDS); // test-only completion at tick boundary
        }
        public void shutdown(){closed=true;runner.shutdown();}
        public List<Runnable> shutdownNow(){shutdown();var rest=List.copyOf(queue);queue.clear();return rest;}
        public boolean isShutdown(){return closed;}
        public boolean isTerminated(){return closed&&queue.isEmpty();}
        public boolean awaitTermination(long timeout,TimeUnit unit)throws InterruptedException{return runner.awaitTermination(timeout,unit);}
        public void close(){shutdownNow();}
    }
    private static void horizon(int view,boolean full)throws Exception{
        try(var sourceWorker=new Worker(16);var meshWorker=new Worker(2)){
            var source=new GeneratedDiscoveryFixture(sourceWorker);Vec3d eye=new Vec3d(0,64,0);
            var scheduler=new SourceDiscovery.Scheduler();scheduler.replan(eye,view);
            var pending=new LinkedHashMap<Bounds,CompletableFuture<SourceLandmarks.Region>>();
            var retained=new LinkedHashMap<Bounds,List<SourceMeshBuilder.Node>>();
            Thread owner=Thread.currentThread();int[] shapes={0};
            var stone=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:stone",Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0xf000f0,false,true);
            var pipeline=new DiscoveryMeshPipeline("minecraft:overworld",scheduler,pending,retained,(cell,at)->{
                check(Thread.currentThread()==owner,"actual source shape evaluations remain owner-thread confined");shapes[0]++;
                check(cell.material().blockId().equals("minecraft:stone"),"only observed solid material reaches registry adapter");
                return new DiscoveryMeshPipeline.Resolved(stone,true);
            });pipeline.refresh(eye,false);
            Set<Bounds> published=new HashSet<>();int firstHorizon=0,allHorizon=0,bearings=0,ticks=0,movements=0,maxMovement=0,start=0;
            CompletableFuture<SourceLandmarks.Region> movement=null;
            int expected=SourceDiscovery.plan(eye,view).size();
            for(;ticks<3000&&(full?published.size()<expected:allHorizon==0);ticks++){
                if(ticks%2==0)while(pending.size()<SourceDiscovery.MAX_INFLIGHT){
                    Bounds tile=scheduler.next(ticks);if(tile==null)break;
                    pending.put(tile,source.request(tile,false));scheduler.pending(tile);
                }
                if(ticks%20==0&&movement==null){movement=source.request(Bounds.cube(0,64,0,1),true);start=ticks;}
                source.tick();
                int before=shapes[0];pipeline.advance(new DiscoveryMeshPipeline.Budget(256,1,()->0),meshWorker,ticks);
                check(shapes[0]-before<=DiscoveryMeshPipeline.CELLS_PER_WINDOW,"source voxel count never expands the owner shape budget");
                check(pending.size()<=2&&meshWorker.queue.size()<=2&&sourceWorker.queue.size()<=16,"all admission/worker queues remain bounded");
                published.addAll(retained.keySet());
                double edge=view*16-1,diagonal=view*16*.67;
                for(var node:pipeline.nodes())if(node.position().getY()<64){
                    Box box=SourceMeshBuilder.nodeBox(node);
                    if(box.maxX>=edge)bearings|=1;if(box.minX<=-edge)bearings|=2;
                    if(box.maxZ>=edge)bearings|=4;if(box.minZ<=-edge)bearings|=8;
                    if(box.maxX>=diagonal&&box.maxZ>=diagonal)bearings|=16;
                    if(box.minX<=-diagonal&&box.maxZ>=diagonal)bearings|=32;
                    if(box.maxX>=diagonal&&box.minZ<=-diagonal)bearings|=64;
                    if(box.minX<=-diagonal&&box.minZ<=-diagonal)bearings|=128;
                }
                if(firstHorizon==0&&(bearings&1)!=0)firstHorizon=ticks+1;
                if(allHorizon==0&&bearings==255)allHorizon=ticks+1;
                if(movement!=null&&movement.isDone()){
                    check(movement.getNow(null).complete(),"movement read still observes real source air");
                    maxMovement=Math.max(maxMovement,ticks-start+1);movements++;movement=null;
                }
                // Neither source future completion nor owner polling runs these heavy stages inline.
                sourceWorker.turn();meshWorker.turn();
            }
            check(firstHorizon>0&&firstHorizon<=160,"real collision/render terrain reaches the horizon within eight simulated seconds (view="+view+", ticks="+firstHorizon+")");
            check(allHorizon>0&&allHorizon<=320,"real source support reaches all eight horizon bearings within sixteen simulated seconds, not only the nearest sector (view="+view+", ticks="+allHorizon+")");
            check(movements>0&&maxMovement<=4,"urgent movement reads retain four-tick scheduler latency under full horizon pressure");
            if(full){
                check(published.size()==expected,"all "+expected+" full-volume tiles publish, including air and distant source layers (got "+published.size()+")");
                check(ticks<=3000,"full eight-chunk coverage completes within 150 simulated seconds, not three hours");
                check(shapes[0]<expected*2,"static palette reuse avoids millions of repeated owner-thread shape evaluations");
                var cells=pipeline.nodes();check(!cells.isEmpty(),"coverage includes actual retained solid mesh, not just scheduler flags");
                var far=cells.stream().filter(n->n.position().getX()>=96&&n.position().getY()<64).findFirst().orElseThrow();
                var projected=SourceDiscovery.project(far,Vec3d.ZERO,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),eye).cell(0,1,1);
                var frame=new TerrainMeshFrame(1,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(stone.material()),List.of(projected));Vec3d inside=projected.point(.5,.5,.5);
                check(!MeshCollision.bodyClear(frame,new Box(inside.x-.01,inside.y-.01,inside.z-.01,inside.x+.01,inside.y+.01,inside.z+.01)),"distant published terrain has real SAT collision");
            }
            System.out.println("DiscoveryCoverageLatencyTest: view="+view+", published="+published.size()+"/"+expected+", ticks="+ticks+", first horizon="+firstHorizon+", all bearings="+allHorizon+", shape callbacks="+shapes[0]+", movement max="+maxMovement+", saved-chunk scans="+source.scans);
        }
    }
    public static void main(String[] args)throws Exception{
        check(DiscoveryCoverageLatencyTest.class.desiredAssertionStatus(),"runner enables assertions");
        for(int view:new int[]{1,2,3,8,16,32}){
            Vec3d eye=new Vec3d(-17,63,9);var order=SourceDiscovery.discoveryOrder(eye,view);var plan=SourceDiscovery.plan(eye,view);
            check(order.size()==plan.size()&&new HashSet<>(order).equals(new HashSet<>(plan)),"horizon bootstrap neither drops nor duplicates any required tile at view "+view);
        }
        horizon(8,true);horizon(32,false);
        System.out.println("DiscoveryCoverageLatencyTest: "+checks+" checks passed (simulated ticks, not measured gameplay/IO latency)");
    }
}
