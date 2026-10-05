package io.github.mysticism.landmark;

/** Bounded activity snapshot. Repeated updates in the same tick cannot manufacture growth. */
public record ActivityMetadata(double level, long evaluatedTick) {
    public ActivityMetadata {
        if (!Double.isFinite(level) || level<0 || level>1 || evaluatedTick<0) throw new IllegalArgumentException("activity");
    }
    public double decayed(long tick, ImportancePolicy policy) {
        if(tick<evaluatedTick) throw new IllegalArgumentException("time reversal");
        return Math.min(policy.cap(),level*Math.pow(0.5,(tick-evaluatedTick)/policy.halfLifeTicks()));
    }
    public ActivityMetadata advance(long tick, double stimulus, ImportancePolicy policy) {
        if (!Double.isFinite(stimulus) || stimulus<0) throw new IllegalArgumentException("stimulus");
        double old=decayed(tick,policy);
        double growth=Math.min(stimulus,policy.growthPerTick()*(tick-evaluatedTick));
        return new ActivityMetadata(Math.min(policy.cap(),old+growth),tick);
    }
}
