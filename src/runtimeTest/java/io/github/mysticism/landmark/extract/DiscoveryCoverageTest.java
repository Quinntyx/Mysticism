package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import io.github.mysticism.landmark.Bounds;
import java.util.List;

/** Tests the same bounded coverage/admission/sweep policy used by the live Session. */
public final class DiscoveryCoverageTest {
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    private static final BlockPoint AT=new BlockPoint(8,65,8);
    public static void main(String[] args) {
        invalidationUsesObservationFootprint();
        coverageRemainsBoundedAndSessionLocal();
        slowDiscoveryReachesBeyondHintCapacity(false);
        slowDiscoveryReachesBeyondHintCapacity(true);
        System.out.println("DiscoveryCoverageTest: "+checks+" checks passed");
    }
    private static void invalidationUsesObservationFootprint() {
        var coverage=new DiscoveryCoverage(8);
        coverage.sampled("overworld",AT);coverage.sampled("nether",AT);
        var distant=new BlockPoint(128,65,128);coverage.sampled("overworld",distant);
        check(coverage.contains("overworld",AT),"completed background observation suppresses redundant sample");
        coverage.invalidate("overworld",new Bounds(21,0,0,22,256,16));
        check(coverage.contains("overworld",AT),"nonintersecting boundary does not invalidate sample");
        coverage.invalidate("overworld",new Bounds(16,0,0,32,256,16));
        check(!coverage.contains("overworld",AT),"new neighboring chunk retries a formerly partial observation");
        check(coverage.contains("nether",AT),"other dimension's coverage not invalidated");
        check(coverage.contains("overworld",distant),"distant coverage not invalidated");
        coverage.sampled("overworld",AT);
        coverage.invalidate("overworld",new Bounds(8,64,8,9,65,9));
        check(!coverage.contains("overworld",AT),"near source edit retries background sample independently of timer");
    }
    private static void coverageRemainsBoundedAndSessionLocal() {
        var coverage=new DiscoveryCoverage(2);
        coverage.sampled("overworld",AT);coverage.sampled("nether",AT);
        coverage.sampled("nether",AT);
        check(coverage.size()==2,"duplicate completions consume one coverage slot");
        coverage.sampled("end",AT);
        check(coverage.size()==2 && !coverage.contains("overworld",AT),"bounded memory evicts oldest completed sample, allowing rediscovery");
        coverage.removeDimension("nether");check(coverage.size()==1,"dimension unload clears its coverage");
        coverage.clear();check(coverage.size()==0,"server stop clears coverage");
    }
    private static void slowDiscoveryReachesBeyondHintCapacity(boolean ineligibleNear) {
        var sweep=new VisibleDiscoverySweep();var hints=new DiscoveryPrioritizer(128);var coverage=new DiscoveryCoverage(16384);
        var observer=new DiscoveryPrioritizer.Anchor("overworld",AT);var anchors=List.of(observer);
        DiscoveryPrioritizer.Hint active=null;int finishedAt=0,expected=17*17,ineligible=0;
        // 100 tick completions take much longer than the old 1200 tick cooldown to finish
        // a visible neighborhood. Reseeding near rings must not reset progress indefinitely.
        // In the second run the inner 5x5 has no eligible ownership mask (prepare returns
        // empty, not an inference error); wider usable terrain must still get its turn.
        for(int tick=0;tick<40000 && coverage.size()<expected;tick++) {
            for(int n=0;n<4;n++) {
                var sample=sweep.next(observer,8);var pos=new BlockPoint(sample.chunkX()*16L+8,65,sample.chunkZ()*16L+8);
                if(!coverage.contains(sample.dimension(),pos) && (active==null || !active.key().pos().equals(pos)))
                    hints.offer(sample.dimension(),pos,DiscoveryPrioritizer.BACKFILL,anchors,tick);
            }
            if(active!=null && tick>=finishedAt) {
                var pos=active.key().pos();
                if(ineligibleNear && Math.max(Math.abs(Math.floorDiv(pos.x(),16)),Math.abs(Math.floorDiv(pos.z(),16)))<=2)ineligible++;
                coverage.sampled(active.key().dimension(),pos);active=null;
            }
            if(active==null) {
                active=hints.peek(anchors,tick,DiscoveryPrioritizer.AGING_TICKS);
                if(active!=null){hints.remove(active);finishedAt=tick+100;}
            }
            if(tick%1000==0)check(hints.size()<=128,"slow discovery never exceeds hint admission budget");
        }
        check(ineligible==(ineligibleNear?25:0),"ineligible near observations are attempted once, not continually retried");
        check(coverage.size()==expected,"every visible source sample eventually runs beyond the 128-entry hint cap");
        check(coverage.contains("overworld",new BlockPoint(136,65,136)),"outermost usable ring reached despite continual near-ring offers");
    }
}
