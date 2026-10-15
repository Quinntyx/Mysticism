package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import io.github.mysticism.landmark.Bounds;
import java.util.LinkedHashMap;

/** Bounded, session-only record of completed background samples (including observations with
 * no eligible ownership mask). Not ownership: suppresses redundant IO/model work until source
 * edits or chunk availability change. Explicit/player/frontier discovery always bypasses it.
 * No timers: a slow model must not perpetually restart near rings before wider locality runs. */
public final class DiscoveryCoverage {
    private final int capacity;
    private final LinkedHashMap<DiscoveryPrioritizer.Key,Boolean> completed=new LinkedHashMap<>();
    public DiscoveryCoverage(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("coverage capacity");
        this.capacity=capacity;
    }
    public boolean contains(String dimension,BlockPoint pos) {
        return completed.containsKey(new DiscoveryPrioritizer.Key(dimension,pos));
    }
    public void sampled(String dimension,BlockPoint pos) {
        completed.put(new DiscoveryPrioritizer.Key(dimension,pos),Boolean.TRUE);
        if(completed.size()>capacity)completed.remove(completed.keySet().iterator().next());
    }
    /** Same 25^3 observation footprint as Ensure. A newly available chunk or changed block
     * invalidates every intersecting sample, including observations that were only partial. */
    public void invalidate(String dimension,Bounds changed) {
        completed.keySet().removeIf(key->{
            if(!key.dimension().equals(dimension))return false;
            BlockPoint p=key.pos();
            return p.x()-12<changed.maxX() && p.x()+13>changed.minX()
                    && p.y()-12<changed.maxY() && p.y()+13>changed.minY()
                    && p.z()-12<changed.maxZ() && p.z()+13>changed.minZ();
        });
    }
    public int size() {return completed.size();}
    public void removeDimension(String dimension) {completed.keySet().removeIf(key->key.dimension().equals(dimension));}
    public void clear() {completed.clear();}
}
