package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.ArrayList;
import java.util.List;

/** Combined production policy, including actual-start accounting used by SourceLandmarks.
 * Dispatch bounds are operation counts, not a promise about IO/inference wall-clock latency. */
public final class DiscoveryFairnessTest {
    private static final int CAPACITY=128;
    private static final long AGING=DiscoveryPrioritizer.AGING_TICKS;
    private static int checks;
    private record Op(String name,int tier,long enqueuedTick) implements DiscoveryPrioritizer.Scheduled {}
    private static void check(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        fullAgedHintBacklogYieldsToUrgentRequests();
        fullAgedHintBacklogYieldsToUrgentHints();
        bothQueuesProgressUnderSustainedUrgentChurn();
        backgroundOnlyNeverIdlesAndYieldsToNewArrival();
        selectionDoesNotSpendDispatchBudget();
        unreadyDiscoveryCannotSpendOrBlockUrgentTurn();
        clearResetsDispatchHistory();
        System.out.println("DiscoveryFairnessTest: "+checks+" checks passed");
    }
    private static DiscoveryPrioritizer backlog() {
        var queue=new DiscoveryPrioritizer(CAPACITY);
        for(int n=0;n<CAPACITY;n++)check(queue.offer("overworld",new BlockPoint(n*512L,64,0),
                DiscoveryPrioritizer.BACKFILL,List.of(),n),"full background backlog admitted");
        return queue;
    }
    /** Same activation sequence as Session: select, remove from its queue, record one start. */
    private static DiscoveryPrioritizer.Scheduled start(DiscoveryPrioritizer queue,
            List<Op> requests,long now,boolean ready) {
        var chosen=queue.next(requests,List.of(),now,AGING,ready);
        if(chosen instanceof DiscoveryPrioritizer.Hint hint)queue.remove(hint);
        else if(chosen!=null)check(requests.remove(chosen),"selected explicit request removed exactly once");
        if(chosen!=null)queue.dispatched(chosen);
        return chosen;
    }
    /** P2: all 128 old backfills used to precede READ/PLAYER even after urgent work aged. */
    private static void fullAgedHintBacklogYieldsToUrgentRequests() {
        for(int tier:new int[]{DiscoveryPrioritizer.READ,DiscoveryPrioritizer.PLAYER}) {
            for(long operationTicks:new long[]{1,AGING+50}) {
                var queue=backlog();var requests=new ArrayList<Op>();
                var urgent=new Op("urgent",tier,1000);requests.add(urgent);
                var first=start(queue,requests,1000,true);
                check(first instanceof DiscoveryPrioritizer.Hint,"aging still grants background one turn");
                check(queue.size()==CAPACITY-1,"only the active backfill was consumed");
                check(start(queue,requests,1000+operationTicks,true)==urgent,
                        "urgent read/player runs after at most one backfill, fresh or aged");
                check(queue.size()==CAPACITY-1 && requests.isEmpty(),"urgent dispatch preserves remaining locality");
                check(start(queue,requests,1000+2*operationTicks,true) instanceof DiscoveryPrioritizer.Hint,
                        "background resumes after urgent turn");
            }
        }
    }
    private static void fullAgedHintBacklogYieldsToUrgentHints() {
        var queue=backlog();var requests=new ArrayList<Op>();
        var near=new BlockPoint(-1,64,0);
        check(queue.offer("overworld",near,DiscoveryPrioritizer.PLAYER,List.of(),1000),
                "player hint admitted into full aged backlog");
        check(queue.size()==CAPACITY,"urgent hint displaces only one background slot");
        check(start(queue,requests,1000,true).tier()==DiscoveryPrioritizer.BACKFILL,"old locality gets its turn");
        var urgent=start(queue,requests,1250,true);
        check(urgent instanceof DiscoveryPrioritizer.Hint hint && hint.key().pos().equals(near),
                "aged urgent hint is found behind oldest background hint");
        check(queue.size()==CAPACITY-2,"only selected hints removed");
    }
    private static void bothQueuesProgressUnderSustainedUrgentChurn() {
        var queue=new DiscoveryPrioritizer(64);var requests=new ArrayList<Op>();
        for(int n=0;n<64;n++) {
            check(queue.offer("overworld",new BlockPoint(n,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),n*2),
                    "hint half of aged backlog admitted");
            requests.add(new Op("frontier-"+n,DiscoveryPrioritizer.FRONTIER,n*2+1));
        }
        int backgrounds=0,urgents=0,consecutive=0;long lastBackgroundAge=-1;
        for(int n=0;n<256;n++) {
            long now=1000L+n*250;
            requests.add(new Op("near-"+n,n%2==0?DiscoveryPrioritizer.READ:DiscoveryPrioritizer.PLAYER,now));
            var chosen=start(queue,requests,now,true);
            check(chosen!=null,"combined queue always dispatches eligible work");
            if(chosen.tier()>DiscoveryPrioritizer.PLAYER) {
                backgrounds++;consecutive++;
                check(chosen.enqueuedTick()>lastBackgroundAge,"oldest background progresses across both queues");
                lastBackgroundAge=chosen.enqueuedTick();
                check(consecutive<=DiscoveryPrioritizer.MAX_CONSECUTIVE_BACKGROUND_DISPATCHES,
                        "hint and explicit background share one consecutive dispatch bound");
            }else {urgents++;consecutive=0;}
        }
        check(backgrounds==128 && urgents==128,"both classes get every other turn despite serial long operations");
        check(queue.isEmpty() && requests.stream().noneMatch(op->op.tier()>DiscoveryPrioritizer.PLAYER),
                "entire retained background backlog drains despite continuous urgent arrivals");
        check(requests.size()==128,"waiting urgent operations are delayed, never dropped");
        while(!requests.isEmpty())check(start(queue,requests,100000,true).tier()<=DiscoveryPrioritizer.PLAYER,
                "remaining urgent requests also drain");
    }
    private static void backgroundOnlyNeverIdlesAndYieldsToNewArrival() {
        var queue=backlog();var requests=new ArrayList<Op>();
        for(int n=0;n<100;n++)check(start(queue,requests,1000+n,true).tier()==DiscoveryPrioritizer.BACKFILL,
                "no urgent waiter means unrestricted background progress");
        var urgent=new Op("new-read",DiscoveryPrioritizer.READ,1100);requests.add(urgent);
        check(start(queue,requests,1100,true)==urgent,"fresh urgent arrival gets next opportunity after background-only drain");
    }
    private static void selectionDoesNotSpendDispatchBudget() {
        var queue=backlog();var requests=new ArrayList<Op>();
        requests.add(new Op("read",DiscoveryPrioritizer.READ,1000));
        var selected=queue.next(requests,List.of(),1000,AGING,true);
        for(int n=0;n<5;n++)check(queue.next(requests,List.of(),1000,AGING,true)==selected,
                "repeated selection cannot change policy until an actual activation");
        check(queue.size()==CAPACITY && requests.size()==1,"selection neither consumes nor cancels work");
        check(start(queue,requests,1000,true)==selected,"real start consumes chosen background turn");
        var urgent=queue.next(requests,List.of(),1000,AGING,true);
        for(int n=0;n<5;n++)check(queue.next(requests,List.of(),1000,AGING,true)==urgent,
                "peeking urgent candidate cannot spend its reserved turn");
    }
    private static void unreadyDiscoveryCannotSpendOrBlockUrgentTurn() {
        var queue=backlog();var requests=new ArrayList<Op>();
        start(queue,requests,1000,true);
        var player=new Op("waiting-player",DiscoveryPrioritizer.PLAYER,1000);requests.add(player);
        check(start(queue,requests,1300,false)==null,"unready player discovery cannot start despite reserved urgent turn");
        check(queue.size()==CAPACITY-1 && requests.size()==1,"unready selection retains work and budget");
        check(start(queue,requests,1300,true)==player,"readiness returns reserved urgent opportunity before aged backlog");
        start(queue,requests,1300,true);
        var read=new Op("read-without-model",DiscoveryPrioritizer.READ,1300);requests.add(read);
        check(start(queue,requests,1301,false)==read,"read bypasses model-unready discovery and receives urgent turn");
    }
    private static void clearResetsDispatchHistory() {
        var queue=backlog();var requests=new ArrayList<Op>();
        start(queue,requests,1000,true);queue.clear();
        check(queue.isEmpty(),"clear drops all hints");
        queue.offer("nether",new BlockPoint(0,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),0);
        requests.add(new Op("read",DiscoveryPrioritizer.READ,1000));
        check(start(queue,requests,1000,true).tier()==DiscoveryPrioritizer.BACKFILL,
                "cleared scheduler does not inherit another session's background turn");
    }
}
