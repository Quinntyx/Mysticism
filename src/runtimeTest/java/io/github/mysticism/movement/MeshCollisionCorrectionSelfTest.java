package io.github.mysticism.movement;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Collision-response accounting regressions: the swept resolver reports depenetration separately
 *  from chosen travel against real affine geometry, and per-player accumulators drain cleanly on
 *  both sides. Real SAT geometry is exercised, never a placeholder. */
public final class MeshCollisionCorrectionSelfTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void close(double expected, double actual, double tolerance, String why) {
        checks++; if (Math.abs(expected - actual) > tolerance)
            throw new AssertionError(why + ": expected ~" + expected + " got " + actual);
    }
    private static Vec3d correction(MeshCollision.Index index, Box body, Vec3d wanted) {
        Vec3d[] correction = new Vec3d[1];
        MeshCollision.resolve(index, body, wanted, correction);
        return correction[0] == null ? Vec3d.ZERO : correction[0];
    }
    private static MeshCollision.Index floorFrame() {
        var materials = List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of()));
        // One solid 8x1x8 source cell with an axis-aligned unit collision shape: a real affine SAT floor.
        var floor = new TerrainMeshFrame.Cell(1L, 0, "movement-test-landmark",
                new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(8, 1, 8),
                new Vec3d(8, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 8),
                0xA0A0A0, 15, 1.0f, List.of(new Box(0, 0, 0, 1, 1, 1)));
        return new MeshCollision.Index(new TerrainMeshFrame(1, true, "minecraft:overworld",
                Vec3d.ZERO, Vec3d.ZERO, materials, List.of(floor)));
    }
    public static void main(String[] args) {
        MeshCollision.Index index = floorFrame();
        travelWithoutOverlapReportsNoCorrection(index);
        overlappingBodiesReportTheDepenetration(index);
        deepOverlapReportsTheFullEscape(index);
        fallTravelStopsOnTheRealFloor(index);
        accumulatorsAccumulatePerSideAndDrain(index);
        System.out.println("MeshCollisionCorrectionSelfTest: " + checks + " checks passed");
    }
    private static void travelWithoutOverlapReportsNoCorrection(MeshCollision.Index index) {
        Box above = new Box(1.7, 1.5, 1.7, 2.3, 3.3, 2.3);
        Vec3d wanted = new Vec3d(0.5, -1.2, 0);
        Vec3d[] correction = new Vec3d[1];
        Vec3d result = MeshCollision.resolve(index, above, wanted, correction);
        check(correction[0] != null && correction[0].lengthSquared() == 0,
                "a clear body sliding must report zero correction, got " + correction[0]);
        close(0.5, result.x, 1e-6, "horizontal chosen travel is preserved");
        check(result.y <= -0.4999 && result.y >= -0.500001, "vertical travel stops at the real floor, got " + result.y);
        check(above.offset(result).minY >= 1.0 - 1e-9, "the body must end at or above the floor surface");
    }
    private static void overlappingBodiesReportTheDepenetration(MeshCollision.Index index) {
        Box overlapping = new Box(1.7, 0.9, 1.7, 2.3, 2.7, 2.3); // 0.1 into the floor top
        Vec3d correction = correction(index, overlapping, Vec3d.ZERO);
        close(0.1, correction.y, 1e-4, "the minimal depenetration escapes along the floor normal");
        check(Math.abs(correction.x) < 1e-9 && Math.abs(correction.z) < 1e-9,
                "the escape axis is the minimal penetration axis, got " + correction);
        Vec3d result = MeshCollision.resolve(index, overlapping, new Vec3d(0, -1, 0), new Vec3d[1]);
        check(result.y >= correction.y - 1e-9, "sliding down after the escape must not re-penetrate, got " + result.y);
        check(overlapping.offset(result).minY >= 1.0 - 1e-6, "the resolved body stays clear of the floor");
    }
    private static void deepOverlapReportsTheFullEscape(MeshCollision.Index index) {
        Box deep = new Box(1.7, 0.5, 1.7, 2.3, 2.3, 2.3); // 0.5 into the floor top
        Vec3d correction = correction(index, deep, new Vec3d(0.4, 0, 0.4));
        close(0.5, correction.y, 1e-4, "the correction reports the full escape depth, got " + correction);
        check(deep.offset(correction).minY >= 1.0 - 1e-6, "the escape clears the real geometry");
    }
    private static void fallTravelStopsOnTheRealFloor(MeshCollision.Index index) {
        Box above = new Box(1.7, 2.0, 1.7, 2.3, 3.8, 2.3);
        Vec3d result = MeshCollision.resolve(index, above, new Vec3d(0, -3, 0), new Vec3d[1]);
        close(-1.0, result.y, 1e-3, "a long fall travels exactly to the floor surface, got " + result.y);
        check(above.offset(result).minY >= 1.0 - 1e-6, "the fall never tunnels through the mesh floor");
    }
    private static void accumulatorsAccumulatePerSideAndDrain(MeshCollision.Index index) {
        UUID player = UUID.randomUUID();
        MeshCollision.recordCorrection(player, false, new Vec3d(1, 0, 0));
        MeshCollision.recordCorrection(player, false, new Vec3d(0.25, 0, 0));
        Vec3d drained = MeshCollision.drainCorrection(player, false);
        close(1.25, drained.x, 0, "server corrections accumulate until drained");
        check(MeshCollision.drainCorrection(player, false).lengthSquared() == 0, "drain resets the accumulator");
        MeshCollision.recordCorrection(player, true, new Vec3d(0, 1, 0));
        check(MeshCollision.drainCorrection(player, false).lengthSquared() == 0, "client corrections never touch the server side");
        close(1, MeshCollision.drainCorrection(player, true).y, 0, "client corrections drain on the client side");
        MeshCollision.recordCorrection(player, false, Vec3d.ZERO);
        check(MeshCollision.drainCorrection(player, false).lengthSquared() == 0, "zero corrections are not recorded");
        // The side selection used by move() follows the world: server players use the server accumulator.
        MeshCollision.recordCorrection(player, false, new Vec3d(0, 0, 3));
        MeshCollision.clear(player);
        check(MeshCollision.drainCorrection(player, false).lengthSquared() == 0, "clear wipes the server accumulator");
        check(MeshCollision.drainCorrection(player, true).lengthSquared() == 0, "clear wipes the client accumulator");
    }
}
