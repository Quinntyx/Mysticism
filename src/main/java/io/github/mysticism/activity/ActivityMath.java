package io.github.mysticism.activity;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.util.*;

/** Pure bounded reducers shared by the production adapters and checks. */
public final class ActivityMath {
    private ActivityMath() {}
    /** Actual before/after Stats values: resets, saturation and repeated totals cannot add influence. */
    public static int delta(int before,int after){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,(long)after-before));}
    public static Vec384f drift(Vec384f from, Vec384f to, double rate) {
        EmbeddingSpace.requireCurrent(from); EmbeddingSpace.requireCurrent(to);
        if (!Double.isFinite(rate) || rate < 0 || rate > 1) throw new IllegalArgumentException("rate");
        if (to.length() == 0 || rate == 0) return from.clone();
        if (from.length() == 0) return new Vec384f(to.norm());
        Vec384f mixed = from.clone().converge(to, (float) rate);
        // Opposing vectors may cancel exactly: retain continuity instead of emitting NaN.
        return mixed.length() < 1e-6 ? from.clone() : new Vec384f(mixed.norm());
    }
    public static double relevance(double distanceSquared, double radius) {
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0 || !Double.isFinite(radius) || radius <= 0)
            throw new IllegalArgumentException("distance/radius");
        double t = Math.max(0, 1 - distanceSquared / (radius * radius)); return t * t;
    }
    public static Vec384f weighted(List<Vec384f> vectors, List<Double> weights) {
        if (vectors.size() != weights.size() || vectors.size() > 8) throw new IllegalArgumentException("budget");
        Vec384f sum = Vec384f.ZERO(); double total = 0;
        for (int i=0;i<vectors.size();i++) {
            double weight = weights.get(i);
            if (!Double.isFinite(weight) || weight < 0 || weight > 64) throw new IllegalArgumentException("weight");
            EmbeddingSpace.requireCurrent(vectors.get(i));
            if (vectors.get(i).length() > 0) {sum.add(new Vec384f(vectors.get(i).norm()).mul((float) weight)); total += weight;}
        }
        return total == 0 || sum.length() < 1e-6 ? Vec384f.ZERO() : new Vec384f(sum.norm());
    }
    /** Bounded, consumable window; never replays a lifetime counter on a later window. */
    public static final class Window {
        public static final int LIMIT=64;
        private final Map<String,Double> weights=new TreeMap<>();
        public void add(String descriptor, double weight) {
            if (descriptor == null || descriptor.isBlank() || descriptor.length()>2048 || !Double.isFinite(weight) || weight<=0) return;
            if (!weights.containsKey(descriptor) && weights.size()>=LIMIT) return;
            weights.merge(descriptor, Math.min(64,weight), (a,b)->Math.min(64,a+b));
        }
        public Map<String,Double> take() {
            Map<String,Double> selected=new LinkedHashMap<>();
            weights.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(8).forEach(e->selected.put(e.getKey(),e.getValue()));
            weights.clear(); return selected;
        }
        public int size(){return weights.size();}
    }
}
