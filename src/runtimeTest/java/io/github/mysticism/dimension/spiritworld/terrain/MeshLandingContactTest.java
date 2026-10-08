package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real swept-SAT regressions for the jump/landing contact window that shallow navigation relies on.
 * Exercises MeshCollision.Index against flat and sheared (distorted) projected terrain cells: the
 * ground-support window, phantom-support absence during a jump ascent, and settle stability — a landing
 * must come to rest exactly on the supporting surface and stay there without alternating
 * penetration/correction. No player entity or server is required; Index is the shared collision core and
 * the real depenetrate+slide integration primitives are replayed, not an approximation. */
public final class MeshLandingContactTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final double FLOOR_TOP = 65; // top face of the unit floor cell whose min sits at y=64

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, true, "minecraft:overworld", new Vec3d(0, 64, 0), new Vec3d(0, 64, 0),
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cells));
    }

    private static TerrainMeshFrame.Cell cell(Vec3d axisZ) {
        return new TerrainMeshFrame.Cell(1L, 0, "test-landmark", new Vec3d(0, 64, 0), new Vec3d(0, 64, 0),
                new Vec3d(1, 1, 1), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), axisZ, 0xFFFFFFFF, 15, 1f,
                List.of(new Box(0, 0, 0, 1, 1, 1)));
    }

    private static Box body(double feetY, double x, double z) {
        return new Box(x - .3, feetY, z - .3, x + .3, feetY + 1.8, z + .3);
    }

    /** MeshCollision.ground contract: body lifted .025, swept .15 down, upward normal required. */
    private static boolean ground(MeshCollision.Index index, double feetY, double x, double z) {
        Optional<MeshCollision.Hit> hit = index.sweep(body(feetY, x, z).offset(0, .025, 0), new Vec3d(0, -.15, 0));
        return hit.isPresent() && hit.get().normal().y > .3;
    }

    /** Vanilla-style free fall onto the mesh using the real production integration order:
     * requested = v - .08 each tick; depenetrate, then the swept clamp; v = (v - .08) * .98 unclamped. */
    private static void settle(MeshCollision.Index index, Box body, Vec3d surfacePoint) {
        double velocity = -.4;
        for (int tick = 0; tick < 80; tick++) {
            Vec3d requested = new Vec3d(0, velocity - .08, 0);
            Vec3d applied = index.slide(body.offset(index.depenetrate(body)), requested);
            body = body.offset(applied);
            velocity = applied.y == requested.y ? (velocity - .08) * .98 : 0;
        }
        check(Math.abs(body.minY - surfacePoint.y) < 1e-3, "landing settles exactly on the supporting surface (rest y="
                + body.minY + " expected " + surfacePoint.y + ")");
        double velocity2 = -.2;
        for (int tick = 0; tick < 20; tick++) { // standing: repeated gravity requests must not sink or alternate
            Vec3d requested = new Vec3d(0, velocity2 - .08, 0);
            Vec3d applied = index.slide(body.offset(index.depenetrate(body)), requested);
            body = body.offset(applied);
            velocity2 = applied.y == requested.y ? (velocity2 - .08) * .98 : 0;
            check(Math.abs(body.minY - surfacePoint.y) < 1e-3, "resting body stays on its support without correction oscillation (tick "
                    + tick + " y=" + body.minY + ")");
        }
    }

    private static void flatFloorLanding() {
        MeshCollision.Index index = new MeshCollision.Index(frame(cell(new Vec3d(0, 0, 1))));
        check(ground(index, FLOOR_TOP, .5, .5), "resting body has measured ground support");
        check(ground(index, FLOOR_TOP + .1, .5, .5), "touchdown window just above the floor keeps support");
        check(!ground(index, FLOOR_TOP + .35, .5, .5), "jump ascent leaves the measured support window");
        check(!ground(index, FLOOR_TOP + 1.25, .5, .5), "no phantom support at jump apex");
        check(ground(index, FLOOR_TOP + .1, .5, .5), "descent re-enters the support window before touchdown");
        check(!ground(index, FLOOR_TOP + .2, .5, .5), "the support window is bounded above the floor");
        MeshCollision.Hit landing = index.sweep(body(FLOOR_TOP + 1.25, .5, .5), new Vec3d(0, -1.3, 0)).orElseThrow();
        check(Math.abs(landing.time() * 1.3 - 1.25) < 1e-6 && landing.normal().y > .9,
                "full fall clamps exactly on the floor with an upward normal");
        settle(index, body(FLOOR_TOP + 1.25, .5, .5), new Vec3d(.5, FLOOR_TOP, .5));
    }

    private static void shearedFloorLanding() {
        // Distorted projected terrain: the unit cell is sheared so the walkable surface rises with z.
        // axisZ = (0, .5, 1) tilts the top face; surface height at world z is 65 + .5 * z.
        MeshCollision.Index index = new MeshCollision.Index(frame(cell(new Vec3d(0, .5, 1))));
        double slope = 1 / Math.sqrt(1 + .5 * .5); // upward normal component of the tilted top face
        check(slope > .3, "sheared surface keeps a walkable upward normal");
        double highEdge = 65 + .5; // top face height at the cell's far z edge
        check(!ground(index, highEdge + 1.25, .5, .8), "no phantom support above the sheared surface");
        check(ground(index, highEdge, .5, .8), "sheared surface supports a body resting at its local height");
        check(ground(index, 65 + .5 * .05, .5, .05), "the tilted surface supports a body at the low edge height");
        // A fall fully inside the cell footprint clamps onto the tilted plane with the tilted normal.
        MeshCollision.Hit landing = index.sweep(body(66.6, .5, .4), new Vec3d(0, -2, 0)).orElseThrow();
        check(Math.abs(landing.time() * 2 - 1.25) < 1e-6 && Math.abs(landing.normal().y - slope) < 1e-6,
                "fall onto the sheared surface clamps on the tilted plane with the tilted normal (" + landing + ")");
    }

    public static void main(String[] args) {
        flatFloorLanding();
        shearedFloorLanding();
        System.out.println("MeshLandingContactTest: " + checks + " checks passed");
    }
}
