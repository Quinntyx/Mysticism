package io.github.mysticism.activity;

import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.vector.*;
import java.util.function.BiConsumer;

/** Shared server/client movement math. Shallow preserves source-grid axes; deep rotates per player. */
public final class TraversalSteering {
    public static final double BLOCKS_PER_SEMANTIC_UNIT = 96;
    public static final float ROTATION_PER_BLOCK = 0.30f;
    // Bound integration error by DISTANCE, not number/duration of client/server ticks. An admitted
    // 8-block window costs at most 64 substeps; midpoint rotation avoids post-turn translation jumps.
    private static final double MAX_STEP_BLOCKS = .125;
    private static final double APPROACH_DECAY_PER_BLOCK = -Math.log(.25);
    private TraversalSteering() {}

    /** Turn the chosen movement toward a captured LOCATION, then advance q in the updated basis. */
    public static void deepStep(Vec384f q, Basis384f basis, Vec384f target, double dx, double dy, double dz) {
        deepStep(q, basis, target, dx, dy, dz, false);
    }
    public static void deepStep(Vec384f q, Basis384f basis, Vec384f target, double dx, double dy, double dz,
                                boolean capturedLanding) {
        deepStep(q, basis, target, dx, dy, dz, capturedLanding, null);
    }
    /** Observe the actual distance-bounded curved path, not a chord between tick endpoints.
     * The observer must copy live vectors if retaining them; server integration needs no observer. */
    public static void deepStep(Vec384f q, Basis384f basis, Vec384f target, double dx, double dy, double dz,
                                boolean capturedLanding, BiConsumer<Vec384f, Basis384f> observer) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return;
        double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
        // Same bound the integrators admit: an admitted lag burst must actually advance navigation.
        if (distance < 1e-8 || distance > MovementIntegration.REPOSITION_LIMIT) return; // stationary and teleport are not semantic travel
        int steps = (int)Math.ceil(distance / MAX_STEP_BLOCKS);
        double sx = dx / steps, sy = dy / steps, sz = dz / steps;
        for (int step = 0; step < steps; step++) {
            BasisIntegrator384f.step(basis, q, target, sx * .5, sy * .5, sz * .5, ROTATION_PER_BLOCK);
            // Easing uses the current residual at each distance step, not a stale burst-start value.
            // Full physical movement still drives turning; only captured-landing translation eases.
            double remaining = Math.sqrt(q.squareDistance(target));
            double scale = capturedLanding
                    ? Math.min(1, remaining * BLOCKS_PER_SEMANTIC_UNIT * ROTATION_PER_BLOCK * .5) : 1;
            advance(q, basis, sx * scale, sy * scale, sz * scale);
            BasisIntegrator384f.step(basis, q, target, sx * .5, sy * .5, sz * .5, ROTATION_PER_BLOCK);
            if (observer != null) observer.accept(q, basis);
        }
    }
    public static void advance(Vec384f q, Basis384f basis, double dx, double dy, double dz) {
        q.add(basis.i.clone().mul((float)(dx / BLOCKS_PER_SEMANTIC_UNIT)))
                .add(basis.j.clone().mul((float)(dy / BLOCKS_PER_SEMANTIC_UNIT)))
                .add(basis.k.clone().mul((float)(dz / BLOCKS_PER_SEMANTIC_UNIT)));
    }
    /**
     * Terminal acquisition keeps every residual component reachable while the source grid aligns.
     * Driven only by real movement, <= movement/96. Continuous distance-based residual decay
     * replaces a 25%-per-tick clamp, which made identical movement depend on packet grouping.
     * No target assignment, normalization, stationary drift or source-basis-only translation.
     */
    public static void approachStep(Vec384f q, Vec384f target, double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return;
        double movement = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (movement < 1e-8 || movement > MovementIntegration.REPOSITION_LIMIT) return;
        double remaining = Math.sqrt(q.squareDistance(target));
        if (remaining < 1e-8) return;
        double speed = 1 / BLOCKS_PER_SEMANTIC_UNIT;
        double threshold = speed / APPROACH_DECAY_PER_BLOCK;
        double linearDistance = Math.min(movement, Math.max(0, (remaining - threshold) / speed));
        double residual = (remaining - linearDistance * speed)
                * Math.exp(-APPROACH_DECAY_PER_BLOCK * (movement - linearDistance));
        q.converge(target, (float)(1 - residual / remaining));
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
