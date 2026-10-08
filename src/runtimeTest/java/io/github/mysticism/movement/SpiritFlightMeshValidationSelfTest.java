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
        historyIsBounded();
        System.out.println("SpiritFlightMeshValidationSelfTest: " + checks + " checks passed (handler-level mesh validation)");
    }
}
