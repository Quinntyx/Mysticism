package io.github.mysticism.activity;

import io.github.mysticism.vector.*;

/** Shared server/client movement math. Shallow preserves source-grid axes; deep rotates per player. */
public final class TraversalSteering {
    public static final double BLOCKS_PER_SEMANTIC_UNIT = 96;
    public static final float ROTATION_PER_BLOCK = 0.30f;
    private TraversalSteering() {}

    /** Turn the chosen movement toward a captured LOCATION, then advance q in the updated basis. */
    public static void deepStep(Vec384f q, Basis384f basis, Vec384f target, double dx, double dy, double dz) {
        deepStep(q, basis, target, dx, dy, dz, false);
    }
    public static void deepStep(Vec384f q, Basis384f basis, Vec384f target, double dx, double dy, double dz,
                                boolean capturedLanding) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return;
        double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (distance < 1e-8 || distance > 4) return; // stationary and teleport are not semantic travel
        BasisIntegrator384f.step(basis, q, target, dx, dy, dz, ROTATION_PER_BLOCK);
        // Retain ordinary movement-dependent pursuit until acquisition range. Ease translation
        // near a captured landing so fixed walking steps cannot overshoot/orbit the tiny final band.
        // Rotation still receives the FULL physical movement, not the eased semantic displacement.
        double remaining = Math.sqrt(q.squareDistance(target));
        double scale = capturedLanding
                ? Math.min(1, remaining * BLOCKS_PER_SEMANTIC_UNIT * ROTATION_PER_BLOCK * .5) : 1;
        advance(q, basis, dx * scale, dy * scale, dz * scale);
    }
    public static void advance(Vec384f q, Basis384f basis, double dx, double dy, double dz) {
        q.add(basis.i.clone().mul((float)(dx / BLOCKS_PER_SEMANTIC_UNIT)))
                .add(basis.j.clone().mul((float)(dy / BLOCKS_PER_SEMANTIC_UNIT)))
                .add(basis.k.clone().mul((float)(dz / BLOCKS_PER_SEMANTIC_UNIT)));
    }
    /**
     * Terminal acquisition keeps every residual component reachable while the source grid aligns.
     * Driven only by real movement; each step is <= movement/96 and <= 25% of the residual.
     * No target assignment, normalization, stationary drift or source-basis-only translation.
     */
    public static void approachStep(Vec384f q, Vec384f target, double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return;
        double movement = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (movement < 1e-8 || movement > 4) return;
        double remaining = Math.sqrt(q.squareDistance(target));
        if (remaining < 1e-8) return;
        q.converge(target, (float)Math.min(.25, movement / (BLOCKS_PER_SEMANTIC_UNIT * remaining)));
    }
    /** Valid touch participants already have aligned bases, avoiding antipodal interpolation. */
    public static Basis384f blend(Basis384f from, Basis384f to, float fraction) {
        if (fraction >= 1) return to.clone();
        Vec384f i = from.i.clone().converge(to.i, fraction);
        Vec384f j = from.j.clone().converge(to.j, fraction);
        Vec384f k = from.k.clone().converge(to.k, fraction);
        if (i.length() < 1e-6) return from.clone();
        i.mul(1 / i.length());
        j.sub(i.clone().mul(j.dot(i)));
        if (j.length() < 1e-6) return from.clone();
        j.mul(1 / j.length());
        k.sub(i.clone().mul(k.dot(i))).sub(j.clone().mul(k.dot(j)));
        if (k.length() < 1e-6) return from.clone();
        k.mul(1 / k.length());
        return new Basis384f(i, j, k);
    }

    /** Legacy caller compatibility only: current local semantic pose, no target mutation/orbit. */
    public static Vec384f supported(Vec384f landmark, Basis384f basis, double dx, double dy, double dz,
                                    double nx, double ny, double nz) {
        Vec384f result = landmark.clone(); advance(result, basis, dx, dy, dz); return result;
    }
}
