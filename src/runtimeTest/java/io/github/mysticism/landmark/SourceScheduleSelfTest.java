package io.github.mysticism.landmark;

import java.util.*;
import java.util.function.Predicate;

/** Regressions for the production source-pipeline scheduler: wider source discovery must
 * never block movement-critical reads (terrain mesh/collision/support), never hold the
 * pipeline while gated on real embedding readiness, never starve under read pressure, and
 * never let hint-cascade background work delay requested walk/support/target answers. */
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

    public static void main(String[] args){
        readsPreemptRunningDiscovery();
        preemptedDiscoveryResumesInPlace();
        runningReadIsNeverPreempted();
        gatedHeadIsSkippedWithoutLoss();
        gatedDiscoveryNeverBlocksReads();
        backgroundNeverDelaysRequestedOrReads();
        movementLatencyBoundedAndDiscoveryNeverStarves();
        emptySchedulePicksNothing();
        System.out.println("SourceScheduleSelfTest: "+checks+" checks passed");
    }

    /** A discovery op occupying the pipeline is parked the moment a movement read waits. */
    private static void readsPreemptRunningDiscovery(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var ensure=discovery("ensure-1");
        schedule.discover(ensure);
        check(schedule.pick(null,OPEN)==ensure,"requested discovery starts when idle");
        var walkRead=read("walk-region");
        schedule.read(walkRead);
        check(schedule.pick(ensure,OPEN)==walkRead,"running discovery is parked as soon as a movement read waits");
        check(schedule.discoveryPending()==1,"parked discovery is retained, not cancelled");
        // The running read is never interrupted, even by more reads.
        var another=read("mesh-region");schedule.read(another);
        check(schedule.pick(walkRead,OPEN)==walkRead,"running read keeps the pipeline");
        check(schedule.pick(null,OPEN)==another,"queued read is served before parked discovery");
        check(schedule.pick(null,OPEN)==ensure,"parked discovery resumes after reads drain");
    }

    /** Parked discovery resumes in FIFO position; later offers cannot overtake it. */
    private static void preemptedDiscoveryResumesInPlace(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var first=discovery("first");
        schedule.discover(first);
        check(schedule.pick(null,OPEN)==first,"FIFO discovery start");
        schedule.discover(discovery("second"));
        var late=read("late-walk");schedule.read(late);
        check(schedule.pick(first,OPEN)==late,"read preempts");
        check(schedule.pick(null,OPEN)==first,"preempted op resumes ahead of later discovery offers");
        check(schedule.pick(null,OPEN).id.equals("second"),"later discovery offer keeps its queue position");
    }

    private static void runningReadIsNeverPreempted(){
        var schedule=new SourceSchedule<Op>(1,op->op.read,op->op.background);
        var running=read("running");
        schedule.read(running);
        check(schedule.pick(null,OPEN)==running,"read starts");
        var next=read("next");schedule.read(next);
        check(schedule.pick(running,OPEN)==running,"reads are never preempted by reads");
        check(schedule.pick(running,OPEN)==running,"reads are never preempted by waiting reads (repeat)");
    }

    /** An op gated on embedding readiness is skipped without losing its place: one stalled
     * op can never block later discovery in its own lane. */
    private static void gatedHeadIsSkippedWithoutLoss(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var gated=discovery("gated-on-engine");var later=discovery("later");
        schedule.discover(gated);schedule.discover(later);
        Predicate<Op> engineNotReady=op->!op.id.equals("gated-on-engine");
        check(schedule.pick(null,engineNotReady)==later,"gated head is skipped");
        check(schedule.pick(null,engineNotReady)==null,"gated op still holds its lane position");
        check(schedule.discoveryPending()==1,"the skipped op is retained");
        check(schedule.pick(null,OPEN)==gated,"once admissible, the gated op runs in its original position");
    }

    private static void gatedDiscoveryNeverBlocksReads(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        schedule.discover(discovery("gated-on-engine"));
        var walkRead=read("support-region");
        schedule.read(walkRead);
        Predicate<Op> engineNotReady=op->false;
        check(schedule.pick(null,engineNotReady)==walkRead,"movement reads run even when every discovery op is gated");
        check(schedule.pick(null,engineNotReady)==null,"gated op does not run while engine is unavailable");
    }

    /** Hint-cascade background work runs only after requested discovery, and parks behind
     * reads exactly like requested discovery. */
    private static void backgroundNeverDelaysRequestedOrReads(){
        var schedule=new SourceSchedule<Op>(5,op->op.read,op->op.background);
        var cascade=cascade("frontier-hint");var requested=discovery("walk-target");
        schedule.background(cascade);schedule.discover(requested);
        check(schedule.pick(null,OPEN)==requested,"requested discovery outranks background cascade");
        check(schedule.pick(null,OPEN)==cascade,"background cascade runs only when requested work is done");
        var walkRead=read("walk-probe");
        schedule.read(walkRead);
        check(schedule.pick(cascade,OPEN)==walkRead,"background op is parked the moment a read waits");
        check(schedule.pick(null,OPEN)==cascade,"parked background op resumes in place");
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
            current=schedule.pick(current,OPEN);
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
            var op=schedule.pick(current,OPEN);
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
        for(int i=0;i<3;i++)check(schedule.pick(null,OPEN)==null,"empty lanes pick nothing");
        var op=discovery("single");
        schedule.discover(op);
        check(schedule.pick(null,OPEN)==op,"idle schedule starts offered discovery");
        check(schedule.readPending()==0&&schedule.discoveryPending()==0&&schedule.backgroundPending()==0,"lane counters track reality");
    }
}
