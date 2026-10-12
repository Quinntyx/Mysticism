package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.ArrayList;
import java.util.List;

/** Discovery-priority regressions: player-near terrain discovery must not be starved by
 * frontier growth or wide-locality backfill, and background growth must not starve forever.
 * Pure scheduler checks; the native pipeline wires these through SourceLandmarks.Session. */
public final class DiscoveryPriorityTest {
    private DiscoveryPriorityTest() {}
    private static final BlockPoint AT=new BlockPoint(0,64,0);
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++;
        if(!condition)throw new AssertionError(message);
    }
    private static List<DiscoveryPrioritizer.Anchor> anchor(String dimension,long x,long y,long z) {
        return List.of(new DiscoveryPrioritizer.Anchor(dimension,new BlockPoint(x,y,z)));
    }
    public static void main(String[] args) {
        playerHintPreemptsFloodedFrontierQueue();
        hintUpgradeNeverDowngrades();
        hintDeduplicatesByPosition();
        fullQueueNeverEvictsHigherPriorityWork();
        fullQueueEvictsFarthestLowerPriorityEntry();
        sameTierEvictionKeepsNearerHint();
        selectionPrefersNearestObserverWithinTier();
        selectionWithoutObserversKeepsOldestFirst();
        removeDimensionDropsOnlyThatDimension();
        insertionKeepsReadsAheadAndOrdersByPriority();
        requestQueueEvictionFindsLowestPriorityVictim();
        agingPromotesLongQueuedBackgroundRequests();
        capacityIsBounded();
        System.out.println("DiscoveryPriorityTest: "+checks+" checks passed");
    }
    /** The old FIFO hint set dropped or deferred fresh player hints behind 128 stale frontier hints. */
    private static void playerHintPreemptsFloodedFrontierQueue() {
        var queue=new DiscoveryPrioritizer(128);
        for(int n=0;n<128;n++)check(queue.offer("overworld",new BlockPoint(n*512,64,n*512),DiscoveryPrioritizer.FRONTIER,List.of()),"frontier hint accepted");
        check(queue.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,List.of()),"player hint accepted on a full frontier queue");
        check(queue.size()==128,"player hint displaced exactly one stale frontier hint");
        var first=queue.poll(List.of());
        check(first!=null && first.dimension().equals("overworld") && first.pos().equals(AT),"player hint is observed before every queued frontier hint");
    }
    private static void hintUpgradeNeverDowngrades() {
        var queue=new DiscoveryPrioritizer(8);
        check(queue.offer("overworld",AT,DiscoveryPrioritizer.FRONTIER,List.of()),"frontier hint accepted");
        check(queue.size()==1,"same position deduplicates");
        check(queue.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,List.of()),"player re-request accepted");
        check(queue.size()==1,"upgrade keeps a single entry");
        check(queue.offer("overworld",AT,DiscoveryPrioritizer.BACKFILL,List.of()),"lower priority re-request accepted");
        check(queue.size()==1,"no downgrade entry");
        var first=queue.poll(anchor("overworld",0,64,1));
        check(first!=null && first.pos().equals(AT),"upgraded entry still observed");
        check(queue.isEmpty(),"single entry consumed once");
    }
    private static void hintDeduplicatesByPosition() {
        var queue=new DiscoveryPrioritizer(8);
        for(int n=0;n<16;n++)check(queue.offer("overworld",AT,DiscoveryPrioritizer.FRONTIER,List.of()),"duplicate accepted");
        check(queue.size()==1,"repeated identical hints collapse to one entry");
    }
    private static void fullQueueNeverEvictsHigherPriorityWork() {
        var queue=new DiscoveryPrioritizer(4);
        for(int n=0;n<4;n++)check(queue.offer("overworld",new BlockPoint(n,64,n),DiscoveryPrioritizer.PLAYER,List.of()),"player hint accepted");
        check(!queue.offer("overworld",new BlockPoint(1000,64,1000),DiscoveryPrioritizer.FRONTIER,List.of()),"frontier hint rejected when only player work is queued");
        check(!queue.offer("overworld",new BlockPoint(1000,64,1000),DiscoveryPrioritizer.BACKFILL,List.of()),"backfill hint rejected when only player work is queued");
        check(queue.size()==4,"player hints are never evicted for lower-priority work");
        check(queue.poll(anchor("overworld",0,64,0)).pos().equals(new BlockPoint(0,64,0)),"queued player hints intact");
    }
    private static void fullQueueEvictsFarthestLowerPriorityEntry() {
        var anchors=anchor("overworld",0,64,0);
        var queue=new DiscoveryPrioritizer(3);
        check(queue.offer("overworld",new BlockPoint(10,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"near frontier hint accepted");
        check(queue.offer("overworld",new BlockPoint(100000,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"far frontier hint accepted");
        check(queue.offer("overworld",new BlockPoint(200000,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"farther frontier hint accepted");
        check(queue.offer("overworld",new BlockPoint(11,64,0),DiscoveryPrioritizer.PLAYER,anchors),"player hint accepted by evicting frontier work");
        check(queue.size()==3,"capacity retained");
        var first=queue.poll(anchors);
        check(first.pos().equals(new BlockPoint(11,64,0)),"player hint observed first");
        var second=queue.poll(anchors);
        check(second.pos().equals(new BlockPoint(10,64,0)),"nearest frontier hint retained");
        var third=queue.poll(anchors);
        check(third.pos().equals(new BlockPoint(100000,64,0)),"a far frontier hint was evicted, not a near one");
        check(queue.isEmpty(),"exactly one frontier hint was evicted");
    }
    private static void sameTierEvictionKeepsNearerHint() {
        var anchors=anchor("overworld",0,64,0);
        var queue=new DiscoveryPrioritizer(2);
        check(queue.offer("overworld",new BlockPoint(10,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"near hint accepted");
        check(queue.offer("overworld",new BlockPoint(100000,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"far hint accepted");
        // Without observers, distances tie at infinity and the oldest same-tier entry yields (FIFO).
        check(queue.offer("overworld",new BlockPoint(200000,64,0),DiscoveryPrioritizer.FRONTIER,List.of()),"hint accepted without observers");
        check(queue.size()==2,"capacity retained without observers");
        check(queue.poll(List.of()).pos().equals(new BlockPoint(100000,64,0)),"the surviving older entry is observed first");
        check(queue.poll(List.of()).pos().equals(new BlockPoint(200000,64,0)),"the fresh entry follows");
        // With an observer, a full same-tier queue keeps the nearer hint and yields the farther one.
        var queue2=new DiscoveryPrioritizer(2);
        check(queue2.offer("overworld",new BlockPoint(10,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"near hint accepted");
        check(queue2.offer("overworld",new BlockPoint(100000,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"far hint accepted");
        check(queue2.offer("overworld",new BlockPoint(20,64,0),DiscoveryPrioritizer.FRONTIER,anchors),"near hint accepted over the far hint");
        check(queue2.poll(anchors).pos().equals(new BlockPoint(10,64,0)),"nearest retained hint observed first");
        check(queue2.poll(anchors).pos().equals(new BlockPoint(20,64,0)),"the other near hint follows");
    }
    private static void selectionPrefersNearestObserverWithinTier() {
        var anchors=anchor("overworld",1000,64,1000);
        var queue=new DiscoveryPrioritizer(8);
        check(queue.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,anchors),"distant player hint accepted");
        check(queue.offer("overworld",new BlockPoint(999,64,1000),DiscoveryPrioritizer.PLAYER,anchors),"near player hint accepted");
        var first=queue.poll(anchors);
        check(first.pos().equals(new BlockPoint(999,64,1000)),"the hint nearest the observer is observed first within a tier");
        // Observers in another dimension do not shorten cross-dimension distances.
        var queue2=new DiscoveryPrioritizer(8);
        check(queue2.offer("nether",AT,DiscoveryPrioritizer.PLAYER,List.of()),"nether hint accepted");
        check(queue2.offer("overworld",AT,DiscoveryPrioritizer.PLAYER,List.of()),"overworld hint accepted");
        check(queue2.poll(anchor("overworld",0,64,1)).dimension().equals("overworld"),"anchors only prioritize their own dimension");
    }
    private static void selectionWithoutObserversKeepsOldestFirst() {
        var queue=new DiscoveryPrioritizer(8);
        check(queue.offer("overworld",new BlockPoint(1,64,1),DiscoveryPrioritizer.FRONTIER,List.of()),"first hint accepted");
        check(queue.offer("overworld",new BlockPoint(2,64,2),DiscoveryPrioritizer.FRONTIER,List.of()),"second hint accepted");
        check(queue.poll(List.of()).pos().equals(new BlockPoint(1,64,1)),"oldest hint observed first without observers");
        check(queue.poll(List.of()).pos().equals(new BlockPoint(2,64,2)),"second hint observed next");
        check(queue.poll(List.of())==null,"empty queue observes nothing");
    }
    private static void removeDimensionDropsOnlyThatDimension() {
        var queue=new DiscoveryPrioritizer(8);
        check(queue.offer("overworld",AT,DiscoveryPrioritizer.FRONTIER,List.of()),"overworld hint accepted");
        check(queue.offer("nether",AT,DiscoveryPrioritizer.FRONTIER,List.of()),"nether hint accepted");
        queue.removeDimension("overworld");
        check(queue.size()==1,"only the requested dimension was dropped");
        check(queue.poll(List.of()).dimension().equals("nether"),"remaining hint survives");
    }
    private static final class Op implements DiscoveryPrioritizer.Scheduled {
        final String name;final int tier;final long enqueued;
        Op(String name,int tier,long enqueued){this.name=name;this.tier=tier;this.enqueued=enqueued;}
        @Override public int tier(){return tier;}
        @Override public long enqueuedTick(){return enqueued;}
        @Override public String toString(){return name;}
    }
    /** Read observations stay ahead of every discovery request; player-blocking requests jump ahead of queued growth. */
    private static void insertionKeepsReadsAheadAndOrdersByPriority() {
        List<DiscoveryPrioritizer.Scheduled> ops=new ArrayList<>();
        ops.add(new Op("read",DiscoveryPrioritizer.READ,0));
        ops.add(new Op("frontier-ensure",DiscoveryPrioritizer.FRONTIER,1));
        check(DiscoveryPrioritizer.insertionIndex(ops,new Op("player-ensure",DiscoveryPrioritizer.PLAYER,2))==1,"player request inserts after reads and before frontier work");
        ops.add(1,new Op("player-ensure",DiscoveryPrioritizer.PLAYER,2));
        check(DiscoveryPrioritizer.insertionIndex(ops,new Op("second-player",DiscoveryPrioritizer.PLAYER,3))==2,"equal priorities keep FIFO order");
        check(DiscoveryPrioritizer.insertionIndex(ops,new Op("backfill-ensure",DiscoveryPrioritizer.BACKFILL,4))==ops.size(),"lower priority work appends behind everything");
    }
    /** A full request queue must displace queued lower-priority work instead of rejecting player-blocking requests. */
    private static void requestQueueEvictionFindsLowestPriorityVictim() {
        List<DiscoveryPrioritizer.Scheduled> ops=new ArrayList<>();
        ops.add(new Op("read",DiscoveryPrioritizer.READ,0));
        ops.add(new Op("frontier-1",DiscoveryPrioritizer.FRONTIER,1));
        ops.add(new Op("frontier-2",DiscoveryPrioritizer.FRONTIER,2));
        ops.add(new Op("backfill",DiscoveryPrioritizer.BACKFILL,3));
        check(DiscoveryPrioritizer.evictionVictim(ops,new Op("player-ensure",DiscoveryPrioritizer.PLAYER,4))==3,"the newest lowest-priority entry is displaced");
        check(DiscoveryPrioritizer.evictionVictim(ops,new Op("frontier-3",DiscoveryPrioritizer.FRONTIER,4))==3,"a lower-priority entry is displaced for frontier work too");
        var playerOnly=new ArrayList<DiscoveryPrioritizer.Scheduled>();
        playerOnly.add(new Op("read",DiscoveryPrioritizer.READ,0));
        playerOnly.add(new Op("player-1",DiscoveryPrioritizer.PLAYER,1));
        check(DiscoveryPrioritizer.evictionVictim(playerOnly,new Op("player-2",DiscoveryPrioritizer.PLAYER,2))<0,"equally urgent work is rejected rather than cancelled");
    }
    /** Background growth queued behind sustained player churn must still run after aging. */
    private static void agingPromotesLongQueuedBackgroundRequests() {
        List<DiscoveryPrioritizer.Scheduled> ops=new ArrayList<>();
        ops.add(new Op("young-player",DiscoveryPrioritizer.PLAYER,900));
        ops.add(new Op("old-frontier",DiscoveryPrioritizer.FRONTIER,0));
        var chosen=DiscoveryPrioritizer.selectNext(ops,1000,1200);
        check(chosen.toString().equals("young-player"),"young head runs before the aging threshold");
        chosen=DiscoveryPrioritizer.selectNext(ops,1300,1200);
        check(chosen.toString().equals("old-frontier"),"an aged background request is promoted ahead of newer work");
        var empty=new ArrayList<DiscoveryPrioritizer.Scheduled>();
        check(DiscoveryPrioritizer.selectNext(empty,10,1200)==null,"an empty queue selects nothing");
    }
    private static void capacityIsBounded() {
        var queue=new DiscoveryPrioritizer(5);
        for(int n=0;n<5;n++)check(queue.offer("overworld",new BlockPoint(n,64,n),DiscoveryPrioritizer.FRONTIER,List.of()),"hint "+n+" accepted");
        for(int n=5;n<20;n++)queue.offer("overworld",new BlockPoint(n,64,n),DiscoveryPrioritizer.FRONTIER,List.of());
        check(queue.size()==5,"queue never exceeds capacity");
        check(queue.poll(List.of())!=null && queue.poll(List.of())!=null,"queue remains consumable");
    }
}
