package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.*;
import java.util.function.*;

/** Server-thread-confined vertical discovery scheduler. Full scans are capped; overflow
 * retains only distinct loaded-chunk keys, never chunks, snapshots or loader closures.
 * The caller removes keys on chunk/dimension unload, so deferred memory is bounded by
 * resident source chunks rather than a second arbitrary cap that could lose discovery.
 * FIFO admission promotes at most one key per tick, including stale non-loading misses.
 * For a finite loaded backlog and a draining hint sink, every retained key is scanned. */
public final class VerticalScanQueue<K> {
    private final int capacity;
    private final LinkedHashMap<K, VerticalSeeds.Scan> scans = new LinkedHashMap<>();
    private final LinkedHashSet<K> deferred = new LinkedHashSet<>();

    public VerticalScanQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("vertical scan capacity");
        this.capacity = capacity;
    }
    public int activeCount() { return scans.size(); }
    public int deferredCount() { return deferred.size(); }
    public int size() { return scans.size() + deferred.size(); }

    /** Preserve the cheap chunk-load snapshot when room exists. A new arrival cannot
     * bypass deferred work, and duplicate load events cannot restart a partial scan. */
    public void offer(K key, Supplier<VerticalSeeds.Scan> snapshot) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(snapshot);
        if (scans.containsKey(key) || deferred.contains(key)) return;
        if (scans.size() == capacity || !deferred.isEmpty()) deferred.add(key);
        else scans.put(key, Objects.requireNonNull(snapshot.get()));
    }

    /** Retain only a key until bounded tick admission. Used by chunk-load callbacks
     * even before the source Session exists; no world/snapshot closure is retained. */
    public void defer(K key) {
        Objects.requireNonNull(key);
        if (!scans.containsKey(key)) deferred.add(key);
    }

    /** Snapshot and reader callbacks must use non-loading lookups and return null for
     * unloaded keys. Backpressure pauses observation and retains all completed seeds.
     * Returned reads share the source pipeline's tick budget; promotion does no block reads. */
    public int advance(int probeBudget, Function<K, VerticalSeeds.Scan> snapshot,
                       Function<K, Predicate<BlockPoint>> reader, BooleanSupplier acceptsSeed,
                       BiConsumer<K, BlockPoint> seed) {
        if (probeBudget < 1) throw new IllegalArgumentException("vertical probe budget");
        if (scans.size() < capacity && !deferred.isEmpty()) {
            K key = deferred.removeFirst();
            VerticalSeeds.Scan scan = snapshot.apply(key);
            if (scan != null) scans.put(key, scan);
        }
        if (scans.isEmpty() || !acceptsSeed.getAsBoolean()) return 0;
        var entry = scans.entrySet().iterator().next();
        K key = entry.getKey();
        var air = reader.apply(key);
        if (air == null) { scans.remove(key); return 0; }
        var scan = entry.getValue();
        scan.advance(probeBudget, air);
        int sampled = scan.lastSampled();
        if (scan.complete()) {
            while (acceptsSeed.getAsBoolean()) {
                var point = scan.pollSeed();
                if (point.isEmpty()) { scans.remove(key); break; }
                seed.accept(key, point.get());
            }
        }
        return sampled;
    }

    public void remove(K key) { scans.remove(key); deferred.remove(key); }
    public void removeIf(Predicate<K> predicate) {
        scans.keySet().removeIf(predicate);
        deferred.removeIf(predicate);
    }
    public void clear() { scans.clear(); deferred.clear(); }
}
