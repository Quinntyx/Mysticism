package io.github.mysticism.movement;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Vec3d;

/** Admitted bursts must actually advance deep navigation: every displacement the integrator
 *  classifies as chosen movement (up to the reposition bound) must move q/basis downstream.
 *  Regression for admitted 4-8-block lag bursts that the old >4-block steering gates dropped. */
public final class SteeringBurstAdvancementSelfTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void close(float expected, float actual, float tolerance, String why) {
        checks++; if (Math.abs(expected - actual) > tolerance)
            throw new AssertionError(why + ": expected ~" + expected + " got " + actual);
    }
    private static Vec384f unit(int axis) {
        float[] values = new float[io.github.mysticism.vector.EmbeddingSpace.DIMENSIONS];
        values[axis] = 1;
        return new Vec384f(values);
    }
    public static void main(String[] args) {
        burstsAdvanceQAlongTarget();
        burstsAdvanceQAndRotateBasisTowardTarget();
        approachStepConvergesOnBursts();
        admittedAndRejectedBoundsAgree();
        classifiedBurstFeedsRealAdvancement();
        System.out.println("SteeringBurstAdvancementSelfTest: " + checks + " checks passed");
    }
    private static void burstsAdvanceQAlongTarget() {
        Basis384f basis = new Basis384f();
        Vec384f q = Vec384f.ZERO();
        TraversalSteering.deepStep(q, basis, unit(0), 6, 0, 0, false);
        close(6f / 96f, q.data()[0], 1e-5f, "a 6-block burst must advance q along the basis by 6/96");
        close(0f, q.data()[1], 1e-5f, "the burst advances only along the movement axis");
        close(0f, q.data()[2], 1e-5f, "the burst advances only along the movement axis");
    }
    private static void burstsAdvanceQAndRotateBasisTowardTarget() {
        Basis384f basis = new Basis384f();
        Vec384f q = Vec384f.ZERO();
        Vec384f target = unit(1);
        TraversalSteering.deepStep(q, basis, target, 6, 0, 0, false);
        // A burst follows the curved path instead of snapping the whole translation onto the
        // final basis. Rotation decays by distance, without burst-size-dependent saturation.
        check(q.data()[1] > .04f, "the burst makes real progress toward the target");
        check(q.data()[0] > .01f, "the initial part of the curved path is retained");
        check(q.length() <= 6f / 96f + 1e-5f, "semantic travel cannot exceed physical travel/96");
        check(basis.i.dot(unit(1)) > .95f, "the basis itself rotated toward the target");
    }
    private static void approachStepConvergesOnBursts() {
        Vec384f q = unit(0);
        float before = q.length();
        TraversalSteering.approachStep(q, Vec384f.ZERO(), 6, 0, 0);
        float after = q.length();
        check(after < before, "a 6-block burst must converge toward the captured landing");
        check(after > before * 0.7f, "movement-driven convergence stays bounded, no overshoot to the target");
        Vec384f rejected = unit(0);
        TraversalSteering.approachStep(rejected, Vec384f.ZERO(), 9, 0, 0);
        check(rejected.length() == 1f, "movement beyond the admitted bound is still not semantic travel");
    }
    private static void admittedAndRejectedBoundsAgree() {
        Vec384f atBound = Vec384f.ZERO();
        TraversalSteering.deepStep(atBound, new Basis384f(), unit(0),
                MovementIntegration.REPOSITION_LIMIT, 0, 0, false);
        close((float) (MovementIntegration.REPOSITION_LIMIT / 96), atBound.data()[0], 1e-5f,
                "a window exactly at the admitted bound advances");
        Vec384f beyond = Vec384f.ZERO();
        TraversalSteering.deepStep(beyond, new Basis384f(), Vec384f.ZERO(),
                MovementIntegration.REPOSITION_LIMIT + 0.5f, 0, 0, false);
        close(0f, beyond.data()[0], 0f, "beyond the bound the classifier zeroes the window and steering rejects it");
    }
    private static void classifiedBurstFeedsRealAdvancement() {
        // The exact handoff the evolver performs: classifier output feeds deepStep and must move q.
        Vec3d measured = new Vec3d(1.5, -0.5, 0.5); // ~1.66 blocks/tick of flight accumulated over 3 lagged ticks
        MovementIntegration.Sample sample = MovementIntegration.server(measured, Vec3d.ZERO, false);
        Vec3d semantic = sample.semanticDelta();
        check(semantic.lengthSquared() > 0, "the lag burst is classified as chosen movement");
        Basis384f basis = new Basis384f();
        Vec384f q = Vec384f.ZERO();
        Vec384f direction = unit(0).mul((float)semantic.x).add(unit(1).mul((float)semantic.y)).add(unit(2).mul((float)semantic.z));
        TraversalSteering.deepStep(q, basis, direction, semantic.x, semantic.y, semantic.z, false);
        double expected = measured.length() / 96;
        check(Math.abs(q.length() - expected) < 1e-5, "the classified burst advanced q by its full semantic distance, got " + q.length());
        check(sample.semanticDelta() == sample.stateDelta() || sample.semanticDelta().equals(sample.stateDelta()),
                "clean windows integrate state and semantics identically");
    }
}
