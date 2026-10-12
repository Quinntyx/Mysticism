package io.github.mysticism.landmark;

import java.util.*;
import java.util.function.Predicate;

/** Fair three-lane scheduler for the single-op source pipeline. Wider source discovery
 * (hint cascades, frontier retries, activity growth) must never block movement-critical
 * reads (terrain mesh/collision/support sampling) or hold the pipeline while waiting for
 * real embedding readiness:
 * <ul>
 *   <li>Read ops preempt a running non-read op under a bounded fair-share quota,
 *       including resource owners: movement sampling needs no store/activity lock.</li>
 *   <li>Requested/resource-owning discovery still advances under sustained reads (one tick per quota),
 *       and requested ops keep FIFO order; a preempted op resumes where it was parked.</li>
 *   <li>An inadmissible op (e.g. gated on embedding readiness) is skipped without losing
 *       its place: it can never block later ops in its own lane or the other lanes.</li>
 *   <li>Background ops (hint-driven discovery cascade) start only when no reads and no
 *       requested discovery ops are admissible. A started resource owner retains discovery
 *       precedence until it releases its resources, but can still yield to reads.</li>
 * </ul>
 * Ops are identified by identity; the caller owns all op state and lifecycle. */
final class SourceSchedule<T> {
    final ArrayDeque<T> readLane=new ArrayDeque<>();
    final ArrayDeque<T> discoveryLane=new ArrayDeque<>();
    final ArrayDeque<T> backgroundLane=new ArrayDeque<>();
    private final int fairShare;
    private final Predicate<T> readOp,backgroundOp;
    private int debt;
    /** Suspended only for movement reads, never for another discovery (in any lane). */
    private T resourceOwner;
    /** @param fairShare how many read ops may go first before a waiting discovery op runs */
    SourceSchedule(int fairShare,Predicate<T> readOp,Predicate<T> backgroundOp){
        if(fairShare<1)throw new IllegalArgumentException("source schedule fair share");
        this.fairShare=fairShare;this.readOp=Objects.requireNonNull(readOp);this.backgroundOp=Objects.requireNonNull(backgroundOp);
    }
    /** Movement-critical read; newest first, matching the previous read priority. */
    void read(T op){readLane.addFirst(op);}
    /** Requested discovery/topology op (public API callers with a real future). */
    void discover(T op){discoveryLane.addLast(op);}
    /** Hint-driven wider discovery cascade; never outranks requested work. */
    void background(T op){backgroundLane.addLast(op);}
    int readPending(){return readLane.size();}
    int discoveryPending(){return discoveryLane.size()+(resourceOwner!=null&&!backgroundOp.test(resourceOwner)?1:0);}
    int backgroundPending(){return backgroundLane.size()+(resourceOwner!=null&&backgroundOp.test(resourceOwner)?1:0);}
    /** Decide the op to advance this tick. Resource owners may yield to movement reads,
     * but retain precedence over ALL other discovery, including requested work ahead of
     * a background owner. Thus locks stay releasable without delaying movement by the
     * entire geometry/publication stage. Quota expiry grants one discovery advance, not
     * permission to run that op to completion. A read already running is never interrupted. */
    T pick(T current,Predicate<T> admissible,Predicate<T> parkable){
        Objects.requireNonNull(admissible);Objects.requireNonNull(parkable);
        if(current!=null&&!readOp.test(current)){
            if(!parkable.test(current)){
                if(resourceOwner!=null)throw new IllegalStateException("another source resource owner is suspended");
                resourceOwner=current;current=null;
            }else if(!readLane.isEmpty()){
                (backgroundOp.test(current)?backgroundLane:discoveryLane).addFirst(current);
                current=null;
            }
        }
        if(current!=null)return current;
        boolean readWaiting=!readLane.isEmpty();
        if(resourceOwner!=null){
            if(readWaiting&&debt<fairShare){debt++;return readLane.removeFirst();}
            // Finish the lock-owning stage even if model readiness changed meanwhile.
            T owner=resourceOwner;resourceOwner=null;debt=0;return owner;
        }
        if(readWaiting){
            T requested=firstAdmissible(discoveryLane,admissible);
            if(requested!=null){
                if(debt<fairShare){debt++;return readLane.removeFirst();}
                debt=0;return removeAdmissible(discoveryLane,admissible);
            }
            return readLane.removeFirst();
        }
        T requested=removeAdmissible(discoveryLane,admissible);
        if(requested!=null){debt=0;return requested;}
        T parked=removeAdmissible(backgroundLane,admissible);
        if(parked!=null)debt=0;
        return parked;
    }
    /** Cancel and remove every queued op matching the predicate, in all lanes, invoking the
     * action exactly once per matched op (used for guarded invalidation and shutdown, so a
     * parked op can never resume stale work or retain the activity mutation pause). */
    void cancelIf(Predicate<T> matches,java.util.function.Consumer<T> action){
        Objects.requireNonNull(matches);Objects.requireNonNull(action);
        if(resourceOwner!=null&&matches.test(resourceOwner)){
            T owner=resourceOwner;resourceOwner=null;action.accept(owner);
        }
        for(var lane:List.of(readLane,discoveryLane,backgroundLane))
            for(Iterator<T> it=lane.iterator();it.hasNext();){T op=it.next();if(matches.test(op)){it.remove();action.accept(op);}}
    }
    private T firstAdmissible(ArrayDeque<T> lane,Predicate<T> admissible){
        for(T op:lane)if(admissible.test(op))return op;
        return null;
    }
    /** Remove and return the first admissible op, skipping (not dropping) gated ones. */
    private T removeAdmissible(ArrayDeque<T> lane,Predicate<T> admissible){
        for(Iterator<T> it=lane.iterator();it.hasNext();){T op=it.next();if(admissible.test(op)){it.remove();return op;}}
        return null;
    }
}
