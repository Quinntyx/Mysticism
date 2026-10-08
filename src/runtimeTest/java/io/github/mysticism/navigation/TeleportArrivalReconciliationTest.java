package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.client.spiritworld.ClientLatentPredictor;
import io.github.mysticism.client.spiritworld.PredictionContinuity;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;

/** Deterministic regressions for spirit-world teleport arrival reconciliation: an arrival delta is
 * server-decided, never chosen movement, so stale prediction/movement state must be reset at arrival
 * instead of being integrated as semantic travel or carried as residual momentum (rubber banding).
 * Drives the real epoch contract, the real client predictor gate, and the real shared steering math;
 * no server boot, no test framework. */
public final class TeleportArrivalReconciliationTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static Vec384f q() { return Vec384f.ZERO(); }

    /** Reconciled arrival integration delta: the evolver/predictor re-seed makes the arrival tick a
     * ZERO delta regardless of teleport distance, and ordinary movement resumes on the next tick. */
    private static void arrivalDeltaIsNotSemanticTravel() {
        // The pre-existing >4-block per-tick clamp only discarded LARGE teleports: a 2-block /tp was
        // integrated as real movement on both server evolver and client predictor.
        Basis384f basis = new Basis384f();
        Vec384f corrupted = q();
        TraversalSteering.advance(corrupted, basis, 2, 0, 0);
        check(corrupted.squareDistance(q()) > 0, "Un-reconciled sub-band teleport delta advanced the latent position (the defect)");

        Vec384f deep = q();
        TraversalSteering.deepStep(deep, basis, q(), 2, 0, 0);
        check(deep.squareDistance(q()) > 0, "Un-reconciled sub-band teleport delta advanced deep semantic travel (the defect)");

        // Reconciliation re-seeds the last-integrated pose with the arrival pose: delta exactly ZERO.
        Vec384f reconciled = q();
        TraversalSteering.advance(reconciled, basis, 0, 0, 0);
        TraversalSteering.deepStep(reconciled, basis, q(), 0, 0, 0);
        check(reconciled.squareDistance(q()) == 0, "Reconciled arrival integrates ZERO delta");

        // Ordinary movement immediately after arrival still integrates: no freeze.
        Vec384f walking = q();
        TraversalSteering.advance(walking, basis, 0.25, 0, 0);
        check(walking.squareDistance(q()) > 0, "Post-arrival walking still advances semantic travel");

        // Large teleports stay discarded by the existing integrable band (no double protection change).
        Vec384f band = q();
        TraversalSteering.deepStep(band, basis, q(), 30, 0, 0);
        check(band.squareDistance(q()) == 0, "Teleport-scale movement above the integrable band stays discarded");

        // Residual momentum is likewise not arrival travel: a vanilla falling residue per tick would
        // integrate as semantic travel if the server kept its stale velocity; reconciliation zeroes it.
        Vec384f residue = q();
        TraversalSteering.deepStep(residue, basis, q(), 0, -0.0784, 0);
        check(residue.squareDistance(q()) > 0, "Residual per-tick velocity would integrate as semantic travel (why server velocity must be zeroed)");
    }

    /** The synced prediction epoch advances unconditionally at every arrival: a /tp can happen
     * mid-flight with NO mode change, so mode-switch-only bumping would miss it. */
    private static void predictionEpochAdvancesOnEveryArrival() {
        SpiritNavigation nav = new SpiritNavigation();
        long initial = nav.motionEpoch();
        nav.setActive(true);
        nav.enterDeep();
        long deep = nav.motionEpoch();
        check(deep != initial, "Mode transition still advances the prediction epoch");

        nav.enterDeep(); // no mode change
        check(nav.motionEpoch() == deep, "No mode change keeps the epoch stable (only arrival/mode resets bump it)");

        nav.invalidateMotionPrediction();
        check(nav.motionEpoch() != deep, "Teleport reconciliation advances the epoch with no mode change");
        long first = nav.motionEpoch();
        nav.invalidateMotionPrediction();
        check(nav.motionEpoch() != first, "Repeated teleports each advance the epoch");
        check(nav.motionEpoch() > first, "Epoch is monotonic within its range");

        nav.shallow("minecraft:overworld", "", new Vec3d(1, 2, 3));
        long shallow = nav.motionEpoch();
        nav.invalidateMotionPrediction();
        check(nav.motionEpoch() != shallow, "Arrival reconciliation also applies in shallow mode");
        check(nav.deep() == false && nav.active(), "Reconciliation never flips the navigation mode");
    }

    /** The synced epoch survives persistence, so arrival resets are not lost across a restart. */
    private static void predictionEpochPersists() {
        SpiritNavigation nav = new SpiritNavigation();
        nav.setActive(true); nav.enterDeep(); nav.invalidateMotionPrediction();
        long epoch = nav.motionEpoch();
        NbtCompound tag = new NbtCompound();
        nav.writeToNbt(tag, null);
        SpiritNavigation restored = new SpiritNavigation();
        restored.readFromNbt(tag, null);
        check(restored.motionEpoch() == epoch, "Prediction epoch persists across restart/sync serialization");
    }

    /** The real client predictor gate: the reconciled epoch drops the stale pre-teleport delta for
     * exactly the arrival tick, then ordinary movement integration resumes. */
    private static void predictorGateDropsStaleArrivalDelta() {
        long epoch = 7;
        // Unbroken chain with the OLD epoch and a stale pre-teleport pose: this is the defect window —
        // the predictor would integrate the arrival delta as chosen movement.
        check(ClientLatentPredictor.integratesMovement(true, true, true, epoch, epoch, true, true, true),
                "Unbroken pre-arrival chain integrates movement (documents the un-reconciled defect)");
        // Reconciliation advanced the epoch BEFORE the arrival position packet: the arrival tick is
        // rejected even though the pose/mode/readiness are otherwise unchanged.
        check(!ClientLatentPredictor.integratesMovement(true, true, true, epoch, epoch + 1, true, true, true),
                "Advanced epoch discards the stale pre-teleport delta on the arrival tick");
        check(ClientLatentPredictor.integratesMovement(true, true, true, epoch + 1, epoch + 1, true, true, true),
                "The tick after arrival integrates ordinary movement again (no freeze)");
        // The reset must not mask broken chains: every guard still independently rejects.
        check(!ClientLatentPredictor.integratesMovement(false, true, true, epoch, epoch, true, true, true), "Player change still resets prediction");
        check(!ClientLatentPredictor.integratesMovement(true, false, true, epoch, epoch, true, true, true), "World change still resets prediction");
        check(!ClientLatentPredictor.integratesMovement(true, true, false, epoch, epoch, true, true, true), "Missing last pose still resets prediction");
        check(!ClientLatentPredictor.integratesMovement(true, true, true, epoch, epoch, false, true, true), "Mode change still resets prediction");
        check(!ClientLatentPredictor.integratesMovement(true, true, true, epoch, epoch, true, true, false), "Semantic unreadiness still resets prediction");
    }

    /** The reported race: the synced epoch update is processed ONE CLIENT TICK BEFORE the arrival
     * position packet. The predictor then records the new epoch against the pre-teleport pose, and a
     * sub-band arrival passes the matching-epoch gate — unless prediction is re-seeded when the
     * arrival position is actually applied (SpiritArrivalReseedMixin → PredictionContinuity). */
    private static void arrivalReseedSurvivesEpochBeforeArrival() {
        Object player = new Object(), world = new Object();
        Vec3d pose = new Vec3d(10, 64, 10);

        // Defect demonstration: without the arrival re-seed, the split-tick ordering integrates a
        // 2-block arrival as chosen movement even though the epoch advance was published first.
        PredictionContinuity unseeded = new PredictionContinuity();
        check(unseeded.integrableDelta(player, world, pose, 7, true, true) == null, "Cold start re-baselines");
        check(unseeded.integrableDelta(player, world, pose.add(0.25, 0, 0), 7, true, true) != null, "Ordinary walking integrates");
        check(unseeded.integrableDelta(player, world, pose.add(0.25, 0, 0), 8, true, true) == null, "Epoch advance re-baselines (same-tick case closed)");
        Vec3d defect = unseeded.integrableDelta(player, world, pose.add(2.25, 0, 0), 8, true, true);
        check(defect != null && Math.abs(defect.x - 2) < 1e-9, "Epoch-before-arrival ordering integrates the 2-block arrival (the reported defect)");

        // Reconciled sequence with the arrival re-seed applied by the client arrival mixin:
        PredictionContinuity reconciled = new PredictionContinuity();
        check(reconciled.integrableDelta(player, world, pose, 7, true, true) == null, "Cold start re-baselines");
        check(reconciled.integrableDelta(player, world, pose.add(0.25, 0, 0), 7, true, true) != null, "Pre-teleport walking integrates");
        // Tick with the epoch update only (position packet not yet applied): ZERO delta, new epoch
        // recorded against the pre-teleport pose — the hazard state.
        check(reconciled.integrableDelta(player, world, pose.add(0.25, 0, 0), 8, true, true) == null, "Epoch-only tick integrates nothing and re-baselines");
        // Arrival position packet applied client-side → re-seed:
        reconciled.onArrivalApplied();
        Vec3d arrival = reconciled.integrableDelta(player, world, pose.add(2.25, 0, 0), 8, true, true);
        check(arrival == null, "Applied 2-block arrival integrates NOTHING despite the matching epoch");
        // Ordinary movement resumes on the next tick — no freeze, no suppression of real walking.
        Vec3d resume = reconciled.integrableDelta(player, world, pose.add(2.5, 0, 0), 8, true, true);
        check(resume != null && Math.abs(resume.x - 0.25) < 1e-9, "Post-arrival walking integrates normally");
        // Re-seed is idempotent and safe outside teleports:
        reconciled.onArrivalApplied();
        check(reconciled.integrableDelta(player, world, pose.add(2.75, 0, 0), 8, true, true) == null, "Repeated re-seed keeps re-baselining safely");
        // The re-seed must not mask the epoch gate: a STALE epoch still re-baselines after it.
        reconciled.onArrivalApplied();
        check(reconciled.integrableDelta(player, world, pose.add(3.0, 0, 0), 8, true, true) == null, "Re-seeded chain re-baselines once, then resumes");
        check(reconciled.integrableDelta(player, world, pose.add(3.25, 0, 0), 8, true, true) != null, "Movement after the re-seeded tick integrates");
    }

    public static void main(String[] args) {
        arrivalDeltaIsNotSemanticTravel();
        predictionEpochAdvancesOnEveryArrival();
        predictionEpochPersists();
        predictorGateDropsStaleArrivalDelta();
        arrivalReseedSurvivesEpochBeforeArrival();
        System.out.println("TeleportArrivalReconciliationTest: " + checks + " checks passed");
    }
}
