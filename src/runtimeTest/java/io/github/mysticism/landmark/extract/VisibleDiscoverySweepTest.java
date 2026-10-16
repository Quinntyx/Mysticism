package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.HashSet;
import java.util.Set;

/** Ring coverage is independent of chunk-load event order and hint-queue admission. */
public final class VisibleDiscoverySweepTest {
    private static int checks;
    private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args) {
        for(int radius:new int[]{0,1,2,8,16,32})coversRadiusAndRepeats(radius);
        independentObserversAndMovement();
        radiusChangeRestartsNear();
        lifecycleAndNegativeCoordinates();
        System.out.println("VisibleDiscoverySweepTest: "+checks+" checks passed");
    }
    private static DiscoveryPrioritizer.Anchor anchor(String dim,long x,long z) {
        return new DiscoveryPrioritizer.Anchor(dim,new BlockPoint(x,64,z));
    }
    private static void coversRadiusAndRepeats(int radius) {
        var sweep=new VisibleDiscoverySweep();var observer=anchor("overworld",0,0);
        int count=(2*radius+1)*(2*radius+1),lastRing=-1;
        Set<VisibleDiscoverySweep.Sample> visited=new HashSet<>();
        for(int index=0;index<count;index++) {
            var sample=sweep.next(observer,radius);int ring=Math.max(Math.abs(sample.chunkX()),Math.abs(sample.chunkZ()));
            check(ring<=radius,"no lookup beyond configured visible radius");
            check(ring>=lastRing,"near rings sampled before far locality");lastRing=ring;
            check(visited.add(sample),"every IO address visited once per sweep");
        }
        check(visited.size()==count,"full render-distance square covered, not a fixed hint cap");
        check(sweep.next(observer,radius).equals(new VisibleDiscoverySweep.Sample("overworld",0,0)),"later sweep retries addresses whose hint was rejected or chunk absent");
    }
    private static void independentObserversAndMovement() {
        var sweep=new VisibleDiscoverySweep();var first=anchor("overworld",0,0);var second=anchor("nether",160,160);
        check(sweep.next(first,2).chunkX()==0,"first observer begins nearby");
        check(sweep.next(second,2).equals(new VisibleDiscoverySweep.Sample("nether",10,10)),"other dimension gets its own near-first cursor");
        check(sweep.next(anchor("overworld",1,1),2).equals(new VisibleDiscoverySweep.Sample("overworld",-1,-1)),"motion inside a source chunk does not reset wide progress");
        check(sweep.next(anchor("overworld",16,0),2).equals(new VisibleDiscoverySweep.Sample("overworld",1,0)),"new source neighborhood starts near rather than at stale far cursor");
    }
    private static void radiusChangeRestartsNear() {
        var sweep=new VisibleDiscoverySweep();var observer=anchor("overworld",0,0);
        for(int n=0;n<20;n++)sweep.next(observer,8);
        check(sweep.next(observer,2).equals(new VisibleDiscoverySweep.Sample("overworld",0,0)),"render-distance changes restart safely inside new radius");
        boolean rejected=false;try{sweep.next(observer,33);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid radius cannot create unbounded scan");
    }
    private static void lifecycleAndNegativeCoordinates() {
        var sweep=new VisibleDiscoverySweep();var observer=anchor("overworld",-1,-17);
        var expected=new VisibleDiscoverySweep.Sample("overworld",-1,-2);
        check(sweep.next(observer,2).equals(expected),"negative block coordinates use floor chunk IO addressing");
        sweep.next(observer,2);sweep.removeDimension("overworld");
        check(sweep.next(observer,2).equals(expected),"dimension unload releases retained progress");
        sweep.next(observer,2);sweep.clear();
        check(sweep.next(observer,2).equals(expected),"server stop releases retained progress");
    }
}
