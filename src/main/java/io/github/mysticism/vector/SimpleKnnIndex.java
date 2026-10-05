package io.github.mysticism.vector;

import io.github.mysticism.vector.IndexPair;
import java.util.*;
import java.util.function.BiConsumer;

/** Exact KNN with copied snapshots and deterministic score-descending/id-ascending order.
 * Results are independently owned, mutable lists for compatibility with callers that sort them.
 * EUCLIDEAN scores are negative squared distances, preserving the existing contract.
 */
public final class SimpleKnnIndex implements KnnIndex {
    private final Map<String,Vec384f> data = new HashMap<>();
    public SimpleKnnIndex() {}
    public SimpleKnnIndex(HashMap<String,Vec384f> values) { values.forEach(this::upsert); }
    public synchronized int size() { return data.size(); }
    public synchronized void upsert(String id, Vec384f vector) { EmbeddingSpace.requireCurrent(vector); data.put(Objects.requireNonNull(id),vector.clone()); }
    public synchronized Vec384f get(String id) { Vec384f v=data.get(id); return v==null?null:v.clone(); }
    public synchronized void deltaUpdate(String id,Vec384f delta) { EmbeddingSpace.requireCurrent(delta); data.computeIfAbsent(id,k->Vec384f.ZERO()).add(delta); }
    public synchronized void converge(List<String> keys,Vec384f target,float factor) { EmbeddingSpace.requireCurrent(target); for(String id:keys)data.computeIfAbsent(id,k->Vec384f.ZERO()).converge(target,factor); }
    private synchronized Map<String,Vec384f> snapshot() { Map<String,Vec384f> result=new TreeMap<>();data.forEach((id,v)->result.put(id,v.clone()));return result; }
    public List<IndexPair<String,Float>> kNN(int k,Vec384f query,Metric metric) {
        EmbeddingSpace.requireCurrent(query); Objects.requireNonNull(metric);
        if(k<=0)return new ArrayList<>();
        Vec384f q=query.clone();
        Comparator<IndexPair<String,Float>> best = Comparator.<IndexPair<String,Float>>comparingDouble(IndexPair::getValue).reversed().thenComparing(IndexPair::getKey);
        PriorityQueue<IndexPair<String,Float>> heap=new PriorityQueue<>(best.reversed());
        snapshot().forEach((id,v)->{
            float score=switch(metric){case COSINE->v.cosine(q);case DOT->v.dot(q);case EUCLIDEAN->-v.squareDistance(q);};
            if(!Float.isFinite(score))throw new IllegalArgumentException("Nonfinite KNN score");
            IndexPair<String,Float> candidate=new IndexPair<>(id,score);
            if(heap.size()<k)heap.add(candidate);else if(best.compare(candidate,heap.peek())<0){heap.poll();heap.add(candidate);}
        });
        List<IndexPair<String,Float>> result=new ArrayList<>(heap);result.sort(best);return result;
    }
    public void forEach(BiConsumer<String,Vec384f> consumer) { snapshot().forEach(consumer); }
}
