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

    public static void main(String[] args) {
        readsKeepPriorityAndBypassReadiness();discoveryNeverStarvesEnsure();yieldAlternatesUnderLoad();notReadyEnsureDoesNotConsumeTheYield();
        System.out.println("SourceLaneFairnessSelfTest: "+checks+" checks passed");
    }
}
