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
