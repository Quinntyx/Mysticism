package io.github.mysticism.landmark;

import java.util.*;
import java.util.function.Predicate;

/** Regressions for the production source-pipeline scheduler: wider source discovery must
 * never block movement-critical reads (terrain mesh/collision/support), never hold the
 * pipeline while gated on real embedding readiness, never starve under read pressure, never
 * let hint-cascade background work delay requested walk/support/target answers, never park
 * allow resource owners to yield ONLY to reads while retaining discovery precedence, and
 * support invalidation of both queued observations and suspended owners after source edits. */
public final class SourceScheduleSelfTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}

    /** Plain identity-equals op model mirroring SourceLandmarks.Operation classification. */
    static final class Op {
        final String id;final boolean read,background;
        Op(String id,boolean read,boolean background){this.id=id;this.read=read;this.background=background;}
        @Override public String toString(){return id;}
    }
    static Op read(String id){return new Op(id,true,false);}
    static Op discovery(String id){return new Op(id,false,false);}
    static Op cascade(String id){return new Op(id,false,true);}
    static final Predicate<Op> OPEN=op->true;
    static final Predicate<Op> PARKABLE=op->true;
    static final Predicate<Op> HOLDING_RESOURCE=op->false;

    public static void main(String[] args){
        readsPreemptRunningDiscovery();
        preemptedDiscoveryResumesInPlace();
        runningReadIsNeverPreempted();
        gatedHeadIsSkippedWithoutLoss();
        gatedDiscoveryNeverBlocksReads();
        backgroundNeverDelaysRequestedOrReads();
        movementLatencyBoundedAndDiscoveryNeverStarves();
        emptySchedulePicksNothing();
        resourceOwnerYieldsOnlyToReads();
        backgroundOwnerRetainsPrecedenceAndProgress();
        suspendedOwnerCancellationReleasesExactlyOnce();
        quotaExpiryGrantsOneDiscoveryTick();
        cancelIfRemovesMatchedOpsAcrossLanesExactlyOnce();
        editDuringPreemptionInvalidatesParkedObservation();
        System.out.println("SourceScheduleSelfTest: "+checks+" checks passed");
    }

    /** A discovery op occupying the pipeline is parked the moment a movement read waits. */
    private static void readsPreemptRunningDiscovery(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var ensure=discovery("ensure-1");
        schedule.discover(ensure);
        check(schedule.pick(null,OPEN,PARKABLE)==ensure,"requested discovery starts when idle");
        var walkRead=read("walk-region");
        schedule.read(walkRead);
        check(schedule.pick(ensure,OPEN,PARKABLE)==walkRead,"running discovery is parked as soon as a movement read waits");
        check(schedule.discoveryPending()==1,"parked discovery is retained, not cancelled");
        // The running read is never interrupted, even by more reads.
        var another=read("mesh-region");schedule.read(another);
        check(schedule.pick(walkRead,OPEN,PARKABLE)==walkRead,"running read keeps the pipeline");
        check(schedule.pick(null,OPEN,PARKABLE)==another,"queued read is served before parked discovery");
        check(schedule.pick(null,OPEN,PARKABLE)==ensure,"parked discovery resumes after reads drain");
    }

    /** Parked discovery resumes in FIFO position; later offers cannot overtake it. */
    private static void preemptedDiscoveryResumesInPlace(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var first=discovery("first");
        schedule.discover(first);
        check(schedule.pick(null,OPEN,PARKABLE)==first,"FIFO discovery start");
        schedule.discover(discovery("second"));
        var late=read("late-walk");schedule.read(late);
        check(schedule.pick(first,OPEN,PARKABLE)==late,"read preempts");
        check(schedule.pick(null,OPEN,PARKABLE)==first,"preempted op resumes ahead of later discovery offers");
        check(schedule.pick(null,OPEN,PARKABLE).id.equals("second"),"later discovery offer keeps its queue position");
    }

    private static void runningReadIsNeverPreempted(){
        var schedule=new SourceSchedule<Op>(1,op->op.read,op->op.background);
        var running=read("running");
        schedule.read(running);
        check(schedule.pick(null,OPEN,PARKABLE)==running,"read starts");
        var next=read("next");schedule.read(next);
        check(schedule.pick(running,OPEN,PARKABLE)==running,"reads are never preempted by reads");
        check(schedule.pick(running,OPEN,PARKABLE)==running,"reads are never preempted by waiting reads (repeat)");
    }

    /** An op gated on embedding readiness is skipped without losing its place: one stalled
     * op can never block later discovery in its own lane. */
    private static void gatedHeadIsSkippedWithoutLoss(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var gated=discovery("gated-on-engine");var later=discovery("later");
        schedule.discover(gated);schedule.discover(later);
        Predicate<Op> engineNotReady=op->!op.id.equals("gated-on-engine");
        check(schedule.pick(null,engineNotReady,PARKABLE)==later,"gated head is skipped");
        check(schedule.pick(null,engineNotReady,PARKABLE)==null,"gated op still holds its lane position");
        check(schedule.discoveryPending()==1,"the skipped op is retained");
        check(schedule.pick(null,OPEN,PARKABLE)==gated,"once admissible, the gated op runs in its original position");
    }

    private static void gatedDiscoveryNeverBlocksReads(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        schedule.discover(discovery("gated-on-engine"));
        var walkRead=read("support-region");
        schedule.read(walkRead);
        Predicate<Op> engineNotReady=op->false;
        check(schedule.pick(null,engineNotReady,PARKABLE)==walkRead,"movement reads run even when every discovery op is gated");
        check(schedule.pick(null,engineNotReady,PARKABLE)==null,"gated op does not run while engine is unavailable");
    }

    /** Hint-cascade background work runs only after requested discovery, and parks behind
     * reads exactly like requested discovery. */
    private static void backgroundNeverDelaysRequestedOrReads(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var cascade=cascade("frontier-hint");var requested=discovery("walk-target");
        schedule.background(cascade);schedule.discover(requested);
        check(schedule.pick(null,OPEN,PARKABLE)==requested,"requested discovery outranks background cascade");
        check(schedule.pick(null,OPEN,PARKABLE)==cascade,"background cascade runs only when requested work is done");
        var walkRead=read("walk-probe");
        schedule.read(walkRead);
        check(schedule.pick(cascade,OPEN,PARKABLE)==walkRead,"background op is parked the moment a read waits");
        check(schedule.pick(null,OPEN,PARKABLE)==cascade,"parked background op resumes in place");
    }

    /** Sustainable streaming load: each movement read waits at most a few ticks (the fair
     * share), and discovery still advances at its guaranteed rate instead of starving. */
    private static void movementLatencyBoundedAndDiscoveryNeverStarves(){
        int share=5,ticks=400;
        var schedule=new SourceSchedule<Op>(share,op->op.read,op->op.background);
        Map<Op,Integer> remaining=new IdentityHashMap<>(),offeredAt=new HashMap<>();
        int offeredDiscovery=0,completedDiscovery=0,discoveryTicks=0,servedReads=0,maxLatency=0;
        Op current=null;
        for(int tick=0;tick<ticks;tick++){
            // Terrain streaming keeps offering movement reads; discovery keeps cascading.
            if(tick%3==0){var streamRead=read("stream-"+tick);schedule.read(streamRead);offeredAt.put(streamRead,tick);servedReads++;}
            if(tick%15==0){var ensure=discovery("ensure-"+(offeredDiscovery++));schedule.discover(ensure);remaining.put(ensure,6);}
            current=schedule.pick(current,OPEN,PARKABLE);
            if(current==null)continue;
            if(current.read){maxLatency=Math.max(maxLatency,tick-offeredAt.remove(current));current=null;}
            else{discoveryTicks++;if(remaining.merge(current,-1,Integer::sum)==0){completedDiscovery++;current=null;}}
        }
        check(maxLatency<=share+3,"movement read latency stayed within the fair share (max="+maxLatency+")");
        check(completedDiscovery>0,"discovery kept progressing under read pressure ("+completedDiscovery+" completed)");
        int guaranteed=servedReads/(share+1)-2;
        check(discoveryTicks>=guaranteed,"discovery received its fair share of ticks ("+discoveryTicks+" >= "+guaranteed+")");
        // Once reads stop, the remaining cascade drains immediately and loses no op.
        current=null;int drained=0;
        while(schedule.discoveryPending()>0||drained==0&&current!=null){
            var op=schedule.pick(current,OPEN,PARKABLE);
            if(op==null)break;
            if(op.read){current=null;continue;}
            current=op;
            if(remaining.merge(op,-1,Integer::sum)==0){completedDiscovery++;drained++;current=null;}
        }
        check(schedule.discoveryPending()==0,"discovery drains promptly when reads stop");
        check(completedDiscovery==offeredDiscovery,"no discovery op was lost (completed="+completedDiscovery+", offered="+offeredDiscovery+")");
    }

    private static void emptySchedulePicksNothing(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        for(int i=0;i<3;i++)check(schedule.pick(null,OPEN,PARKABLE)==null,"empty lanes pick nothing");
        var op=discovery("single");
        schedule.discover(op);
        check(schedule.pick(null,OPEN,PARKABLE)==op,"idle schedule starts offered discovery");
        check(schedule.readPending()==0&&schedule.discoveryPending()==0&&schedule.backgroundPending()==0,"lane counters track reality");
    }

    /** Reads need no exclusive resource. Other discovery must never pass a suspended owner. */
    private static void resourceOwnerYieldsOnlyToReads(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var owner=discovery("geometry-read-owner");var other=discovery("other");
        schedule.discover(owner);schedule.discover(other);
        check(schedule.pick(null,OPEN,HOLDING_RESOURCE)==owner,"resource owner starts");
        var walkRead=read("walk-region");schedule.read(walkRead);
        check(schedule.pick(owner,OPEN,HOLDING_RESOURCE)==walkRead,"resource owner yields immediately to movement read");
        check(schedule.discoveryPending()==2,"pending count includes the suspended owner");
        check(schedule.discoveryLane.peekFirst()==other,"owner is reserved, not parked among competing discovery");
        check(schedule.pick(null,op->false,HOLDING_RESOURCE)==owner,"owner resumes even if model readiness changes");
        check(schedule.pick(null,OPEN,HOLDING_RESOURCE)==other,"other discovery proceeds only after owner finishes");
        check(schedule.pick(null,OPEN,HOLDING_RESOURCE)==null,"nothing lost or duplicated");
    }

    private static void backgroundOwnerRetainsPrecedenceAndProgress(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var owner=cascade("publication-owner");schedule.background(owner);
        Op current=schedule.pick(null,OPEN,HOLDING_RESOURCE);
        schedule.discover(discovery("waiting-request"));int ownerTicks=0;
        for(int tick=0;tick<60;tick++){
            var read=read("stream-"+tick);schedule.read(read);
            current=schedule.pick(current,OPEN,HOLDING_RESOURCE);
            if(current==owner){ownerTicks++;check(schedule.readPending()>0,"owner receives one advance under sustained reads");}
            else{check(current.read,"requested discovery cannot pass background resource owner");current=null;}
            check(schedule.backgroundPending()+(current==owner?1:0)==1,"owner counted exactly once");
        }
        check(ownerTicks==10,"resource owner advances once per five completed reads");
        schedule.cancelIf(op->op==owner,op->{});
    }

    private static void suspendedOwnerCancellationReleasesExactlyOnce(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var owner=cascade("stale-owner");var later=discovery("fresh-request");
        schedule.background(owner);check(schedule.pick(null,OPEN,HOLDING_RESOURCE)==owner,"owner starts");
        schedule.discover(later);var read=read("walk");schedule.read(read);
        check(schedule.pick(owner,OPEN,HOLDING_RESOURCE)==read,"owner suspends");
        List<Op> released=new ArrayList<>();
        schedule.cancelIf(op->op==owner,released::add);schedule.cancelIf(op->op==owner,released::add);
        check(released.equals(List.of(owner)),"suspended owner cancelled/released exactly once");
        check(schedule.backgroundPending()==0,"cancel removes reserved owner");
        check(schedule.pick(null,OPEN,PARKABLE)==later,"cancelled owner never resumes; other discovery may proceed");
    }

    private static void quotaExpiryGrantsOneDiscoveryTick(){
        var schedule=new SourceSchedule<Op>(1,op->op.read,op->op.background);
        var discovery=discovery("long-discovery");schedule.discover(discovery);
        schedule.read(read("first"));check(schedule.pick(null,OPEN,PARKABLE).read,"first read consumes quota");
        var next=read("next");schedule.read(next);
        check(schedule.pick(null,OPEN,PARKABLE)==discovery,"quota expiry grants discovery one tick");
        check(schedule.pick(discovery,OPEN,PARKABLE)==next,"discovery yields again after that one tick, not after completion");
    }

    /** Lane-wide cancellation: matched ops are removed from every lane, the action runs
     * exactly once per matched op, and unmatched ops keep their lane order. */
    private static void cancelIfRemovesMatchedOpsAcrossLanesExactlyOnce(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var keptRead=read("fresh-region");var staleDiscovery=discovery("stale-observation");var staleBackground=cascade("stale-hint");
        var keptDiscovery=discovery("fresh-ensure");
        schedule.read(keptRead);schedule.discover(staleDiscovery);schedule.discover(keptDiscovery);schedule.background(staleBackground);
        List<String> cancelled=new ArrayList<>();
        schedule.cancelIf(op->op.id.startsWith("stale"),op->cancelled.add(op.id));
        check(cancelled.equals(List.of("stale-observation","stale-hint")),"matched ops cancelled exactly once across lanes: "+cancelled);
        check(schedule.readPending()==1&&schedule.readLane.peekFirst()==keptRead,"unmatched read retained in order");
        check(schedule.discoveryPending()==1&&schedule.discoveryLane.peekFirst()==keptDiscovery,"unmatched discovery retained in order");
        check(schedule.backgroundPending()==0,"matched background op removed");
    }

    /** Edit-during-preemption: a parked observation invalidated by a source edit is removed
     * from its lane and cancelled before it can resume and publish stale source geometry. */
    private static void editDuringPreemptionInvalidatesParkedObservation(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var observation=discovery("stale-observation");var later=discovery("later-observation");
        schedule.discover(observation);schedule.discover(later);
        check(schedule.pick(null,OPEN,PARKABLE)==observation,"observation starts");
        var editRead=read("edit-region");schedule.read(editRead);
        check(schedule.pick(observation,OPEN,PARKABLE)==editRead,"observation parked by the preemption");
        check(schedule.discoveryPending()==2,"parked observation is still queued when the edit arrives");
        List<String> cancelled=new ArrayList<>();
        schedule.cancelIf(op->op.id.equals("stale-observation"),op->cancelled.add(op.id));
        check(cancelled.equals(List.of("stale-observation")),"parked observation invalidated");
        check(schedule.discoveryPending()==1&&schedule.discoveryLane.peekFirst()==later,"invalidated op removed; other discovery untouched");
        check(schedule.pick(null,OPEN,PARKABLE)==later,"remaining discovery resumes in place");
    }
}
