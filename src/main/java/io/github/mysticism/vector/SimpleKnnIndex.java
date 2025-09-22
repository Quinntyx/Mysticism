package io.github.mysticism.vector;

import ai.djl.util.Pair;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

import static io.github.mysticism.Mysticism.LOGGER;

public class SimpleKnnIndex implements KnnIndex {
    private final HashMap<String, Vec384f> data;
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();


    public SimpleKnnIndex(HashMap<String, Vec384f> data) {
        this.data = data;
    }

    public SimpleKnnIndex() {
        this.data = new HashMap<>();
    }

    public int size() {
        rw.readLock().lock();
        try { return data.size(); }
        finally { rw.readLock().unlock(); }
    }

    private static final long WRITE_WARN_MS = 2000;

    public void upsert(String id, Vec384f v) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            ok = rw.writeLock().tryLock(WRITE_WARN_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!ok) {
                LOGGER.warn("[KNN] writeLock timeout acquiring for upsert('{}')", id);
                rw.writeLock().lock(); // block & acquire anyway, but we log a warning
            }
            this.data.put(id, v);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            rw.writeLock().lock(); // fall back to blocking
            this.data.put(id, v);
        } finally {
            rw.writeLock().unlock();
        }
    }


    public Vec384f get(String id) {
        rw.readLock().lock();
        try {
            Vec384f v = this.data.get(id);
            return v != null ? v.clone() : null;
        } finally {
            rw.readLock().unlock();
        }
    }

    public void deltaUpdate(String id, Vec384f delta) {
        rw.writeLock().lock();
        try {
            this.data.computeIfAbsent(id, k -> Vec384f.ZERO()).add(delta);
        } finally {
            rw.writeLock().unlock();
        }
    }

    public List<Pair<String, Float>> kNN(int k, Vec384f query, Metric metric) {
        if (k <= 0) return new ArrayList<>();

        List<Map.Entry<String, Vec384f>> snapshot;
        rw.readLock().lock();
        try {
            snapshot = new ArrayList<>(data.entrySet());
        } finally {
            rw.readLock().unlock();
        }

        PriorityQueue<Pair<String, Float>> heap = new PriorityQueue<>(Comparator.comparingDouble(Pair::getValue));

        for (Map.Entry<String, Vec384f> entry : snapshot) {
            float score = switch (metric) {
                case COSINE -> entry.getValue().cosine(query);
                case DOT -> entry.getValue().dot(query);
                // PriorityQueue is minheap, must flip Euclidean since we want smaller = closer
                case EUCLIDEAN -> -1.f * entry.getValue().squareDistance(query);
            };

            if (heap.size() < k)
                heap.add(new Pair<>(entry.getKey(), score));
            else {
                // we already early return if k == 0
                // so this branch only triggers if heap already has elements
                assert heap.peek() != null;
                if (score > heap.peek().getValue()) { // if better than the current worst one
                    heap.poll();
                    heap.add(new Pair<>(entry.getKey(), score));
                }
            }
        }

        return new ArrayList<>(heap);

    }

    public void converge(List<String> affectedKeys, Vec384f target, float factor) {
        rw.writeLock().lock();
        try {
            for (String key : affectedKeys)
                this.data.computeIfAbsent(key, k -> Vec384f.ZERO()).converge(target, factor);
        } finally { rw.writeLock().unlock(); }
    }

    public void forEach(BiConsumer<String, Vec384f> consumer) {
        List<Map.Entry<String, Vec384f>> snapshot;
        rw.readLock().lock();
        try {
            snapshot = new ArrayList<>(data.entrySet());
        } finally {
            rw.readLock().unlock();
        }
        // Iterate with NO lock held
        for (var e : snapshot) {
            Vec384f copy = e.getValue() != null ? e.getValue().clone() : null;
            consumer.accept(e.getKey(), copy);
        }
    }

}

