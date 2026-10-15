package io.github.mysticism.client.spiritworld;

/** Vanilla TERRAIN parameters captured before bypass; never applied to the transformed branch. */
record SpiritSourceFog(float start, float end, int shape, float red, float green, float blue, float alpha) {
    /** CPU reference for Minecraft's fog.glsl, including its cylinder metric and color alpha. */
    double amount(double x, double y, double z) {
        double distance = shape == 0 ? Math.sqrt(x*x + y*y + z*z) : Math.max(Math.hypot(x,z),Math.abs(y));
        if (distance <= start) return 0;
        if (distance >= end) return alpha;
        double t = (distance-start)/(end-start);
        return t*t*(3-2*t)*alpha;
    }
}
