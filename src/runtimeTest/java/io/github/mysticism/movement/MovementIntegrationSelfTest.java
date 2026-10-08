package io.github.mysticism.movement;

import io.github.mysticism.navigation.MovementIntegration;
import net.minecraft.util.math.Vec3d;

/** Movement classification regressions: chosen bursts integrate in full; corrections and
 * teleports of any size are excluded from semantic advance by provenance, not magnitude. */
public final class MovementIntegrationSelfTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void checkEquals(Vec3d expected, Vec3d actual, String why) {
        checks++; if (expected.x != actual.x || expected.y != actual.y || expected.z != actual.z)
            throw new AssertionError(why + ": expected " + expected + " got " + actual);
    }
    private static final Vec3d WALK = new Vec3d(0.18, 0, 0.05);
    private static final Vec3d BURST = new Vec3d(1.2, -0.4, 0.7); // lag spike: 3+ ticks of flight in one window
    private static final Vec3d CORRECTION = new Vec3d(0.02, 0.1, -0.01);
    public static void main(String[] args) {
        cleanMovementIntegrates();
        lagBurstsAreNotDiscarded();
        correctionsDoNotAdvanceSemantics();
        teleportsNeverIntegrateAtAnySize();
        impossibleWindowsAreRepositioning();
        clientPredictionSubtractsItsOwnCorrection();
        finiteAndNullWindowsAreInert();
        System.out.println("MovementIntegrationSelfTest: " + checks + " checks passed");
    }
    private static void cleanMovementIntegrates() {
        var sample = MovementIntegration.server(WALK, Vec3d.ZERO, false);
        checkEquals(WALK, sample.stateDelta(), "clean window must drive state machines");
        checkEquals(WALK, sample.semanticDelta(), "clean chosen movement must integrate semantically");
        check(MovementIntegration.clientSemantic(WALK, Vec3d.ZERO, false) != null
                && MovementIntegration.clientSemantic(WALK, Vec3d.ZERO, false).lengthSquared() > 0,
                "clean client window must not be inert");
        check(MovementIntegration.Sample.ZERO.stateDelta().equals(Vec3d.ZERO)
                && MovementIntegration.Sample.ZERO.semanticDelta().equals(Vec3d.ZERO),
                "the zero sample must be inert");
    }
    private static void lagBurstsAreNotDiscarded() {
        var sample = MovementIntegration.server(BURST, Vec3d.ZERO, false);
        checkEquals(BURST, sample.stateDelta(), "burst below the reposition limit is real movement");
        checkEquals(BURST, sample.semanticDelta(), "bursts must integrate instead of stalling then catching up");
        check(MovementIntegration.clientSemantic(BURST, Vec3d.ZERO, false).equals(BURST),
                "client bursts integrate in full");
        // The old integration dropped everything above 4 blocks/tick; 8 is the reposition bound.
        Vec3d edge = new Vec3d(8.0, 0, 0);
        checkEquals(edge, MovementIntegration.server(edge, Vec3d.ZERO, false).semanticDelta(),
                "a window exactly at the reposition bound is still integrable");
    }
    private static void correctionsDoNotAdvanceSemantics() {
        var sample = MovementIntegration.server(WALK, CORRECTION, false);
        checkEquals(WALK, sample.stateDelta(), "correction windows keep physical state machines alive");
        checkEquals(Vec3d.ZERO, sample.semanticDelta(),
                "body-overlap corrections must pause semantic advance instead of chasing the dispute");
    }
    private static void teleportsNeverIntegrateAtAnySize() {
        for (Vec3d measured : new Vec3d[] {WALK, BURST, new Vec3d(3, 64, 0)}) {
            var sample = MovementIntegration.server(measured, Vec3d.ZERO, true);
            checkEquals(Vec3d.ZERO, sample.stateDelta(), "teleport window must not run state machines");
            checkEquals(Vec3d.ZERO, sample.semanticDelta(), "teleports are not chosen movement at any size");
            check(MovementIntegration.clientSemantic(measured, Vec3d.ZERO, true).equals(Vec3d.ZERO),
                    "client correction windows are inert");
        }
    }
    private static void impossibleWindowsAreRepositioning() {
        Vec3d beyond = new Vec3d(9, 0, 0);
        var sample = MovementIntegration.server(beyond, Vec3d.ZERO, false);
        checkEquals(Vec3d.ZERO, sample.semanticDelta(), "beyond the bound is repositioning, never chosen travel");
        checkEquals(Vec3d.ZERO, sample.stateDelta(), "beyond the bound must not drive state machines");
        check(MovementIntegration.clientSemantic(beyond, Vec3d.ZERO, false).equals(Vec3d.ZERO),
                "client windows beyond the bound are inert");
    }
    private static void clientPredictionSubtractsItsOwnCorrection() {
        Vec3d semantic = MovementIntegration.clientSemantic(WALK, CORRECTION, false);
        checkEquals(WALK.subtract(CORRECTION), semantic, "client semantic advance is the exact chosen remainder");
        // No displacement is lost or double counted: semantic + correction reconstructs the window.
        checkEquals(WALK, semantic.add(CORRECTION), "correction accounting must reconstruct the window");
        Vec3d pushOnly = MovementIntegration.clientSemantic(CORRECTION, CORRECTION, false);
        checkEquals(Vec3d.ZERO, pushOnly, "a window that was entirely collision response integrates nothing");
    }
    private static void finiteAndNullWindowsAreInert() {
        Vec3d nan = new Vec3d(Double.NaN, 0, 0);
        checkEquals(Vec3d.ZERO, MovementIntegration.clientSemantic(nan, Vec3d.ZERO, false), "NaN windows are inert");
        checkEquals(Vec3d.ZERO, MovementIntegration.clientSemantic(WALK, new Vec3d(0, Double.POSITIVE_INFINITY, 0), false),
                "nonfinite corrections are inert");
        checkEquals(Vec3d.ZERO, MovementIntegration.server(nan, Vec3d.ZERO, false).semanticDelta(), "NaN server windows are inert");
        checkEquals(Vec3d.ZERO, MovementIntegration.server(null, Vec3d.ZERO, false).semanticDelta(), "null measured windows are inert");
        checkEquals(Vec3d.ZERO, MovementIntegration.server(WALK, null, false).semanticDelta(), "null correction windows are inert");
        checkEquals(Vec3d.ZERO, MovementIntegration.Sample.ZERO.semanticDelta(), "zero sample stays zero");
    }
}
