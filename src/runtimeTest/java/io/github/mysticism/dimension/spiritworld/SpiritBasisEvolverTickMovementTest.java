package io.github.mysticism.dimension.spiritworld;

import net.minecraft.util.math.Vec3d;

/**
 * Regression for jump takeoff immediately after a shallow acquisition commit (walk request
 * landing / captured landing). The acquisition bumps the navigation motionEpoch, so on the next
 * evolver tick the recorded epoch differs. The evolver must keep TWO separate movement inputs:
 * the bounded PHYSICAL delta (fed to SpiritNavigationService.update, whose unsupported branch
 * derives the ascending-takeoff jump grace from delta.y > .01) and the epoch-filtered SEMANTIC
 * delta (fed only to q/basis integration, matching ClientLatentPredictor). Filtering the physical
 * delta would zero the jump ascent on exactly that tick, make the navigation controller treat the
 * jumping player as non-jumping, and instantly restore deep flight over ground the player just
 * acquired. Physical movement never depends on epochs; semantic movement never crosses one.
 */
public final class SpiritBasisEvolverTickMovementTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static void zero(Vec3d delta, String message) { check(delta.equals(Vec3d.ZERO), message); }

    public static void main(String[] args) {
        jumpTakeoffImmediatelyAfterShallowAcquisition();
        sameEpochContinuity();
        reanchoredInputs();
        correctionAccountedWindows();
        productionCallbackUsesAccountedWindow();
        System.out.println("SpiritBasisEvolverTickMovementTest passed: " + checks + " checks");
    }

    private static void jumpTakeoffImmediatelyAfterShallowAcquisition() {
        // Walk-request acquisition commits at tick T: nav.shallow bumps motionEpoch 4 -> 5 while
        // the tracked position is the acquired carrier pose. At tick T+1 the player jumps.
        Vec3d acquired = new Vec3d(12.5, 64, -3.25);
        Vec3d ascending = new Vec3d(12.52, 64.42, -3.2); // ordinary jump ascent + small stride
        SpiritBasisEvolver.TickMovement movement =
                SpiritBasisEvolver.tickMovement(acquired, ascending, 4L, 5L);
        // The ascending takeoff signal must SURVIVE the epoch change so update()'s unsupported
        // branch grants ordinary jump grace instead of restoring deep flight over acquired ground.
        check(movement.physical().y > .01, "physical delta must retain the ascending jump takeoff after a shallow commit");
        check(movement.physical().equals(ascending.subtract(acquired)), "physical delta is the bounded real movement");
        // The same tick must not integrate semantic movement across the transition.
        zero(movement.semantic(), "semantic delta must re-anchor across the acquisition epoch change");
    }

    private static void sameEpochContinuity() {
        Vec3d last = new Vec3d(0, 64, 0), now = new Vec3d(.3, 64.05, -.2);
        Vec3d step = now.subtract(last);
        // Within one epoch both inputs are the same bounded movement.
        SpiritBasisEvolver.TickMovement continuous =
                SpiritBasisEvolver.tickMovement(last, now, 7L, 7L);
        check(continuous.physical().equals(step) && continuous.semantic().equals(step),
                "same-epoch ticks integrate one identical bounded movement");
        // Physical NEVER depends on the epoch: the same positions yield the same physical delta
        // regardless of recorded/current epoch values (epoch filtering is semantic-only).
        for (Long recorded : new Long[]{null, 0L, 7L, 99L}) {
            for (long epoch : new long[]{0, 7, 100}) {
                SpiritBasisEvolver.TickMovement m = SpiritBasisEvolver.tickMovement(last, now, recorded, epoch);
                check(m.physical().equals(step), "physical delta is epoch-agnostic");
            }
        }
        // A walk-off-the-edge descent (negative y, bounded) also stays physical-only.
        Vec3d descending = new Vec3d(.2, -.15, 0);
        SpiritBasisEvolver.TickMovement walked =
                SpiritBasisEvolver.tickMovement(now, now.add(descending), 7L, 8L);
        check(walked.physical().y < 0 && walked.semantic().equals(Vec3d.ZERO),
                "edge walk keeps its physical signal but integrates no semantic movement across the transition");
    }

    private static void correctionAccountedWindows() {
        Vec3d before = new Vec3d(0, 64, 0), burst = new Vec3d(6, 0, 0);
        var accepted = SpiritBasisEvolver.tickMovement(before, before.add(burst), 7L, 7L, Vec3d.ZERO, false);
        check(accepted.physical().equals(burst) && accepted.semantic().equals(burst),
                "actual evolver admits the full 4-8-block chosen window");
        check(!accepted.requiresReconciliation(), "ordinary burst is not forced authority");
        Vec3d jump = new Vec3d(.2, .42, 0);
        for (long epoch : new long[]{7, 8}) {
            var corrected = SpiritBasisEvolver.tickMovement(before, before.add(jump), 7L, epoch,
                    new Vec3d(0, .02, 0), false);
            check(corrected.physical().squaredDistanceTo(jump) < 1e-20, "correction must not erase jump state decisions");
            zero(corrected.semantic(), "overlap correction is not semantic travel even in the same epoch");
            check(corrected.requiresReconciliation(), "overlap forces unchanged authoritative pose delivery");
        }
        for (Vec3d displacement : new Vec3d[]{Vec3d.ZERO, new Vec3d(.05, 0, 0), burst}) {
            var teleport = SpiritBasisEvolver.tickMovement(before, before.add(displacement), 7L, 7L,
                    Vec3d.ZERO, true);
            zero(teleport.physical(), "even sub-block teleport is not chosen navigation");
            zero(teleport.semantic(), "even sub-block teleport is not chosen semantic travel");
            check(teleport.requiresReconciliation(), "re-baselined teleport still retires stale prediction");
        }
    }

    private static void productionCallbackUsesAccountedWindow() {
        try {
            String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/io/github/mysticism/dimension/spiritworld/SpiritBasisEvolver.java"));
            check(code.contains("tickMovement(last, now, recorded, epoch, correction, teleported)"),
                    "real server callback consumes drained provenance, not the compatibility overload");
            check(code.contains("boolean reconcile = reconcileSuppressedWindow(new MovementIntegration.Sample(")
                            && code.contains("if (reconcile || server.getTicks() % 4 == 0)"),
                    "real correction window publishes forced authority immediately, not only at cadence");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static void reanchoredInputs() {
        // First tracked tick after entry/reconnect (no last position): both inputs re-anchor.
        SpiritBasisEvolver.TickMovement fresh =
                SpiritBasisEvolver.tickMovement(null, new Vec3d(1, 64, 1), null, 3L);
        zero(fresh.physical(), "tracking start re-anchors physical movement");
        zero(fresh.semantic(), "tracking start re-anchors semantic movement");
        // Teleport-sized movement is never navigation or semantic travel.
        SpiritBasisEvolver.TickMovement teleport =
                SpiritBasisEvolver.tickMovement(Vec3d.ZERO, new Vec3d(64, 0, 0), 7L, 7L);
        zero(teleport.physical(), "teleport is not physical navigation movement");
        zero(teleport.semantic(), "teleport is not semantic travel");
        // Nonfinite movement re-anchors both.
        SpiritBasisEvolver.TickMovement nonfinite =
                SpiritBasisEvolver.tickMovement(Vec3d.ZERO, new Vec3d(Double.NaN, 0, 0), 7L, 7L);
        zero(nonfinite.physical(), "nonfinite movement is not navigation");
        zero(nonfinite.semantic(), "nonfinite movement is not semantic travel");
    }
}
