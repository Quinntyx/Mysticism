package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.ArrayList;
import java.util.List;

/** Exercises the exact combined dispatch/admission policy used by SourceLandmarks.Session.
 * No Minecraft bootstrap, dummy embeddings, model weights or external service required. */
public final class DiscoveryDispatchTest {
    private static final BlockPoint AT=new BlockPoint(0,64,0);
    private static int checks;
    private record Op(String name,int tier,long enqueuedTick) implements DiscoveryPrioritizer.Scheduled {}
    private static void check(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        hintsCompeteWithExplicitRequests();
        hintTierAndAgeSurviveUpgrade();
        agedHintsSurvivePlayerFlood();
        sustainedReadsStillDispatchWideLocality();
        modelReadinessCannotHeadBlockReads();
        agedSelectionUsesActualOldestNotPriorityOrder();
        readsRemainFifoBeforePlayerDiscovery();
        agingOverridesDistanceWithinTier();
        unsortedEvictionFindsActualLowestTier();
        requestFloodPreservesAnAgedBackgroundSlot();
        System.out.println("DiscoveryDispatchTest: "+checks+" checks passed");
    }
    private static void hintsCompeteWithExplicitRequests() {
        var hints=new DiscoveryPrioritizer(8);
        hints.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,List.of(),10);
        var frontier=new Op("growth",DiscoveryPrioritizer.FRONTIER,0);
        var requests=new ArrayList<DiscoveryPrioritizer.Scheduled>();requests.add(frontier);
        var next=hints.next(requests,List.of(),20,200,true);
        check(next instanceof DiscoveryPrioritizer.Hint,"player hint preempts queued explicit frontier work, not just other hints");
        check(next.tier()==DiscoveryPrioritizer.PLAYER,"dispatch retains player priority");
        hints.remove((DiscoveryPrioritizer.Hint)next);
        check(hints.next(requests,List.of(),20,200,true)==frontier,"explicit work remains queued after hint selection");
    }
    private static void hintTierAndAgeSurviveUpgrade() {
        var hints=new DiscoveryPrioritizer(4);
        hints.offer("overworld",AT,DiscoveryPrioritizer.BACKFILL,List.of(),5);
        hints.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,List.of(),190);
        hints.offer("overworld",AT,DiscoveryPrioritizer.FRONTIER,List.of(),199);
        var hint=hints.peek(List.of(),205,200);
        check(hint.tier()==DiscoveryPrioritizer.PLAYER,"upgrade retains real dispatch tier");
        check(hint.enqueuedTick()==5,"duplicate/upgrade cannot reset hint age");
        check(hints.size()==1,"upgrade cannot consume another bounded slot");
    }
    private static void agedHintsSurvivePlayerFlood() {
        var hints=new DiscoveryPrioritizer(3);
        hints.offer("overworld",AT,DiscoveryPrioritizer.BACKFILL,List.of(),0);
        hints.offer("overworld",new BlockPoint(100,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),1);
        hints.offer("overworld",new BlockPoint(200,64,0),DiscoveryPrioritizer.BACKFILL,List.of(),2);
        for(int tick=200;tick<260;tick++)hints.offer("overworld",new BlockPoint(tick,64,0),DiscoveryPrioritizer.PLAYER,List.of(),tick);
        var next=hints.peek(List.of(),260,200);
        check(next.key().pos().equals(AT),"one oldest aged locality hint survives a full-queue player flood");
        check(hints.size()==3,"fairness reservation cannot increase capacity");
    }
    private static void sustainedReadsStillDispatchWideLocality() {
        var hints=new DiscoveryPrioritizer(8);
        hints.offer("overworld",AT,DiscoveryPrioritizer.BACKFILL,List.of(),0);
        var requests=new ArrayList<DiscoveryPrioritizer.Scheduled>();
        boolean dispatched=false;
        for(int tick=1;tick<=201;tick++) {
            var read=new Op("near-read-"+tick,DiscoveryPrioritizer.READ,tick);
            requests.add(DiscoveryPrioritizer.insertionIndex(requests,read),read);
            var next=hints.next(requests,List.of(),tick,200,true);
            if(next instanceof DiscoveryPrioritizer.Hint hint) {
                check(tick==200,"wide locality progresses at the aging boundary despite endless reads");
                hints.remove(hint);dispatched=true;
            }else requests.remove(next);
        }
        check(dispatched,"background hint really dispatches under sustained read churn");
        check(hints.isEmpty(),"dispatched hint is removed exactly once");
        check(requests.size()==1,"one read was delayed, not lost, by fairness dispatch");
    }
    private static void modelReadinessCannotHeadBlockReads() {
        var hints=new DiscoveryPrioritizer(8);
        hints.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,List.of(),0);
        var waiting=new Op("aged-discovery",DiscoveryPrioritizer.PLAYER,0);
        var read=new Op("read",DiscoveryPrioritizer.READ,300);
        var requests=List.of(waiting,read);
        check(hints.next(requests,List.of(),301,200,false)==read,"unready aged discovery excluded before selecting read");
        check(hints.next(List.of(waiting),List.of(),301,200,false)==null,"no discovery starts before real embedding/index readiness");
        check(hints.size()==1,"waiting hint not consumed when model unavailable");
        check(hints.next(requests,List.of(),301,200,true)==waiting,"readiness restores oldest aged explicit discovery eligibility");
    }
    private static void agedSelectionUsesActualOldestNotPriorityOrder() {
        var requests=List.of(new Op("aged-read",DiscoveryPrioritizer.READ,100),
                new Op("older-frontier",DiscoveryPrioritizer.FRONTIER,10),
                new Op("oldest-backfill",DiscoveryPrioritizer.BACKFILL,0));
        check(DiscoveryPrioritizer.selectNext(requests,400,200)==requests.get(2),"oldest aged entry wins even in a priority-sorted list");
    }
    private static void readsRemainFifoBeforePlayerDiscovery() {
        var requests=new ArrayList<Op>();
        var player=new Op("player",DiscoveryPrioritizer.PLAYER,0);
        var first=new Op("first-read",DiscoveryPrioritizer.READ,1);
        var second=new Op("second-read",DiscoveryPrioritizer.READ,2);
        requests.add(player);
        requests.add(DiscoveryPrioritizer.insertionIndex(requests,first),first);
        requests.add(DiscoveryPrioritizer.insertionIndex(requests,second),second);
        check(requests.equals(List.of(first,second,player)),"new reads insert after older reads, before player discovery");
        check(DiscoveryPrioritizer.selectNext(requests,3,200)==first,"same-tier reads dispatch FIFO rather than LIFO");
    }
    private static void agingOverridesDistanceWithinTier() {
        var hints=new DiscoveryPrioritizer(2);
        var observers=List.of(new DiscoveryPrioritizer.Anchor("overworld",AT));
        var far=new BlockPoint(100000,64,0);
        hints.offer("overworld",far,DiscoveryPrioritizer.BACKFILL,observers,0);
        hints.offer("overworld",AT,DiscoveryPrioritizer.BACKFILL,observers,199);
        check(hints.peek(observers,199,200).key().pos().equals(AT),"nearest visible locality first before aging");
        check(hints.peek(observers,200,200).key().pos().equals(far),"old far locality cannot starve behind fresh near hints");
        check(!hints.offer("overworld",new BlockPoint(1,64,0),DiscoveryPrioritizer.BACKFILL,observers,200),"aged far hint protected from same-tier replacement; nearer young hint kept too");
        check(hints.size()==2,"aged protection remains bounded");
    }
    private static void unsortedEvictionFindsActualLowestTier() {
        var requests=List.of(new Op("backfill",DiscoveryPrioritizer.BACKFILL,0),new Op("frontier",DiscoveryPrioritizer.FRONTIER,1));
        check(DiscoveryPrioritizer.evictionVictim(requests,new Op("player",DiscoveryPrioritizer.PLAYER,2))==0,"eviction finds lowest tier independent of list ordering");
    }
    private static void requestFloodPreservesAnAgedBackgroundSlot() {
        var requests=new ArrayList<Op>();
        var oldest=new Op("oldest-backfill",DiscoveryPrioritizer.BACKFILL,0);
        requests.add(oldest);requests.add(new Op("growth",DiscoveryPrioritizer.FRONTIER,1));
        var read=new Op("incoming-read",DiscoveryPrioritizer.READ,200);
        check(DiscoveryPrioritizer.evictionVictim(requests,read,200,200)==1,"admission reserves one aged background slot");
        requests.remove(1);requests.add(read);
        check(DiscoveryPrioritizer.selectNext(requests,200,200)==oldest,"protected aged request actually runs before fresh read");
    }
}
