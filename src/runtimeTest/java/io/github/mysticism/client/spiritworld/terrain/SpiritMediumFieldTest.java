package io.github.mysticism.client.spiritworld.terrain;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Actual production density/cache behavior; no game bootstrap, GL context, or model inference. */
public final class SpiritMediumFieldTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void equal(float a, float b, String message) { check(a == b, message + ": " + a + " != " + b); }
    private static TerrainMeshFrame.Cell cell(long id, Vec3d min, Vec3d x, Vec3d y, Vec3d z, float alpha) {
        // Deliberately no collision and unrelated source coordinates: these are visible custom models.
        return new TerrainMeshFrame.Cell(id, 0, "owner", new Vec3d(1000, 2000, 3000), min,
                new Vec3d(1, 1, 1), x, y, z, 0xffffff, 0, alpha, List.of());
    }
    private static TerrainMeshFrame.Cell cube(long id, Vec3d min, double size, float alpha) {
        return cell(id, min, new Vec3d(size, 0, 0), new Vec3d(0, size, 0), new Vec3d(0, 0, size), alpha);
    }
    private static TerrainMeshFrame frame(long revision, boolean shallow, List<TerrainMeshFrame.Cell> cells) {
        return new TerrainMeshFrame(revision, shallow, "minecraft:overworld", new Vec3d(1000, 2000, 3000),
                Vec3d.ZERO, List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), cells);
    }
    private static float[] sample(TerrainMeshFrame f, Vec3d camera) {
        return new SpiritMediumField().update(f, camera).orElseThrow().occupancy();
    }
    /** Slow independent voxel-intersection oracle, including clipped/rotated world-space bounds. */
    private static float[] oracle(TerrainMeshFrame f, Vec3d center) {
        float[] out = new float[4096];
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double px = center.x - 64 + 8 * x, py = center.y - 64 + 8 * y, pz = center.z - 64 + 8 * z;
            Box voxel = new Box(px, py, pz, px + 8, py + 8, pz + 8);
            for (var cell : f.cells()) if (cell.bounds().intersects(voxel))
                out[x + 16 * (y + 16 * z)] = Math.max(out[x + 16 * (y + 16 * z)], .5f * cell.opacity());
        }
        return out;
    }
    private static void same(float[] actual, float[] expected, String message) {
        check(actual.length == 4096, "fixed texture size");
        for (int i = 0; i < 4096; i++) equal(actual[i], expected[i], message + " voxel " + i);
    }
    private static void freshness() {
        var field = new SpiritMediumField();
        var f = frame(1, true, List.of(cube(1, new Vec3d(98, 0, 0), 1, 1)));
        var initial = field.update(f, Vec3d.ZERO).orElseThrow();
        check(Arrays.equals(initial.occupancy(), new float[4096]), "far terrain starts outside density volume");
        check(field.update(f, Vec3d.ZERO).isEmpty(), "stationary camera does not resample");
        check(field.update(f, new Vec3d(3.9, 0, 0)).isEmpty(), "small motion reuses anchored field with shader offset");
        var shifted = field.update(f, new Vec3d(4, 0, 0)).orElseThrow();
        check(shifted.center().equals(new Vec3d(4, 0, 0)), "half-voxel threshold recenters actual camera");
        var moved = field.update(f, new Vec3d(96, 0, 0)).orElseThrow();
        equal(moved.occupancy()[8 + 16 * (8 + 16 * 8)], .5f, "movement without packet brings terrain into medium");
        check(moved.center().equals(new Vec3d(96, 0, 0)), "camera, not player-eye pose, anchors samples");
        var other = new SpiritMediumField();
        same(other.update(f, Vec3d.ZERO).orElseThrow().occupancy(), initial.occupancy(), "independent camera cache");
        check(field.update(f, moved.center()).isEmpty(), "other observer does not disturb cache");
        var replacement = frame(2, false, List.of(cube(1, new Vec3d(98, 0, 0), 1, .2f)));
        equal(field.update(replacement, moved.center()).orElseThrow().occupancy()[8 + 16 * (8 + 16 * 8)],
                .1f, "new projection/opacity immediately rebuilds at stationary camera");
        same(field.update(frame(3, false, List.of()), moved.center()).orElseThrow().occupancy(),
                new float[4096], "empty replacement removes old geometry");
        field.clear();
        check(field.update(f, Vec3d.ZERO).isPresent(), "clear forces fresh publication at same pose/revision");
        float[] mutable = moved.occupancy();
        mutable[8 + 16 * (8 + 16 * 8)] = 0;
        equal(moved.occupancy()[8 + 16 * (8 + 16 * 8)], .5f, "published sample is detached");
        for (Vec3d invalid : Arrays.asList(null, new Vec3d(Double.NaN, 0, 0), new Vec3d(0, Double.POSITIVE_INFINITY, 0))) {
            boolean rejected = false;
            try { field.update(f, invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid camera rejected");
        }
    }
    private static void geometry() {
        var cells = List.of(cube(1, new Vec3d(-8, -8, -8), 8, 1),
                cube(2, Vec3d.ZERO, 8, .2f), cube(3, Vec3d.ZERO, 8, .8f),
                cube(4, new Vec3d(48, 48, 48), 8, 0),
                cube(5, new Vec3d(-68, 8, 8), 8, 1), cube(6, new Vec3d(64, 0, 0), 8, 1),
                cell(7, new Vec3d(20, 20, 20), new Vec3d(-17, 5, 2),
                        new Vec3d(3, 18, 2), new Vec3d(1, -2, 11), .6f));
        var f = frame(1, false, cells);
        float[] actual = sample(f, Vec3d.ZERO);
        same(actual, oracle(f, Vec3d.ZERO), "visible affine bounds, negative coordinates and volume clipping");
        equal(actual[7 + 16 * (7 + 16 * 7)], .5f, "noncollidable visible model contributes");
        equal(actual[8 + 16 * (8 + 16 * 8)], .4f, "overlap uses maximum fade, not summed occupancy");
        equal(actual[9 + 16 * (8 + 16 * 8)], 0, "exact max boundary does not bleed to next voxel");
        equal(actual[14 + 16 * (14 + 16 * 14)], 0, "fully faded geometry contributes nothing");
        Vec3d far = new Vec3d(29000000.25, -20000000.75, -29000000.5);
        List<TerrainMeshFrame.Cell> translated = new ArrayList<>();
        for (var c : cells) translated.add(cell(c.key(), c.min().add(far), c.axisX(), c.axisY(), c.axisZ(), c.opacity()));
        same(sample(frame(2, false, translated), far), actual, "world-space translation invariant at large coordinates");
    }
    private static void budgetsAndOrder() {
        List<TerrainMeshFrame.Cell> cells = new ArrayList<>();
        for (int i = 0; i < TerrainMeshFrame.MAX_CELLS - 1; i++)
            cells.add(cube(i, new Vec3d(-64, -64, -64), 64, .2f));
        cells.add(cube(TerrainMeshFrame.MAX_CELLS, new Vec3d(40, 40, 40), 1, 1));
        var f = frame(1, false, cells);
        float[] actual = sample(f, Vec3d.ZERO);
        equal(actual[13 + 16 * (13 + 16 * 13)], .5f, "late visible cell survives >16384 old-budget writes");
        equal(actual[0], .1f, "dense overlap still contributes");
        Collections.reverse(cells);
        same(sample(frame(2, false, cells), Vec3d.ZERO), actual, "maximum-size input order independence");
        var random = new Random(9213);
        cells.clear();
        for (int i = 0; i < 50; i++) cells.add(cell(i,
                new Vec3d(random.nextDouble() * 160 - 80, random.nextDouble() * 160 - 80, random.nextDouble() * 160 - 80),
                new Vec3d(random.nextDouble() * 32 - 16, 4, -3), new Vec3d(1, random.nextDouble() * 24 + 1, 2),
                new Vec3d(3, -2, random.nextDouble() * 24 + 1), random.nextFloat()));
        var randomFrame = frame(3, true, cells);
        var center = new Vec3d(-.375, 4.25, 1.125);
        same(sample(randomFrame, center), oracle(randomFrame, center), "skewed random mesh against independent oracle");
    }
    public static void main(String[] args) {
        freshness(); geometry(); budgetsAndOrder();
        System.out.println("SpiritMediumFieldTest passed: " + checks + " checks");
    }
}
