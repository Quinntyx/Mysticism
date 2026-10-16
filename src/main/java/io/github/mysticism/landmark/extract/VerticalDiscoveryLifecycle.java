package io.github.mysticism.landmark.extract;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Server-thread-confined chunk discovery lifetime, beginning BEFORE worlds/spawn load.
 * The later source Session adopts the same queue; starting that Session must not erase
 * early loads. Only keys are retained, bounded by resident source chunks, never worlds,
 * chunks, scan snapshots or loading closures. Stop rejects late unload/load callbacks. */
public final class VerticalDiscoveryLifecycle<S, K> {
    private final int capacity;
    private final Map<S, VerticalScanQueue<K>> queues = new IdentityHashMap<>();

    public VerticalDiscoveryLifecycle(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("vertical discovery capacity");
        this.capacity = capacity;
    }

    /** SERVER_STARTING allocates; SERVER_STARTED adopts without replacing early keys. */
    public VerticalScanQueue<K> start(S server) {
        Objects.requireNonNull(server);
        return queues.computeIfAbsent(server, ignored -> new VerticalScanQueue<>(capacity));
    }

    public void loaded(S server, K key) {
        var queue = queues.get(server);
        if (queue != null) queue.defer(key);
    }

    public void unloaded(S server, K key) {
        var queue = queues.get(server);
        if (queue != null) queue.remove(key);
    }

    public void unloadWorld(S server, Predicate<K> belongsToWorld) {
        var queue = queues.get(server);
        if (queue != null) queue.removeIf(belongsToWorld);
    }

    public void stop(S server) {
        var queue = queues.remove(server);
        if (queue != null) queue.clear();
    }
}
