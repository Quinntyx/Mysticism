package io.github.mysticism.client.spiritworld;

import io.github.mysticism.landmark.FogHorizons;

/** Client contract in blocks; parent/terrain must load beyond the opaque horizon. */
public final class SpiritRenderSettings {
    public static final double OPAQUE_RADIUS = 64.0;
    public static final double MIN_LOADING_RADIUS = 80.0;
    public static final double MAX_TRANSMITTANCE = 0.001;
    public static final double NEAR_BUBBLE = 5.0;
    public static final double MIN_EXTINCTION = -Math.log(MAX_TRANSMITTANCE) / (OPAQUE_RADIUS - NEAR_BUBBLE);
    public static final FogHorizons HORIZONS = new FogHorizons(40, OPAQUE_RADIUS, MIN_LOADING_RADIUS);
    public static final double SATURATION_FADE_SECONDS = 2.5;
    public static final double DENSITY_PERIOD = 4096.0;
    public static final int MAX_GLYPHS = 128;

    public enum Quality {
        LOW(24, 1), MEDIUM(40, 2), HIGH(64, 3);
        public final int fogSteps;
        public final int kernelRadius;
        Quality(int fogSteps, int kernelRadius) {
            this.fogSteps = fogSteps;
            this.kernelRadius = kernelRadius;
        }
    }

    private SpiritRenderSettings() {}

    /** Smooth, independent of frame count, game ticks, fog and painterly quality. */
    public static float saturation(double elapsedSeconds) {
        double t = Math.max(0, Math.min(1, elapsedSeconds / SATURATION_FADE_SECONDS));
        return (float) (t * t * (3 - 2 * t));
    }

    /** Periodic coordinates preserve density at negative positions and wrap boundaries. */
    public static float wrap(double coordinate) {
        if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Nonfinite camera coordinate");
        float wrapped = (float) (coordinate - Math.floor(coordinate / DENSITY_PERIOD) * DENSITY_PERIOD);
        // Float rounding just below the boundary (especially tiny negative coordinates) can
        // produce PERIOD. Zero is the same periodic point, and keeps uniforms in [0, PERIOD).
        return wrapped >= DENSITY_PERIOD ? 0.0f : wrapped;
    }
}
