package io.github.mysticism.client.spiritworld;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Exercises production picking with real affine mesh cells; no game/model/network bootstrap. */
public final class SpiritTouchPickingSelfTest {
    private static int checks;
    private static final UUID NEAR = new UUID(0, 1), FAR = new UUID(0, 2);
    private static final Vec3d X = new Vec3d(1, 0, 0), Y = new Vec3d(0, 1, 0), Z = new Vec3d(0, 0, 1);
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static TerrainMeshFrame.Cell cell(Vec3d origin, Vec3d x, Vec3d y, Vec3d z,
            float opacity, List<Box> shapes) {
        return new TerrainMeshFrame.Cell(1, 0, "landmark", Vec3d.ZERO, origin,
                new Vec3d(1, 1, 1), x, y, z, -1, 0, opacity, shapes);
    }
    private static TerrainMeshFrame mesh(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cells));
    }
    private static SpiritTouchPicking.Peer peer(UUID id, double z) {
        return new SpiritTouchPicking.Peer(id, new Box(-.3, -.3, z, .3, .3, z + .6));
    }
    public static void main(String[] args) {
        var empty = mesh();
        var peers = List.of(peer(FAR, 3), peer(NEAR, 2));
        var hit = SpiritTouchPicking.pick(empty, peers, Vec3d.ZERO, Z, 4);
        check(hit.isPresent() && hit.get().id().equals(NEAR), "Nearest visible peer, not iteration order");
        check(hit.get().point().equals(new Vec3d(0, 0, 2)), "Exact projected bounds hit");
        check(SpiritTouchPicking.pick(empty, List.of(peer(FAR, 4.01)), Vec3d.ZERO, Z, 100).isEmpty(),
                "Reach cannot exceed normal four blocks");
        check(SpiritTouchPicking.pick(empty, peers, Vec3d.ZERO, Z, 1).isEmpty(), "Requested shorter reach respected");
        check(SpiritTouchPicking.pick(empty, peers, Vec3d.ZERO, Z.multiply(7), 4).isPresent(), "Direction normalized");
        for (double reach : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            check(SpiritTouchPicking.pick(empty, peers, Vec3d.ZERO, Z, reach).isEmpty(), "Invalid reach rejected");
        check(SpiritTouchPicking.pick(empty, peers, Vec3d.ZERO, Vec3d.ZERO, 4).isEmpty(), "Zero ray rejected");
        check(SpiritTouchPicking.pick(empty, peers, Vec3d.ZERO, new Vec3d(Double.NaN, 0, 1), 4).isEmpty(),
                "Nonfinite ray rejected");
        check(SpiritTouchPicking.pick(empty, peers, new Vec3d(0, 0, 2.1), Z, 4).orElseThrow().point()
                .equals(new Vec3d(0, 0, 2.1)), "Inside peer bounds is a zero-distance hit");

        var unit = new Box(0, 0, 0, 1, 1, 1);
        var wall = cell(new Vec3d(-.5, -.5, 1), X, Y, Z, 1, List.of(unit));
        check(SpiritTouchPicking.pick(mesh(wall), peers, Vec3d.ZERO, Z, 4).isEmpty(), "Mesh wall occludes touch");
        check(!SpiritTouchPicking.clearMeshRay(mesh(wall), new Vec3d(0, 0, 1.5), new Vec3d(0, 0, 3)),
                "Ray starts inside solid: fail closed");
        check(SpiritTouchPicking.pick(mesh(cell(wall.min(), X, Y, Z, 0, List.of(unit))), peers,
                Vec3d.ZERO, Z, 4).isPresent(), "Fully faded geometry does not occlude");
        check(SpiritTouchPicking.pick(mesh(cell(wall.min(), X, Y, Z, 1, List.of())), peers,
                Vec3d.ZERO, Z, 4).isPresent(), "Non-solid visible cells do not become cubes");
        var hole = cell(wall.min(), X, Y, Z, 1,
                List.of(new Box(0, 0, 0, .2, 1, 1), new Box(.8, 0, 0, 1, 1, 1)));
        check(SpiritTouchPicking.pick(mesh(hole), peers, Vec3d.ZERO, Z, 4).isPresent(),
                "Actual gap between local shapes stays touchable");

        var skew = cell(Vec3d.ZERO, X, Y, new Vec3d(1, 0, 1), 1, List.of(unit));
        check(SpiritTouchPicking.clearMeshRay(mesh(skew), new Vec3d(.1, .5, .8), new Vec3d(.2, .5, .9)),
                "Inside conservative world AABB but outside affine solid: no phantom occlusion");
        check(!SpiritTouchPicking.clearMeshRay(mesh(skew), new Vec3d(1, .5, .5), new Vec3d(1.1, .5, .5)),
                "Inside skewed local solid blocks touch");
        var mirrored = cell(new Vec3d(1, 0, 0), X.multiply(-1), Y, Z, 1, List.of(unit));
        check(!SpiritTouchPicking.clearMeshRay(mesh(mirrored), new Vec3d(.5, .5, -1), new Vec3d(.5, .5, 2)),
                "Negative determinant/mirrored mesh occludes correctly");
        var protrusion = cell(Vec3d.ZERO, X, Y, Z, 1, List.of(new Box(1.5, 0, 0, 2, 1, 1)));
        check(!SpiritTouchPicking.clearMeshRay(mesh(protrusion), new Vec3d(1.75, .5, -1), new Vec3d(1.75, .5, 2)),
                "Protruding local collision must not be culled by unit model bounds");
        var degenerate = cell(Vec3d.ZERO, X, Y, Vec3d.ZERO, 1, List.of(unit));
        check(!SpiritTouchPicking.clearMeshRay(mesh(degenerate), new Vec3d(.5, .5, -1), new Vec3d(.5, .5, 1)),
                "Degenerate visible surface fails closed");
        check(SpiritTouchPicking.clearMeshRay(mesh(degenerate), new Vec3d(3, .5, -1), new Vec3d(3, .5, 1)),
                "Unrelated degenerate cells do not block every ray");

        var miss = SpiritCrosshair.miss(new Vec3d(10, 20, 30), Z);
        check(miss.getType() == HitResult.Type.MISS, "Carrier miss must not be BLOCK, even though it has a block position");
        check(miss.getPos().equals(new Vec3d(10, 20, 34)), "Non-null miss endpoint follows view ray");
        System.out.println("SpiritTouchPickingSelfTest: " + checks + " checks passed (offline geometry only)");
    }
}
