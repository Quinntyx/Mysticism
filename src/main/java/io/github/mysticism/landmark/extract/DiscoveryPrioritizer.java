package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.*;
import java.util.function.Predicate;

/** Bounded owner-thread discovery scheduling; no source IO or ownership decisions here.
 * Reads and player-blocking discovery precede frontier growth and visible-locality backfill.
 * Admitted work ages across BOTH queues, so continuous reads/player churn cannot starve
 * retained background work. Aged background dispatches yield to waiting READ/PLAYER work
 * after one turn, across both queues. Scheduling is non-preemptive: an active mutation is
 * never torn down for priority; latency still includes its IO/model/publication time. */
public final class DiscoveryPrioritizer {
    public static final int READ=-1,PLAYER=0,FRONTIER=1,BACKFILL=2;
    public static final long AGING_TICKS=200;
    public static final int MAX_CONSECUTIVE_BACKGROUND_DISPATCHES=1;
    public record Anchor(String dimension,BlockPoint pos) {}
    public record Key(String dimension,BlockPoint pos) {}
    public interface Scheduled {
        int tier();
        long enqueuedTick();
    }
    public record Hint(Key key,int tier,long enqueuedTick) implements Scheduled {}
    private final int capacity;
    private final ArrayList<Hint> entries=new ArrayList<>();
    private int consecutiveBackgroundDispatches;
    public DiscoveryPrioritizer(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("discovery capacity");
        this.capacity=capacity;
    }
    public boolean offer(String dimension,BlockPoint pos,int tier,List<Anchor> anchors) {
        return offer(dimension,pos,tier,anchors,0);
    }
    /** Upgrades never reset age. Full queues preserve higher-priority work and prefer nearby
     * hints. Once aged, a same-tier hint is protected from replacement by fresh arrivals.
     * Overload can still displace lower-tier work; rejected locality hints must be retried. */
    public boolean offer(String dimension,BlockPoint pos,int tier,List<Anchor> anchors,long now) {
        if(tier<PLAYER||tier>BACKFILL)throw new IllegalArgumentException("discovery tier");
        if(dimension==null||pos==null)throw new IllegalArgumentException("discovery hint");
        Key key=new Key(dimension,pos);
        for(int i=0;i<entries.size();i++) {
            Hint entry=entries.get(i);
            if(entry.key().equals(key)) {
                if(entry.tier()>tier)entries.set(i,new Hint(key,tier,entry.enqueuedTick()));
                return true;
            }
        }
        if(entries.size()>=capacity) {
            int victim=-1;
            Hint protectedEntry=oldestAged(entries,now,AGING_TICKS);
            for(int i=0;i<entries.size();i++) {
                Hint entry=entries.get(i);
                // Reserve one aged slot even during higher-tier floods. This is a bounded
                // fairness reservation, not permission to grow beyond the queue budget.
                if(entry==protectedEntry || entry.tier()<tier || entry.tier()==tier && aged(entry,now,AGING_TICKS))continue;
                if(victim<0 || entry.tier()>entries.get(victim).tier()
                        || entry.tier()==entries.get(victim).tier()
                        && distance(anchors,entry.key())>distance(anchors,entries.get(victim).key()))victim=i;
            }
            if(victim<0)return false;
            Hint entry=entries.get(victim);
            double oldDistance=distance(anchors,entry.key()),incoming=distance(anchors,key);
            if(entry.tier()==tier && (oldDistance<incoming
                    || oldDistance==incoming && oldDistance!=Double.POSITIVE_INFINITY))return false;
            entries.remove(victim);
        }
        entries.add(new Hint(key,tier,now));
        return true;
    }
    /** Highest tier/nearest observer normally; oldest admitted hint after bounded aging.
     * An observer in a different dimension never makes a hint nearer. */
    public Hint peek(List<Anchor> anchors,long now,long agingTicks) {
        return peek(anchors,now,agingTicks,entry->true);
    }
    private Hint peek(List<Anchor> anchors,long now,long agingTicks,Predicate<Hint> eligible) {
        Hint best=null;
        for(Hint entry:entries) {
            if(!eligible.test(entry))continue;
            if(best==null) {best=entry;continue;}
            int order=compare(entry,best,now,agingTicks);
            if(order<0 || order==0 && !aged(entry,now,agingTicks)
                    && distance(anchors,entry.key())<distance(anchors,best.key()))best=entry;
        }
        return best;
    }
    public void remove(Hint hint) {entries.remove(hint);}
    public Key poll(List<Anchor> anchors) {
        Hint best=peek(anchors,0,AGING_TICKS);
        if(best==null)return null;
        remove(best);return best.key();
    }
    /** The production dispatch policy: hint and explicit request ages share the same clock.
     * After a background turn, eligible urgent work gets the next opportunity even if every
     * older background entry is aged. With sustained urgent churn, aging still grants oldest
     * background work alternate turns. No urgent work means background drains without idling.
     * Model-unready discovery is excluded BEFORE selection, so it cannot head-block reads.
     * Selection is read-only; call dispatched ONLY once an operation is actually activated. */
    public <T extends Scheduled> Scheduled next(List<T> requests,List<Anchor> anchors,long now,
                                               long agingTicks,boolean discoveryReady) {
        if(consecutiveBackgroundDispatches>=MAX_CONSECUTIVE_BACKGROUND_DISPATCHES) {
            Scheduled urgent=next(requests,anchors,now,agingTicks,discoveryReady,true);
            if(urgent!=null)return urgent;
        }
        return next(requests,anchors,now,agingTicks,discoveryReady,false);
    }
    private <T extends Scheduled> Scheduled next(List<T> requests,List<Anchor> anchors,long now,
                                                long agingTicks,boolean discoveryReady,boolean urgentOnly) {
        Scheduled request=selectNext(requests,now,agingTicks,
                op->(discoveryReady||op.tier()==READ) && (!urgentOnly||op.tier()<=PLAYER));
        Hint hint=discoveryReady?peek(anchors,now,agingTicks,op->!urgentOnly||op.tier()<=PLAYER):null;
        if(request==null)return hint;
        if(hint==null)return request;
        int order=compare(hint,request,now,agingTicks);
        // Within an unaged tier, preserve request FIFO; hints use distance within their queue.
        return order<0 || order==0 && hint.enqueuedTick()<request.enqueuedTick()?hint:request;
    }
    /** Account an actual dispatch from EITHER queue, not a peek or a cancelled queued item.
     * Count all background starts so a fresh urgent arrival after background-only draining
     * need not wait for another background turn. Saturate to avoid overflow on idle servers. */
    public void dispatched(Scheduled op) {
        Objects.requireNonNull(op,"dispatched operation");
        if(op.tier()<=PLAYER)consecutiveBackgroundDispatches=0;
        else consecutiveBackgroundDispatches=Math.min(MAX_CONSECUTIVE_BACKGROUND_DISPATCHES,
                consecutiveBackgroundDispatches+1);
    }
    public int size() {return entries.size();}
    public boolean isEmpty() {return entries.isEmpty();}
    public void clear() {entries.clear();consecutiveBackgroundDispatches=0;}
    public void removeDimension(String dimension) {entries.removeIf(entry->entry.key().dimension().equals(dimension));}
    private static double distance(List<Anchor> anchors,Key key) {return distance(anchors,key.dimension(),key.pos());}
    public static double distance(List<Anchor> anchors,String dimension,BlockPoint pos) {
        double best=Double.POSITIVE_INFINITY;
        if(anchors!=null)for(Anchor anchor:anchors) {
            if(!anchor.dimension().equals(dimension))continue;
            double dx=(double)anchor.pos().x()-pos.x(),dy=(double)anchor.pos().y()-pos.y(),dz=(double)anchor.pos().z()-pos.z();
            best=Math.min(best,dx*dx+dy*dy+dz*dz);
        }
        return best;
    }
    public static <T extends Scheduled> int insertionIndex(List<T> ops,T op) {
        for(int i=0;i<ops.size();i++)if(ops.get(i).tier()>op.tier())return i;
        return ops.size();
    }
    /** Lowest priority, newest on ties, independent of the list's current ordering. */
    public static <T extends Scheduled> int evictionVictim(List<T> ops,T incoming) {
        return evictionVictim(ops,incoming,0,AGING_TICKS);
    }
    public static <T extends Scheduled> int evictionVictim(List<T> ops,T incoming,long now,long agingTicks) {
        T protectedEntry=oldestAged(ops,now,agingTicks);
        int victim=-1;
        for(int i=0;i<ops.size();i++) {
            T op=ops.get(i);
            if(op==protectedEntry || op.tier()<=incoming.tier())continue;
            if(victim<0 || op.tier()>ops.get(victim).tier()
                    || op.tier()==ops.get(victim).tier() && op.enqueuedTick()>=ops.get(victim).enqueuedTick())victim=i;
        }
        return victim;
    }
    public static <T extends Scheduled> T selectNext(List<T> ops,long now,long agingTicks) {
        return selectNext(ops,now,agingTicks,op->true);
    }
    public static <T extends Scheduled> T selectNext(List<T> ops,long now,long agingTicks,Predicate<T> eligible) {
        T best=null;
        for(T op:ops)if(eligible.test(op) && (best==null || compare(op,best,now,agingTicks)<0))best=op;
        return best;
    }
    private static <T extends Scheduled> T oldestAged(List<T> ops,long now,long agingTicks) {
        T oldest=null;
        for(T op:ops)if(aged(op,now,agingTicks) && (oldest==null || op.enqueuedTick()<oldest.enqueuedTick()))oldest=op;
        return oldest;
    }
    private static boolean aged(Scheduled op,long now,long agingTicks) {return now-op.enqueuedTick()>=agingTicks;}
    private static int compare(Scheduled a,Scheduled b,long now,long agingTicks) {
        boolean aa=aged(a,now,agingTicks),ba=aged(b,now,agingTicks);
        if(aa!=ba)return aa?-1:1;
        if(aa)return Long.compare(a.enqueuedTick(),b.enqueuedTick());
        return Integer.compare(a.tier(),b.tier());
    }
}
