package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Physical premise of approach completion: a descending actor must be able to DISCOVER the owned
 * support below before actual contact, because guarded source-grid alignment can only rotate the
 * rendered world while near-surface cells stay clear of the body. The deeper sweep is the SAME
 * contact query MeshCollision.ground performs, parameterized by reach. */
public final class ApproachSupportSweepTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final TerrainMeshFrame.Material STONE = new TerrainMeshFrame.Material("minecraft:stone", Map.of());
    private static final double CONTACT_REACH = .15;
    /** Landing floor collision spans y in [127,128]; a settled body's feet rest at y=128. */
    private static final double FLOOR_CELL_MIN_Y = 127, FLOOR_TOP = 128;

    public static void main(String[] args) {
        contactReachMissesUpcomingSupport();
        approachReachDiscoversSupportBeforeContact();
        approachSweepFindsTheFirstLandingSurface();
        fadedTerrainIsNeverSupport();
        approachDiscoveryKeepsTheAcquisitionNormalContract();
        System.out.println("approach support sweep checks: " + checks);
    }

    private static TerrainMeshFrame.Cell cell(long key, Vec3d min, float opacity) {
        return new TerrainMeshFrame.Cell(key, 0, "landmark-a", new Vec3d(0, 0, 0), min, new Vec3d(1, 1, 1),
                new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1), 0xFF88CC00, 0, opacity,
                List.of(new Box(0, 0, 0, 1, 1, 1)));
    }

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO, List.of(STONE), List.of(cells));
    }

    /** A descending deep actor whose feet are the given distance above the world floor. */
    private static Box descendingBody(double feetY) {
        return new Box(.2, feetY, .2, .8, feetY + 1.8, .8);
    }

    private static void contactReachMissesUpcomingSupport() {
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, FLOOR_CELL_MIN_Y, 0), 1f)));
        check(MeshCollision.ground(descendingBody(140), index, CONTACT_REACH).isEmpty(),
                "the unchanged contact query finds nothing while the actor is still descending");
        check(MeshCollision.ground(descendingBody(FLOOR_TOP + .3), index, CONTACT_REACH).isEmpty(),
                "just outside contact reach the unchanged query has not committed yet");
        check(MeshCollision.ground(descendingBody(FLOOR_TOP + .1), index, CONTACT_REACH).isPresent(),
                "the unchanged contact query still commits inside its settle band");
    }

    private static void approachReachDiscoversSupportBeforeContact() {
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, FLOOR_CELL_MIN_Y, 0), 1f)));
        var hit = MeshCollision.ground(descendingBody(140), index, 24).orElse(null);
        check(hit != null, "approach discovery sees the owned landing surface before the body arrives");
        check(Math.abs(hit.normal().y - 1) < 1e-9, "a flat landing surface has an up normal");
        check(hit.time() > 0 && hit.time() <= 1, "the contact is inside the sweep");
        var settled = MeshCollision.ground(descendingBody(FLOOR_TOP + .02), index, 24).orElse(null);
        check(settled != null, "the same query keeps discovering the support at settle height");
        var outside = MeshCollision.ground(descendingBody(FLOOR_TOP + .3), index, 24).orElse(null);
        check(outside != null, "approach discovery also reaches where the contact query does not");
    }

    private static void approachSweepFindsTheFirstLandingSurface() {
        var index = new MeshCollision.Index(frame(
                cell(1, new Vec3d(0, FLOOR_CELL_MIN_Y, 0), 1f), cell(2, new Vec3d(0, FLOOR_CELL_MIN_Y - 8, 0), 1f)));
        var hit = MeshCollision.ground(descendingBody(140), index, 24).orElse(null);
        check(hit != null, "the deeper sweep still stops at the first surface");
        check(Math.abs(hit.cell().min().y - FLOOR_CELL_MIN_Y) < 1e-9,
                "the first (highest) surface within reach is the predicted landing, not deeper geometry");
    }

    private static void fadedTerrainIsNeverSupport() {
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, FLOOR_CELL_MIN_Y, 0), 0f)));
        check(MeshCollision.ground(descendingBody(140), index, 24).isEmpty(),
                "faded-out terrain has no collision and cannot be discovered as support");
    }

    private static void approachDiscoveryKeepsTheAcquisitionNormalContract() {
        // A 45-degree ramp: approach discovery may align toward it (generic ground normal), while
        // acquisition at contact still independently requires normal.y >= .99.
        double a = Math.sqrt(.5);
        var index = new MeshCollision.Index(frame(new TerrainMeshFrame.Cell(1, 0, "landmark-a", new Vec3d(0, 0, 0),
                new Vec3d(0, FLOOR_CELL_MIN_Y, 0), new Vec3d(1, 1, 1), new Vec3d(a, a, 0), new Vec3d(-a, a, 0),
                new Vec3d(0, 0, 1), 0xFF88CC00, 0, 1f, List.of(new Box(0, 0, 0, 1, 1, 1)))));
        var hit = MeshCollision.ground(descendingBody(140), index, 24).orElse(null);
        check(hit != null, "sloped owned terrain is still discovered during descent");
        check(hit.normal().y > .3 && hit.normal().y < .99,
                "slope discovery keeps the generic ground normal, distinct from the >=.99 acquisition gate, got " + hit.normal().y);
    }
}
