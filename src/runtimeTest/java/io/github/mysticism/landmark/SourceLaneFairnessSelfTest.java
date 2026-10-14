package io.github.mysticism.landmark;

import java.util.*;
import java.util.function.Predicate;

/** Fair-lane regressions for the serialized source pipeline: continued discovery reads must
 * never starve ownership generation or landing-target preparation (Ensure/Transfer work), while
 * terrain/mesh Reads keep priority and never wait for embedding readiness. Pure, no Minecraft
 * boot, no JUnit. */
public final class SourceLaneFairnessSelfTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}

    static final class Op { final String id;Op(String id){this.id=id;} @Override public String toString(){return id;} }
    static final Predicate<Op> READ=op->op.id.startsWith("read");
    static SourceLandmarks.FairLane<Op> lane(){return new SourceLandmarks.FairLane<>(2);}

    private static void readsKeepPriorityAndBypassReadiness() {
        var l=lane();var q=new ArrayDeque<Op>();
        check(l.pick(q,READ,false)==null,"empty queue starts nothing");
        var ensure=new Op("ensure");q.add(ensure);
        check(l.pick(q,READ,false)==null,"readiness-gated head with no reads still waits");
        q.addFirst(new Op("read-1"));
        check(l.pick(q,READ,false).id.equals("read-1"),"reads start without embedding readiness");
        q.add(new Op("read-2"));q.add(new Op("read-3"));
        check(READ.test(l.pick(q,READ,false)),"a gated non-read head never blocks waiting reads");
        check(READ.test(l.pick(q,READ,false)),"remaining reads keep running past the gated head");
        check(l.pick(q,READ,false)==null,"a gated non-read head alone still waits");
        q.addFirst(new Op("ensure-2"));q.addFirst(new Op("ensure-1"));
        check(l.pick(q,READ,true).id.equals("ensure-1"),"ready non-read requests run in FIFO order");
        check(l.pick(q,READ,true).id.equals("ensure-2"),"second ready non-read follows");
        var read=new Op("read-9");q.addFirst(read);
        check(l.pick(q,READ,true)==read,"reads keep priority over later non-read offers");
    }

    private static void discoveryNeverStarvesEnsure() {
        // Simulate continued discovery: a fresh region read is offered before every pick, forever.
        var l=lane();var q=new ArrayDeque<Op>();
        q.add(new Op("ensure-ownership"));
        int picks=0,issued=0;
        while(picks<64) {
            q.addFirst(new Op("read-discovery-"+issued++));
            Op picked=l.pick(q,READ,true);picks++;
            if(picked!=null && !READ.test(picked)) {
                check(picked.id.equals("ensure-ownership"),"the waiting Ensure runs while discovery remains active");
                check(picks<=3,"Ensure runs after only the bounded read streak ("+picks+" picks)");
                return;
            }
        }
        check(false,"Ensure starved behind continual discovery reads");
    }

    private static void yieldAlternatesUnderLoad() {
        var l=lane();var q=new ArrayDeque<Op>();
        q.add(new Op("ensure-a"));
        for(int n=0;n<8;n++)q.addFirst(new Op("read-"+n)); // discovery reads enter at the head like production offer()
        // Reads first (streak 1, 2), then the Ensure, then reads resume immediately.
        check(READ.test(l.pick(q,READ,true)),"read 1 under load");
        check(READ.test(l.pick(q,READ,true)),"read 2 under load");
        check(l.pick(q,READ,true).id.equals("ensure-a"),"the Ensure is yielded to after the read streak");
        check(READ.test(l.pick(q,READ,true)),"reads resume immediately after the yield");
        // With a fresh Ensure waiting behind continued read pressure, the lane yields again promptly.
        q.add(new Op("ensure-b"));
        Op picked=null;int reads=0;
        for(int n=0;n<4;n++){picked=l.pick(q,READ,true);if(!READ.test(picked))break;reads++;}
        check(picked!=null && picked.id.equals("ensure-b"),"the fresh Ensure is served behind continued read pressure");
        check(reads<=2,"the yield comes within the bounded read streak");
    }

    private static void notReadyEnsureDoesNotConsumeTheYield() {
        var l=lane();var q=new ArrayDeque<Op>();
        q.add(new Op("ensure-landing"));
        for(int n=0;n<6;n++)q.addFirst(new Op("read-"+n));
        // Ensure not ready: reads run without limit and the streak must not delay a later-ready Ensure.
        for(int n=0;n<6;n++)check(READ.test(l.pick(q,READ,false)),"reads run while the Ensure is gated");
        check(l.pick(q,READ,true).id.equals("ensure-landing"),"a newly ready Ensure runs on the very next pick");
        q.addFirst(new Op("read-after"));
        check(READ.test(l.pick(q,READ,true)),"reads resume after the gated Ensure completes");
    }

    private static void generationHintsShareTheYield() {
        var l=lane();var q=new ArrayDeque<Op>();var hints=new ArrayDeque<Op>();
        for(int n=0;n<12;n++)hints.add(new Op("hint-frontier-"+n));
        for(int n=0;n<2;n++) {
            q.addFirst(new Op("read-not-ready-"+n));
            check(READ.test(l.pick(q,READ,false,true,hints::removeFirst)),"unready hints never gate discovery reads");
        }
        check(hints.size()==12,"unready hints remain queued, without creating Ensure operations");
        check(l.pick(q,READ,false,true,hints::removeFirst)==null,"hints alone await real embedding readiness");
        q.addFirst(new Op("read-ready"));
        check(l.pick(q,READ,true,true,hints::removeFirst).id.equals("hint-frontier-0"),"accumulated read streak immediately yields to newly ready hints");
        // Keep replenishing both requests and hints as production chunk-load/frontier traffic does.
        int lastGated=0,lastHint=0,lastRequest=0,hintCount=0,requestCount=0;
        for(int pick=1;pick<=60;pick++) {
            q.addFirst(new Op("read-discovery-"+pick));
            q.addLast(new Op("ensure-landing-"+pick));
            hints.addLast(new Op("hint-chunk-"+pick));
            Op selected=l.pick(q,READ,true,!hints.isEmpty(),hints::removeFirst);
            if(READ.test(selected))continue;
            check(pick-lastGated<=3,"every bounded read streak yields even with hints in a separate queue");lastGated=pick;
            if(selected.id.startsWith("hint")) {
                check(pick-lastHint<=6,"explicit Ensure traffic cannot starve generation hints");lastHint=pick;hintCount++;
            } else {
                check(pick-lastRequest<=6,"generation hints cannot starve explicit landing/ownership requests");lastRequest=pick;requestCount++;
            }
        }
        check(hintCount==10 && requestCount==10,"both ready gated lanes share sustained discovery yields");
        var alone=lane();q.clear();hints.clear();hints.add(new Op("hint-alone"));
        check(alone.pick(q,READ,true,true,hints::removeFirst).id.equals("hint-alone"),"ready hint starts without queued requests");
        check(alone.pick(q,READ,true,false,hints::removeFirst)==null,"empty hint supplier is never called");
    }

    public static void main(String[] args) {
        readsKeepPriorityAndBypassReadiness();discoveryNeverStarvesEnsure();yieldAlternatesUnderLoad();notReadyEnsureDoesNotConsumeTheYield();generationHintsShareTheYield();
        System.out.println("SourceLaneFairnessSelfTest: "+checks+" checks passed");
    }
}
