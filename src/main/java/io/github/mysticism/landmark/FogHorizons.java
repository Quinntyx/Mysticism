package io.github.mysticism.landmark;

/** Absolute projected distances in blocks; visible < fully hidden < prefetch. Strict horizon gates. */
public record FogHorizons(double visible,double hidden,double prefetch) {
    public FogHorizons {
        if(!Double.isFinite(visible) || !Double.isFinite(hidden) || !Double.isFinite(prefetch) || visible<0 || visible>=hidden || hidden>=prefetch || !Double.isFinite(prefetch*prefetch) || prefetch*prefetch==0)
            throw new IllegalArgumentException("fog horizon ordering");
    }
    public double opacity(double distance) {
        if(!Double.isFinite(distance) || distance<0) throw new IllegalArgumentException("distance");
        return BorderDither.smoothstep((distance-visible)/(hidden-visible));
    }
    public boolean shouldPrefetch(double distanceSquared) {
        if(!Double.isFinite(distanceSquared) || distanceSquared<0) throw new IllegalArgumentException("distance");
        return distanceSquared<prefetch*prefetch;
    }
}
