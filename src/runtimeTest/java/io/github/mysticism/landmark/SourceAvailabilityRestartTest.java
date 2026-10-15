package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.DiscoveryPrioritizer;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the production observation lifecycle without bootstrapping Minecraft or inference.
 * Null Session is deliberate: restarting must not access worlds, stores, models or source IO. */
public final class SourceAvailabilityRestartTest {
    private static final String DIM="minecraft:overworld";
    private static final BlockPos POSITION=new BlockPos(15,65,0);
    private static final Bounds NEIGHBOR=new Bounds(16,-64,0,32,320,16);
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        initialDiscoveryRetainsBindingCallback();
        fullQueueRestartsThroughFairScheduler();
        restartedBackgroundYieldsItsOldAgeReservation();
        hintLoadChurnCannotExpandRequestBudget();
        repeatedLoadsCoalesceAndPreserveAllDiscoveryIntents();
        readRestartsAndDeliversOnlyFreshSnapshot();
        unrelatedAndFinishedObservationsAreNotRestarted();
        cancellationAndOtherInvalidationsRemainTerminal();
        System.out.println("SourceAvailabilityRestartTest: "+checks+" checks passed");
    }
    private static SourceLandmarks.Ensure ensure(int tier){
        var op=new SourceLandmarks.Ensure(null,DIM,POSITION,"preferred-owner",true,new CompletableFuture<>(),tier);
        op.enqueued=17;return op;
    }
    private static SourceLandmarks.Ensure restart(SourceLandmarks.Ensure old,List<SourceLandmarks.Operation<?>> queue){
        check(SourceLandmarks.restartAvailable(old,queue,40)==null,"invalidated active slot released for scheduler");
        check(queue.size()==1 && queue.getFirst() instanceof SourceLandmarks.Ensure,"one fresh Ensure attempt queued");
        return (SourceLandmarks.Ensure)queue.removeFirst();
    }
    private static void initialDiscoveryRetainsBindingCallback(){
        var initial=ensure(DiscoveryPrioritizer.PLAYER);
        var completed=new AtomicInteger();var bound=new AtomicInteger();
        // Same one-shot whenComplete shape as SpiritTerrainService.discoverOwner: errors/empty
        // findings do not bind. These are metadata fixtures, NOT claimed model inference.
        initial.future.whenComplete((found,error)->{completed.incrementAndGet();if(error==null&&found.isPresent())bound.incrementAndGet();});
        var metadata=fixtureMetadata();
        initial.snapshot=CompletableFuture.completedFuture(new SourceLandmarks.Region(DIM,initial.bounds,List.of(),false));
        initial.observed=initial.snapshot.getNow(null);initial.cursor="old-catalog-cursor";initial.catalogEnd=true;
        initial.candidates.add(metadata);initial.parents.add(metadata.header());initial.importance.put(metadata.id(),.5);
        // Represents detached work already running: cancellation cannot guarantee it stops.
        initial.prepared=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};
        initial.work=initial.prepared;
        initial.guarded=initial.guarded.union(new Bounds(0,0,0,96,96,96));
        var releases=new AtomicInteger();initial.resume=releases::incrementAndGet;
        initial.sourceAvailable(DIM,NEIGHBOR);
        check(!initial.future.isDone() && completed.get()==0,"neighbor load must not consume caller callback");
        var queue=new ArrayList<SourceLandmarks.Operation<?>>();var fresh=restart(initial,queue);
        check(initial.done && releases.get()==1 && initial.resume==null,"old attempt retired and pause released exactly once");
        initial.release();check(releases.get()==1,"later cleanup cannot release the pause twice");
        check(fresh.future==initial.future && !fresh.future.isDone(),"original caller future retained across restart");
        check(fresh.position.equals(initial.position)&&fresh.preferred.equals(initial.preferred)&&fresh.activity==initial.activity,
                "source point, preferred owner and activity intent retained");
        check(fresh.enqueued==40 && fresh.tier==DiscoveryPrioritizer.PLAYER,"urgency retained while restarted attempt rejoins current age");
        check(fresh.bounds.equals(initial.bounds)&&fresh.guarded.equals(fresh.bounds),"fresh snapshot guard starts at original bounds");
        check(fresh.reader==null&&fresh.snapshot==null&&fresh.hydrated==null&&fresh.geometryRead==null&&fresh.work==null,
                "reader, snapshot and hydration state are detached from discarded attempt");
        check(fresh.prepared==null&&fresh.observed==null&&fresh.embedding==null&&fresh.value==null&&fresh.published==null,
                "no stale observation, preparation, embedding or publication survives");
        check(fresh.mutation==null&&fresh.history==null&&fresh.resume==null,"no staged mutation or pause is reused");
        check(fresh.cursor==null&&!fresh.catalogEnd&&fresh.candidates.isEmpty()&&fresh.parents.isEmpty()&&fresh.pages.isEmpty()&&fresh.importance.isEmpty(),
                "fresh attempt rescans current parents, pages and importance");
        check(initial.prepared.complete(null),"discarded non-preemptible worker can still finish");
        check(!fresh.future.isDone()&&completed.get()==0&&bound.get()==0,"stale worker completion cannot deliver or lose binding");
        fresh.complete(Optional.of(metadata));
        check(fresh.future.join().orElseThrow()==metadata&&completed.get()==1&&bound.get()==1,
                "fresh success delivers the ORIGINAL owner-binding callback once");
    }
    private static void fullQueueRestartsThroughFairScheduler(){
        var queue=new ArrayList<SourceLandmarks.Operation<?>>();
        for(int n=0;n<64;n++){var op=ensure(DiscoveryPrioritizer.BACKFILL);op.enqueued=n;queue.add(op);}
        var player=ensure(DiscoveryPrioritizer.PLAYER);player.enqueued=1000;
        player.sourceAvailable(DIM,NEIGHBOR);
        check(SourceLandmarks.restartAvailable(player,queue,1000)==null,"restart relinquishes active slot at full queue capacity");
        check(queue.size()==65&&queue.stream().filter(op->op.future==player.future).count()==1,
                "vacated active slot retains one original caller without eviction or new request admission");
        check(queue.stream().noneMatch(op->op.future.isDone()),"full queue restart rejects or cancels nobody");
        var policy=new DiscoveryPrioritizer(128);
        check(policy.offer(DIM,new BlockPoint(0,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),-100),"older hint admitted");
        check(SourceLandmarks.nextOperation(policy,queue,List.of(),1000,false)==null,
                "model-unready restart retains its reserved slot without activating a hint");
        var background=SourceLandmarks.nextOperation(policy,queue,List.of(),1000,true);
        check(background instanceof SourceLandmarks.Operation<?>&&policy.size()==1,
                "full restarted request queue must reclaim the reserved active slot before another hint activation");
        check(background.tier()==DiscoveryPrioritizer.BACKFILL,"retry does not bypass aged background's turn");
        queue.remove(background);policy.dispatched(background);
        var urgent=SourceLandmarks.nextOperation(policy,queue,List.of(),1250,true);
        check(urgent instanceof SourceLandmarks.Ensure op&&op.future==player.future,
                "original binding caller gets reserved urgent turn even after another long operation");
        queue.remove(urgent);policy.dispatched(urgent);
        check(queue.size()==63&&!player.future.isDone(),"normal activation restores bounded queue without completing caller early");
        check(SourceLandmarks.nextOperation(policy,queue,List.of(),1500,true) instanceof DiscoveryPrioritizer.Hint,
                "waiting older hint resumes once the reserved active slot is reclaimed");
    }
    private static void restartedBackgroundYieldsItsOldAgeReservation(){
        var background=ensure(DiscoveryPrioritizer.BACKFILL);background.enqueued=0;
        var waiting=ensure(DiscoveryPrioritizer.BACKFILL);waiting.enqueued=1;
        var queue=new ArrayList<SourceLandmarks.Operation<?>>();queue.add(waiting);
        background.sourceAvailable(DIM,NEIGHBOR);
        check(SourceLandmarks.restartAvailable(background,queue,1000)==null,"aged background restart yields its active slot");
        var policy=new DiscoveryPrioritizer(128);
        var next=policy.next(queue,List.of(),1000,DiscoveryPrioritizer.AGING_TICKS,true);
        check(next==waiting,"restarted background cannot keep stealing oldest-aged dispatch from existing locality");
        queue.remove(next);policy.dispatched(next);
        check(policy.next(queue,List.of(),1200,DiscoveryPrioritizer.AGING_TICKS,true).enqueuedTick()==1000,
                "restarted caller can age and progress normally once older locality gets its turn");
    }
    private static void hintLoadChurnCannotExpandRequestBudget(){
        var queue=new ArrayList<SourceLandmarks.Operation<?>>();var originalCompletions=new AtomicInteger();
        for(int n=0;n<64;n++){
            var op=ensure(DiscoveryPrioritizer.BACKFILL);op.enqueued=n;
            op.future.whenComplete((value,error)->{check(error==null,"original queued intent was not evicted");originalCompletions.incrementAndGet();});
            queue.add(op);
        }
        var policy=new DiscoveryPrioritizer(128);
        for(int n=0;n<128;n++)check(policy.offer(DIM,new BlockPoint(n*16L,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),-1000-n),"old hint backlog admitted");
        for(int n=0;n<128;n++){
            long now=1000L+n*300;
            var selected=SourceLandmarks.nextOperation(policy,queue,List.of(),now,true);
            check(selected instanceof DiscoveryPrioritizer.Hint,"full regular queue still permits existing aged hints");
            var hint=(DiscoveryPrioritizer.Hint)selected;policy.remove(hint);policy.dispatched(hint);
            var pos=hint.key().pos();var active=new SourceLandmarks.Ensure(null,DIM,new BlockPos((int)pos.x(),(int)pos.y(),(int)pos.z()),null,false,true,new CompletableFuture<>(),hint.tier());
            active.enqueued=hint.enqueuedTick();active.sourceAvailable(DIM,active.bounds);
            check(SourceLandmarks.restartAvailable(active,queue,now)==null&&queue.size()==65,
                    "only one reserved active slot is queued during hint restart");
            selected=SourceLandmarks.nextOperation(policy,queue,List.of(),now,true);
            check(selected instanceof SourceLandmarks.Ensure,"restart must dispatch retained request, not another aged hint");
            check(queue.remove(selected),"reserved slot reclaimed by actual request activation");policy.dispatched(selected);
            ((SourceLandmarks.Ensure)selected).complete(Optional.empty());
            check(queue.size()==64,"sustained hint invalidation cannot grow the bounded request backlog");
            check(policy.offer(DIM,new BlockPoint((128+n)*16L,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),-2000-n),"background chunk hints can continue arriving");
        }
        check(originalCompletions.get()==64,"original full locality backlog drains despite repeated active hint invalidations");
    }
    private static void repeatedLoadsCoalesceAndPreserveAllDiscoveryIntents(){
        for(int tier:new int[]{DiscoveryPrioritizer.PLAYER,DiscoveryPrioritizer.FRONTIER,DiscoveryPrioritizer.BACKFILL})for(boolean refresh:List.of(false,true)){
            var op=new SourceLandmarks.Ensure(null,DIM,POSITION,"preferred-owner",true,refresh,new CompletableFuture<>(),tier);var caller=op.future;var callbacks=new AtomicInteger();
            caller.whenComplete((value,error)->callbacks.incrementAndGet());
            var queue=new ArrayList<SourceLandmarks.Operation<?>>();
            for(int attempt=0;attempt<24;attempt++){
                for(int load=0;load<16;load++)op.sourceAvailable(DIM,NEIGHBOR);
                var staleWork=new CompletableFuture<Void>();op.work=staleWork;
                op=restart(op,queue);
                check(staleWork.isCancelled(),"discarded attempt's cancellable worker cancelled");
                check(op.future==caller&&!caller.isDone()&&callbacks.get()==0,"repeated availability must not lose caller intent");
                check(op.tier==tier&&op.enqueued==40,"coalesced restart retains discovery tier and uses requeue age");
                check(op.refresh==refresh&&Objects.equals(op.refreshArea(),refresh?op.bounds:null),"restart retains exact vs whole-window refresh discovery semantics");
                check(SourceLandmarks.restartAvailable(op,queue,40)==op&&queue.isEmpty(),"fresh attempt does not spontaneously restart");
            }
            op.complete(Optional.empty());check(callbacks.get()==1,"stable source can complete once after sustained chunk loads");
        }
    }
    private static void readRestartsAndDeliversOnlyFreshSnapshot(){
        var bounds=new Bounds(15,64,0,18,66,2);var caller=new CompletableFuture<SourceLandmarks.Region>();
        var read=new SourceLandmarks.Read(null,DIM,bounds,caller);read.enqueued=23;
        var stale=new SourceLandmarks.Region(DIM,bounds,List.of(),false);
        read.snapshot=CompletableFuture.completedFuture(stale);read.work=read.snapshot;
        read.sourceAvailable(DIM,NEIGHBOR);
        var queue=new ArrayList<SourceLandmarks.Operation<?>>();
        check(SourceLandmarks.restartAvailable(read,queue,40)==null,"read also refreshes on loaded-neighbor availability");
        var fresh=(SourceLandmarks.Read)queue.removeFirst();
        check(fresh.future==caller&&fresh.tier==DiscoveryPrioritizer.READ&&fresh.enqueued==40&&fresh.bounds.equals(bounds),
                "exact read query, urgency and original result future retained with new attempt age");
        check(!caller.isDone()&&fresh.snapshot==null,"old completed snapshot not delivered after load");
        var current=new SourceLandmarks.Region(DIM,bounds,List.of(),true);fresh.snapshot=CompletableFuture.completedFuture(current);
        fresh.advance();check(caller.join()==current,"real Read.advance delivers only refreshed snapshot");
    }
    private static void unrelatedAndFinishedObservationsAreNotRestarted(){
        var op=ensure(DiscoveryPrioritizer.PLAYER);var queue=new ArrayList<SourceLandmarks.Operation<?>>();
        op.sourceAvailable("minecraft:the_nether",NEIGHBOR);
        op.sourceAvailable(DIM,new Bounds(128,0,128,144,100,144));
        op.sourceAvailable(DIM,new Bounds(op.bounds.maxX(),0,0,op.bounds.maxX()+16,100,16));
        check(SourceLandmarks.restartAvailable(op,queue,40)==op&&queue.isEmpty()&&!op.availabilityChanged,
                "other dimension, distant chunk and exclusive-boundary load cannot invalidate observation");
        op.complete(Optional.empty());op.sourceAvailable(DIM,NEIGHBOR);
        check(SourceLandmarks.restartAvailable(op,queue,40)==op&&queue.isEmpty()&&op.future.join().isEmpty(),
                "published/completed observation never restarts or redelivers");
    }
    private static void cancellationAndOtherInvalidationsRemainTerminal(){
        var queue=new ArrayList<SourceLandmarks.Operation<?>>();var cancelled=ensure(DiscoveryPrioritizer.PLAYER);
        cancelled.sourceAvailable(DIM,NEIGHBOR);cancelled.future.cancel(false);
        check(SourceLandmarks.restartAvailable(cancelled,queue,40)==cancelled&&queue.isEmpty(),"caller cancellation cannot be resurrected by pending load");
        var edited=ensure(DiscoveryPrioritizer.PLAYER);edited.sourceAvailable(DIM,NEIGHBOR);
        edited.cancel(new CancellationException("source edit/unload/server stop"));
        check(SourceLandmarks.restartAvailable(edited,queue,40)==edited&&queue.isEmpty()&&edited.future.isCompletedExceptionally(),
                "terminal edit, unload and shutdown cancellation still supersede pending load");
        var nonObservation=new SourceLandmarks.Operation<Boolean>(null,DIM,NEIGHBOR,new CompletableFuture<>()){
            @Override void advance(){throw new AssertionError("non-observation must not advance after invalidation");}
        };
        nonObservation.sourceAvailable(DIM,NEIGHBOR);
        check(SourceLandmarks.restartAvailable(nonObservation,queue,40)==nonObservation&&nonObservation.done&&nonObservation.future.isCompletedExceptionally()&&queue.isEmpty(),
                "non-observation topology operations preserve terminal invalidation rather than unsafe replay");
    }
    private static LandmarkMetadata fixtureMetadata(){
        var point=new BlockPoint(15,65,0);var bounds=Bounds.cube(point.x(),point.y(),point.z(),1);
        var data=Vec384f.ZERO().data();data[0]=1;
        var profile=new EmbeddingProfile("unit-test-fixture","fixture","fixture","fixture",data.length,EmbeddingProfile.Normalization.UNIT,"fixture");
        var id=LandmarkIds.seed(DIM,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",point);
        return new LandmarkMetadata(new Landmark(id,DIM,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",point,bounds,
                new LandmarkEmbedding(profile,new Vec384f(data)),.08,new ActivityMetadata(0,0),new Ownership(List.of()),
                new SourceGeometry(List.of(),List.of()),0,"unit-test callback metadata fixture"),List.of());
    }
}
