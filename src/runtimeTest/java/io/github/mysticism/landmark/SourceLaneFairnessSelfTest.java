package io.github.mysticism.landmark;

import java.util.*;
import java.util.function.Predicate;

/** Integration of the render-distance fairness contract with the actual async scheduler,
 * not a second unused lane picker. No game bootstrap or embedding service. */
public final class SourceLaneFairnessSelfTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private record Op(String id,boolean read,boolean background) {}
    private static SourceSchedule<Op> lane(){return new SourceSchedule<>(2,Op::read,Op::background);}
    private static final Predicate<Op> OPEN=op->true;
    private static Op read(String id){return new Op(id,true,false);}
    private static Op request(String id){return new Op(id,false,false);}
    private static Op hint(String id){return new Op(id,false,true);}

    private static void readinessNeverGatesReads() {
        var l=lane();var requested=request("landing");var background=hint("frontier");
        l.discover(requested);l.background(background);
        for(int n=0;n<6;n++){
            var read=read("unready-"+n);l.read(read);
            check(l.pick(null,op->false,OPEN)==read,"unready hints/requests never gate movement reads");
        }
        check(l.discoveryPending()==1&&l.backgroundPending()==1,"readiness preserves both lanes");
        check(l.pick(null,op->false,OPEN)==null,"gated discovery alone waits");
        var read=read("ready");l.read(read);
        check(l.pick(null,OPEN,OPEN)==requested,"accumulated read debt yields promptly when ready");
        check(l.pick(null,OPEN,OPEN)==read,"reads resume after one discovery tick");
    }

    private static void hintsAndRequestsShareUnderContinuousReads() {
        var l=lane();int lastGated=0,lastHint=0,lastRequest=0,hintCount=0,requestCount=0;
        for(int pick=1;pick<=60;pick++){
            l.read(read("view-read-"+pick));l.discover(request("landing-"+pick));l.background(hint("chunk-"+pick));
            Op selected=l.pick(null,OPEN,OPEN);
            if(selected.read)continue;
            check(pick-lastGated<=3,"every bounded read streak yields to actual discovery");lastGated=pick;
            if(selected.background){
                check(pick-lastHint<=6,"continuous requests cannot starve chunk/frontier generation");lastHint=pick;hintCount++;
            }else{
                check(pick-lastRequest<=6,"hints cannot starve landing requests");lastRequest=pick;requestCount++;
            }
        }
        check(hintCount==10&&requestCount==10,"both discovery lanes get ten bounded turns");
    }

    private static void waitingBackgroundWorkRotatesWithoutReadTraffic() {
        var l=lane();var slow=hint("pending-io");var other=hint("other-tile");var landing=request("landing");
        l.background(slow);
        check(l.pick(null,OPEN,OPEN)==slow,"background observation starts");
        l.background(other);l.discover(landing);
        check(l.pick(slow,OPEN,OPEN)==landing,"parkable pending background yields to requested work");
        check(l.pick(landing,OPEN,OPEN)==other,"pending background does not hide the next observed tile");
        check(l.pick(other,OPEN,OPEN)==landing,"requested FIFO head resumes");
        check(l.pick(null,OPEN,OPEN)==slow,"parked background operation is retained");
        check(l.pick(slow,op->!op.id.equals("pending-io"),OPEN)==other,"gated active background cannot hold its lane");
        check(l.backgroundPending()==1,"gated background is retained exactly once");
    }

    private static void resourceOwnerStillExcludesOtherDiscovery() {
        var l=lane();var owner=hint("publication");l.background(owner);
        check(l.pick(null,OPEN,OPEN)==owner,"owner starts");
        l.discover(request("landing"));l.background(hint("next-tile"));
        Op current=owner;int advances=0;
        for(int tick=0;tick<30;tick++){
            l.read(read("movement-"+tick));
            current=l.pick(current,OPEN,op->false);
            if(current==owner){advances++;}
            else{check(current.read,"no competing discovery passes a suspended resource owner");current=null;}
        }
        check(advances==10,"resource owner keeps one advance per quota, not uninterrupted ownership of ticks");
        var cancelled=new ArrayList<Op>();l.cancelIf(op->op==owner,cancelled::add);l.cancelIf(op->op==owner,cancelled::add);
        // Last iteration returned the owner; move it into the suspended slot before cancellation.
        if(current==owner){l.pick(current,OPEN,op->false);l.cancelIf(op->op==owner,cancelled::add);}
        check(cancelled.equals(List.of(owner)),"suspended resource owner released exactly once");
    }

    public static void main(String[] args){
        readinessNeverGatesReads();hintsAndRequestsShareUnderContinuousReads();waitingBackgroundWorkRotatesWithoutReadTraffic();resourceOwnerStillExcludesOtherDiscovery();
        System.out.println("SourceLaneFairnessSelfTest: "+checks+" checks passed");
    }
}
