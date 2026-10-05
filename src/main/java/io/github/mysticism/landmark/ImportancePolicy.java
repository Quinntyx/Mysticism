package io.github.mysticism.landmark;

/** Activity/importance are dimensionless [0,1]; half life and growth budget use server ticks. */
public record ImportancePolicy(double cap, double halfLifeTicks, double growthPerTick, double activityWeight) {
    public ImportancePolicy {
        if (!Double.isFinite(cap) || cap<=0 || cap>1 || !Double.isFinite(halfLifeTicks) || halfLifeTicks<=0
                || !Double.isFinite(growthPerTick) || growthPerTick<0 || growthPerTick>1
                || !Double.isFinite(activityWeight) || activityWeight<0 || activityWeight>1) throw new IllegalArgumentException("importance policy");
    }
    /** Euclidean semantic distance. Hard gate runs before importance; no activity can extend radius. */
    public double score(double distanceSquared, double radius, double base, ActivityMetadata activity, long tick) {
        if (!Double.isFinite(radius) || radius<=0 || !Double.isFinite(radius*radius) || radius*radius==0 || !Double.isFinite(distanceSquared) || distanceSquared<0) throw new IllegalArgumentException("semantic gate");
        if (distanceSquared>=radius*radius) return 0;
        if (!Double.isFinite(base) || base<0 || base>1) throw new IllegalArgumentException("base importance");
        double bounded=Math.min(cap,base+activityWeight*activity.decayed(tick,this));
        double taper=1-distanceSquared/(radius*radius);
        return bounded*taper*taper;
    }
}
