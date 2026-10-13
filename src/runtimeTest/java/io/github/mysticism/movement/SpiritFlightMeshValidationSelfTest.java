package io.github.mysticism.movement;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.MeshMovementValidation;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Packet-handler-level mesh validation regressions for accepted deep-flight prediction. Vanilla
 * overwrites the collision-resolved position with packet coordinates, so the acceptance decision
 * itself must be mesh-aware: a claimed move is accepted only when the player's own recently
 * published frame reproduces it (stale/mismatched client frames tolerated), while moves crossing
 * Mysticism mesh walls under every recent frame are rejected. These checks exercise the exact
 * decision the handler redirect applies to the claimed packet coordinates, including the bounded
 * frame history used for lag compensation. */
public final class SpiritFlightMeshValidationSelfTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final Vec3d UNIT_X = new Vec3d(1, 0, 0), UNIT_Y = new Vec3d(0, 1, 0), UNIT_Z = new Vec3d(0, 0, 1);
    /** 0.6 x 1.8 player body standing just before a wall occupying world x in [2,3]. */
    private static final Box BODY = new Box(1, 0.5, 1, 1.6, 2.3, 1.6);
    private static final Vec3d START_FEET = new Vec3d(1.3, 0.5, 1.3);

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cells));
    }
    private static TerrainMeshFrame walled() {
        return frame(new TerrainMeshFrame.Cell(1, 0, "validation-test", new Vec3d(2, 0, 0), new Vec3d(2, 0, 0),
                new Vec3d(1, 3, 3), UNIT_X, UNIT_Y, UNIT_Z, 0xFFFFFFFF, 0, 1f, List.of(new Box(0, 0, 0, 1, 3, 3))));
    }
    private static TerrainMeshFrame open() { return frame(); }
    private static boolean allows(UUID player, Vec3d claimedFeet) {
        return MeshMovementValidation.allowsMeshMove(player, BODY, claimedFeet, true);
    }

    private static void emptyHistoryAllows() {
        UUID player = UUID.randomUUID();
        check(allows(player, new Vec3d(5.3, 0.5, 1.3)), "no published geometry yet: no fabricated rejection");
        MeshMovementValidation.record(player, walled());
        MeshMovementValidation.clear(player);
        check(allows(player, new Vec3d(5.3, 0.5, 1.3)), "cleared history has nothing to validate against");
    }

    private static void wallCrossingRejected() {
        UUID player = UUID.randomUUID();
        MeshMovementValidation.record(player, walled());
        check(!allows(player, new Vec3d(5.3, 0.5, 1.3)), "a move crossing a Mysticism mesh wall is rejected");
        check(!allows(player, new Vec3d(12, 0.5, 1.3)), "long wall-crossing moves are rejected");
        check(allows(player, new Vec3d(1.8, 0.5, 1.3)), "movement stopping short of the wall remains admissible");
        check(allows(player, START_FEET), "hovering in place is never rejected");
        check(allows(player, new Vec3d(0.5, 0.5, 1.3)), "moving away from the wall remains admissible");
    }

    private static void safePredictionDivergenceTolerated() {
        UUID player = UUID.randomUUID();
        MeshMovementValidation.record(player, walled());
        // Vanilla-class divergence (<=0.25 blocks, e.g. sliding re-simulation residue) stays accepted.
        check(allows(player, new Vec3d(1.9, 0.5, 1.3)), "small prediction divergence is tolerated, not rubber-banded");
        check(!allows(player, new Vec3d(3.0, 0.5, 1.3)), "divergence beyond the tolerance into the wall is rejected");
    }

    private static void mismatchedFramesTolerated() {
        UUID player = UUID.randomUUID();
        // The client predicted against the older open frame; the server has since published a wall.
        MeshMovementValidation.record(player, open());
        MeshMovementValidation.record(player, walled());
        check(allows(player, new Vec3d(5.3, 0.5, 1.3)), "a move the player's own older published frame reproduces is accepted despite the newer server frame");
        // Reverse order: the wall existed then, open space now - the newer frame also admits, and the
        // historical frame must never EXPAND what is allowed beyond admissibility under any recent frame.
        UUID other = UUID.randomUUID();
        MeshMovementValidation.record(other, walled());
        MeshMovementValidation.record(other, open());
        check(allows(other, new Vec3d(5.3, 0.5, 1.3)), "open current frame admits the move");
        check(allows(other, new Vec3d(1.8, 0.5, 1.3)), "short moves stay admissible under the historical wall frame");
    }

    private static void sustainedMovementAndReversals() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var clientFrame = walled();
        var index = new MeshCollision.Index(clientFrame);
        Box body = BODY;
        int wallContacts = 0;
        try {
            MeshMovementValidation.record(other, walled());
            // Six sustained segments, repeatedly reversing horizontal/vertical input while a
            // newer server frame disagrees with the older published frame used by prediction.
            for (int segment = 0; segment < 6; segment++) {
                Vec3d input = segment % 2 == 0 ? new Vec3d(.08, .01, .01) : new Vec3d(-.08, -.01, -.01);
                for (int tick = 0; tick < 32; tick++) {
                    MeshMovementValidation.record(player, clientFrame);
                    MeshMovementValidation.record(player, frame(new TerrainMeshFrame.Cell(1, 0, "validation-test",
                            new Vec3d(2, 0, 0), new Vec3d(1.5, 0, 0), new Vec3d(1, 3, 3),
                            UNIT_X, UNIT_Y, UNIT_Z, 0xFFFFFFFF, 0, 1f, List.of(new Box(0, 0, 0, 1, 3, 3)))));
                    Vec3d predicted = MeshCollision.predicted(index, body, input, true, false, 0);
                    if (predicted.x < input.x - 1e-6) wallContacts++;
                    Vec3d feet = new Vec3d((body.minX + body.maxX) / 2, body.minY, (body.minZ + body.maxZ) / 2);
                    check(MeshMovementValidation.allowsMeshMove(player, body, feet.add(predicted), true),
                            "published-frame prediction accepted throughout held input and reversal: " + segment + "/" + tick);
                    body = body.offset(predicted); // accepted packets advance, never reset to their pre-move position
                }
            }
            check(wallContacts > 0, "sustained flight actually reaches and slides along a custom mesh wall");
            check(!allows(other, new Vec3d(5.3, .5, 1.3)), "another player's history cannot authorize crossing its wall");
        } finally {
            MeshMovementValidation.clear(player);
            MeshMovementValidation.clear(other);
        }
    }

    private static TerrainMeshFrame shiftedWall(double x) {
        return frame(new TerrainMeshFrame.Cell(1, 0, "validation-test", Vec3d.ZERO, new Vec3d(x, 0, 0),
                new Vec3d(1, 3, 3), UNIT_X, UNIT_Y, UNIT_Z, 0xFFFFFFFF, 0, 1f,
                List.of(new Box(0, 0, 0, 1, 3, 3))));
    }

    private static Vec3d feet(Box body) {
        return new Vec3d(body.getCenter().x, body.minY, body.getCenter().z);
    }

    private static void frameTransitionPredictionAndAdmission() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var older = shiftedWall(0);
        var current = shiftedWall(1);
        var oldIndex = new MeshCollision.Index(older);
        var currentIndex = new MeshCollision.Index(current);
        // Publication is safe while the player is beside the wall (outside its Z footprint).
        // A preceding packet predicted under the old frame then moves into the new wall's footprint.
        Box original = new Box(1.2, .5, -1, 1.8, 2.3, -.4);
        try {
            check(MeshCollision.bodyClear(older, original) && MeshCollision.bodyClear(current, original),
                    "both published frames initially leave the player clear");
            check(MeshCollision.transitionClear(older, current, original), "frame transition is permitted by production publication guard");
            MeshMovementValidation.record(player, older);
            MeshMovementValidation.record(player, current);
            MeshMovementValidation.record(other, older);
            Vec3d preceding = MeshCollision.predicted(oldIndex, original, new Vec3d(0, 0, 1.5), true, false, 0);
            check(preceding.distanceTo(new Vec3d(0, 0, 1.5)) < 1e-9, "stale-frame preceding movement is unobstructed");
            check(MeshMovementValidation.allowsMeshMove(player, original, feet(original).add(preceding), true),
                    "preceding stale-frame movement is actually admitted");
            Box embedded = original.offset(preceding);
            check(!MeshCollision.bodyClear(current, embedded), "accepted movement enters the newer frame's wall");
            Vec3d correction = MeshCollision.predicted(currentIndex, embedded, Vec3d.ZERO, true, false, 0);
            check(correction.distanceTo(new Vec3d(-.5, 0, 0)) < 1e-9, "honest client produces the bounded half-block correction");
            // Guard the failure fixture: feeding a resolved claim back as raw input doubles its
            // correction under the same frame; the older frame clips it at the neighboring wall.
            Vec3d doubled = MeshCollision.predicted(currentIndex, embedded, correction, true, false, 0);
            Vec3d olderReplay = MeshCollision.predicted(oldIndex, embedded, correction, true, false, 0);
            check(doubled.distanceTo(new Vec3d(-1, 0, 0)) < 1e-9, "raw-input replay incorrectly doubles correction");
            check(Math.abs(olderReplay.x - (-.19999)) < 1e-6, "older frame cannot mask the correction replay defect");
            check(!MeshMovementValidation.allowsMeshMove(other, embedded, feet(embedded).add(correction), true),
                    "a different player's older-only history still rejects movement through its wall");
            check(MeshMovementValidation.allowsMeshMove(player, embedded, feet(embedded).add(correction), true),
                    "combined prediction/admission accepts the honest frame-transition correction without teleport-back");
            // The same correction must work with only the matching frame, not rely on stale admission.
            MeshMovementValidation.clear(player);
            MeshMovementValidation.record(player, current);
            check(MeshMovementValidation.allowsMeshMove(player, embedded, feet(embedded).add(correction), true),
                    "matching frame alone admits its already-resolved correction");
            for (Vec3d input : List.of(Vec3d.ZERO, new Vec3d(-.4, 0, 0), new Vec3d(.4, 0, 0),
                    new Vec3d(-.2, .07, .08), new Vec3d(-.12, .1, -.06))) {
                Vec3d resolved = MeshCollision.predicted(currentIndex, embedded, input, true, false, 0);
                check(MeshCollision.replayClaimed(currentIndex, embedded, resolved, true).distanceTo(resolved) < 1e-9,
                        "resolved replay reproduces correction plus clipped/reversed/diagonal input exactly: " + input);
                check(MeshMovementValidation.allowsMeshMove(player, embedded, feet(embedded).add(resolved), true),
                        "embedded prediction/admission accepts correction plus actual input: " + input);
            }
            check(!MeshMovementValidation.allowsMeshMove(player, embedded, feet(embedded).add(4, 0, 0), true),
                    "being embedded must not authorize a forged displacement through the wall");
            // Continue from the accepted coordinates, easing out and then repeatedly reversing
            // real input against the wall. Exercise prediction and admission together every move.
            Box body = embedded.offset(correction);
            int contacts = 0;
            for (int tick = 0; tick < 96; tick++) {
                Vec3d input = tick < 2 ? Vec3d.ZERO : new Vec3d((tick / 12) % 2 == 0 ? .12 : -.12, 0, .01);
                Vec3d predicted = MeshCollision.predicted(currentIndex, body, input, true, false, 0);
                if (input.x > 0 && predicted.x < input.x - 1e-6) contacts++;
                check(predicted.length() <= .5 + input.length() + 1e-9, "prediction remains bounded after correction");
                check(MeshMovementValidation.allowsMeshMove(player, body, feet(body).add(predicted), true),
                        "correction followed by sustained movement/reversal is admitted: " + tick);
                body = body.offset(predicted);
            }
            check(contacts > 0, "continued movement reaches the wall; collision remains active");
            check(MeshCollision.bodyClear(current, body), "continued accepted motion escapes embedding without tunnelling");
            check(!MeshMovementValidation.allowsMeshMove(player, body, feet(body).add(4, 0, 0), true),
                    "correction reconciliation must not authorize crossing the wall");
        } finally {
            MeshMovementValidation.clear(player);
            MeshMovementValidation.clear(other);
        }
    }

    private static void historyIsBounded() {
        UUID player = UUID.randomUUID();
        MeshMovementValidation.record(player, open());
        for (int i = 0; i < 24; i++) MeshMovementValidation.record(player, walled());
        check(MeshMovementValidation.historySize(player) == MeshMovementValidation.HISTORY,
                "frame history is bounded (" + MeshMovementValidation.HISTORY + ")");
        check(!allows(player, new Vec3d(5.3, 0.5, 1.3)), "moves admissible only under an evicted frame are rejected");
        check(allows(player, new Vec3d(1.8, 0.5, 1.3)), "retained frames still validate ordinary movement");
        MeshMovementValidation.record(player, walled());
        check(MeshMovementValidation.historySize(player) == MeshMovementValidation.HISTORY, "identical consecutive frames are not duplicated");
    }

    public static void main(String[] args) {
        emptyHistoryAllows();
        wallCrossingRejected();
        safePredictionDivergenceTolerated();
        mismatchedFramesTolerated();
        sustainedMovementAndReversals();
        frameTransitionPredictionAndAdmission();
        historyIsBounded();
        System.out.println("SpiritFlightMeshValidationSelfTest: " + checks + " checks passed (handler-level mesh validation)");
    }
}
