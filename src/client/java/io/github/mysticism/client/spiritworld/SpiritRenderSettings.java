package io.github.mysticism.client.spiritworld;

/** Client presentation distances, not terrain-readiness or collision guarantees. */
public final class SpiritRenderSettings {
    public static final double MAX_TRANSMITTANCE = 0.001;
    public static final double HORIZON_OPTICAL_DEPTH = -Math.log(MAX_TRANSMITTANCE);
    public static final FogRange DEFAULT_FOG_RANGE = fogRange(12);

    /** Follow the client render option, NOT the extent of the currently published mesh.
     * Shrinking to a sparse mesh would disguise missing source generation with opaque fog. */
    public static FogRange fogRange(int renderDistanceChunks) {
        double radius = Math.max(2, Math.min(32, renderDistanceChunks)) * 16.0;
        return new FogRange(radius * 0.5, radius);
    }

    /** Clear foreground followed by a smooth quadratic extinction ramp. At 75% of the
     * render radius even the densest unoccupied medium retains >=27% scene transmission.
     * The cubic integral is exact per segment, so even LOW quality reaches the same horizon. */
    public record FogRange(double visibleRadius, double opaqueRadius) {
        public FogRange {
            if (!Double.isFinite(visibleRadius) || !Double.isFinite(opaqueRadius)
                    || visibleRadius < 0 || visibleRadius >= opaqueRadius)
                throw new IllegalArgumentException("fog range");
        }
        public boolean containsDistanceSquared(double distanceSquared) {
            return Double.isFinite(distanceSquared) && distanceSquared >= 0
                    && distanceSquared <= opaqueRadius * opaqueRadius;
        }
        /** CPU equivalent of the shader's exact ramp integral; no noise/occupancy multiplier. */
        public double opticalDepth(double start, double end) {
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end < start)
                throw new IllegalArgumentException("fog segment");
            double span = opaqueRadius - visibleRadius;
            double a = Math.max(0, Math.min(1, (start - visibleRadius) / span));
            double b = Math.max(0, Math.min(1, (end - visibleRadius) / span));
            return HORIZON_OPTICAL_DEPTH * (b * b * b - a * a * a);
        }
    }
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

    /** A prepared transformed branch owns fog even while blending with the source snapshot. */
    public static boolean useVolumetricFog(boolean framePrepared, boolean failed, float strength) {
        return framePrepared && !failed && strength > .001f;
    }
    /** Bounded brush spacing in framebuffer pixels, independent of GUI scale. */
    public static int painterlyStride(int framebufferHeight) {
        return Math.max(1, Math.min(4, Math.round(framebufferHeight / 540.0f)));
    }

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
