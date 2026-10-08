package io.github.mysticism.movement;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Free-flight movement regressions: rotating observer-local projection geometry sweeps through
 * a flying body between frame publications. The collision core must ease out of embedding during
 * flight (bounded displacement) instead of violently teleporting the player, must still resolve
 * walking embedding fully, and must keep ordinary wall sliding and empty-space flight untouched.
 * These checks run against the real production collision core, not a reimplementation. */
public final class SpiritFlightRubberBandSelfTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void near(double a, double b, String why) { checks++; if (Math.abs(a - b) > 1e-6) throw new AssertionError(why + " (" + a + " vs " + b + ")"); }

    private static final Vec3d UNIT_X = new Vec3d(1, 0, 0), UNIT_Y = new Vec3d(0, 1, 0), UNIT_Z = new Vec3d(0, 0, 1);
    private static final double STEP = 0.6;

    private static TerrainMeshFrame.Cell cell(long key, double x, double y, double z, double sx, double sy, double sz) {
        return new TerrainMeshFrame.Cell(key, 0, "flight-test", new Vec3d(x, y, z), new Vec3d(x, y, z),
                new Vec3d(sx, sy, sz), UNIT_X, UNIT_Y, UNIT_Z, 0xFFFFFFFF, 0, 1f, List.of(new Box(0, 0, 0, sx, sy, sz)));
    }
    /** Unit-block cells (the granularity bodyClear/transitionClear validate). */
    private static List<TerrainMeshFrame.Cell> cube(double x, double y, double z, int s) {
        var cells = new java.util.ArrayList<TerrainMeshFrame.Cell>();
        for (int i = 0; i < s; i++) for (int j = 0; j < s; j++) for (int k = 0; k < s; k++)
            cells.add(cell(1000 + i + 16 * j + 256 * k, x + i, y + j, z + k, 1, 1, 1));
        return cells;
    }
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cells));
    }
    private static final Box BODY = new Box(1.2, 0.6, 1.2, 1.8, 2.4, 1.8); // standard 0.6 x 1.8 player box
    private static Vec3d predicted(MeshCollision.Index index, Box body, Vec3d wanted, boolean flying) {
        return MeshCollision.predicted(index, body, wanted, flying, false, STEP);
    }

    private static void flightEmbeddingIsEasedNotTeleported() {
        // A 3x3x3 solid block region has swept through the hovering flyer: the body is embedded >1 block deep.
        MeshCollision.Index index = new MeshCollision.Index(frame(cube(0, 0, 0, 3).toArray(TerrainMeshFrame.Cell[]::new)));
        Vec3d moved = predicted(index, BODY, Vec3d.ZERO, true);
        check(moved.length() > 1e-4, "embedded flight must still be pushed toward the surface");
        check(moved.length() <= 0.5 + 1e-9, "flight depenetration is bounded per move, not a violent shove");
        check(!MeshCollision.bodyClear(index.frame, BODY.offset(moved)), "bounded ease leaves the body inside; it does not teleport it out");
        // Sustained outward input can never be converted into a violent opposite shove (the free-flight
        // rubber band): displacement stays within the ease cap plus the requested movement.
        Vec3d escaped = predicted(index, BODY, new Vec3d(-2, 0, 0), true);
        check(escaped.x <= 0.5 + 1e-9, "outward input is never flung backwards beyond the ease cap");
        check(escaped.x >= -2.5 - 1e-9 && escaped.length() <= 2.5 + 1e-9, "outward flight movement stays within ease cap plus input");
        // Repeated sustained moves never exceed the per-move bound at any point of the sequence.
        Vec3d position = Vec3d.ZERO;
        for (int move = 0; move < 16; move++) {
            Vec3d step = predicted(index, BODY.offset(position), new Vec3d(-2, 0, 0), true);
            check(step.length() <= 2.5 + 1e-9, "sustained flight remains bounded per move during embedding");
            position = position.add(step);
        }
    }

    private static void walkingEmbeddingStillFullyResolves() {
        MeshCollision.Index index = new MeshCollision.Index(frame(cube(0, 0, 0, 3).toArray(TerrainMeshFrame.Cell[]::new)));
        // The minimal escape from this embedding exceeds the flight cap; the walking path must keep
        // its unbounded (up to 4 blocks) resolution while flight is eased.
        Vec3d walked = index.depenetrate(BODY, 4);
        check(walked.length() > 0.5, "walking depenetration exceeds the flight cap (full resolution retained)");
        Vec3d flown = index.depenetrate(BODY, 0.5);
        check(flown.length() <= 0.5 + 1e-9, "the flight ease cap bounds depenetration itself");
        Vec3d walkedMove = predicted(index, BODY, Vec3d.ZERO, false);
        Vec3d flownMove = predicted(index, BODY, Vec3d.ZERO, true);
        check(walkedMove.length() > flownMove.length(), "walking embeds resolve further per move than eased flight");
    }

    private static void flightWallSlideUnchanged() {
        // Solid wall occupying world x in [2,5]; flyer slides against it, no pass-through, no backward shove.
        MeshCollision.Index index = new MeshCollision.Index(frame(cell(1, 2, 0, 0, 3, 3, 3)));
        Box outside = new Box(1, 0.5, 1, 1.6, 2.3, 1.6);
        Vec3d wanted = new Vec3d(4, 0, 0);
        Vec3d moved = predicted(index, outside, wanted, true);
        check(moved.x < wanted.x, "flight is still clipped by custom terrain meshes");
        check(outside.offset(moved).maxX <= 2 + 1e-3, "flight cannot penetrate a wall by sliding");
        check(moved.x > 0.35, "wall contact does not shove the flyer backwards");
        // Same move while walking must behave identically (no step-up assist mid-air irrelevant here: onGround=false).
        Vec3d walked = MeshCollision.predicted(index, outside, wanted, false, false, STEP);
        check(Math.abs(walked.x - moved.x) < 1e-6, "airborne walking and flying slide identically");
    }

    private static void flightWithoutSurfacesIsUntouched() {
        MeshCollision.Index empty = new MeshCollision.Index(frame());
        Vec3d wanted = new Vec3d(0.31, -0.12, 0.2);
        check(predicted(empty, BODY, wanted, true).equals(wanted), "empty spirit space never alters flight prediction");
        Vec3d tiny = predicted(empty, BODY, new Vec3d(1, 1, 1), true);
        check(tiny.equals(new Vec3d(1, 1, 1)), "slow free flight is unmodified without geometry");
    }

    private static void indexRejectsInvisibleCollision() {
        // Fog-faded cells (opacity <= 0) are both invisible AND non-colliding, as before.
        TerrainMeshFrame.Cell faded = new TerrainMeshFrame.Cell(9, 0, "flight-test", Vec3d.ZERO, Vec3d.ZERO,
                new Vec3d(3, 3, 3), UNIT_X, UNIT_Y, UNIT_Z, 0xFFFFFFFF, 0, 0f, List.of(new Box(0, 0, 0, 3, 3, 3)));
        MeshCollision.Index index = new MeshCollision.Index(frame(faded));
        check(predicted(index, BODY, Vec3d.ZERO, true).equals(Vec3d.ZERO), "invisible faded geometry does not push the flyer");
    }

    public static void main(String[] args) {
        flightEmbeddingIsEasedNotTeleported();
        walkingEmbeddingStillFullyResolves();
        flightWallSlideUnchanged();
        flightWithoutSurfacesIsUntouched();
        indexRejectsInvisibleCollision();
        System.out.println("SpiritFlightRubberBandSelfTest: " + checks + " checks passed (real collision core)");
    }
}
