package io.github.mysticism.client.spiritworld.terrain;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Camera-local, coarse density hints from the CURRENT observer mesh, never source-world scans.
 * This is not collision or surface depth: the rasterized MAIN depth remains the exact fog endpoint.
 * Bounds conservatively include non-collidable baked models (leaves, plants, etc.).
 */
public final class SpiritMediumField {
    public static final int GRID = 16, VOXEL_SIZE = 8, SAMPLES = GRID * GRID * GRID;
    public static final double HALF_EXTENT = GRID * VOXEL_SIZE / 2.0;
    private static final double RECENTER_DISTANCE_SQUARED = VOXEL_SIZE * VOXEL_SIZE / 4.0;
    private TerrainMeshFrame sampledFrame;
    private Vec3d sampledCenter;

    public record Samples(float[] occupancy, Vec3d center) {
        public Samples { occupancy = occupancy.clone(); }
        @Override public float[] occupancy() { return occupancy.clone(); }
    }

    /**
     * Reuse world-anchored samples for sub-half-voxel camera motion, with the shader's camera offset.
     * Recenter even without another network packet; rebuild immediately on any accepted mesh change.
     */
    public Optional<Samples> update(TerrainMeshFrame frame, Vec3d camera) {
        Objects.requireNonNull(frame, "mesh frame");
        if (camera == null || !Double.isFinite(camera.x) || !Double.isFinite(camera.y)
                || !Double.isFinite(camera.z)) throw new IllegalArgumentException("Invalid medium camera");
        if (sampledFrame == frame && sampledCenter.squaredDistanceTo(camera) < RECENTER_DISTANCE_SQUARED)
            return Optional.empty();
        float[] occupancy = rasterize(frame, camera);
        sampledFrame = frame;
        sampledCenter = camera;
        return Optional.of(new Samples(occupancy, camera));
    }

    public void clear() { sampledFrame = null; sampledCenter = null; }

    private record Region(Box bounds, float density) {}

    private static float[] rasterize(TerrainMeshFrame frame, Vec3d center) {
        float[] result = new float[SAMPLES];
        Box volume = new Box(center.x - HALF_EXTENT, center.y - HALF_EXTENT, center.z - HALF_EXTENT,
                center.x + HALF_EXTENT, center.y + HALF_EXTENT, center.z + HALF_EXTENT);
        var regions = new ArrayList<Region>();
        for (var cell : frame.cells()) {
            if (cell.opacity() <= 0) continue;
            Box bounds = cell.bounds();
            if (bounds.intersects(volume)) regions.add(new Region(bounds, .5f * cell.opacity()));
        }
        // Highest density claims each voxel first. A 16-bit X mask per Y/Z row prevents
        // duplicate writes: <= MAX_CELLS * 16 * 16 row visits and exactly <=4096 writes.
        // No input-order work cutoff can silently drop later terrain or stronger fades.
        regions.sort(Comparator.comparingDouble(Region::density).reversed());
        int[] filled = new int[GRID * GRID];
        for (Region region : regions) {
            Box b = region.bounds;
            int x0 = lower(b.minX, center.x), x1 = upper(b.maxX, center.x);
            int y0 = lower(b.minY, center.y), y1 = upper(b.maxY, center.y);
            int z0 = lower(b.minZ, center.z), z1 = upper(b.maxZ, center.z);
            int mask = ((1 << (x1 + 1)) - 1) & ~((1 << x0) - 1);
            for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
                int row = y + GRID * z;
                int unfilled = mask & ~filled[row];
                while (unfilled != 0) {
                    int x = Integer.numberOfTrailingZeros(unfilled);
                    result[x + GRID * row] = region.density;
                    unfilled &= unfilled - 1;
                }
                filled[row] |= mask;
            }
        }
        return result;
    }

    private static int lower(double min, double center) {
        return Math.max(0, Math.min(GRID - 1, (int) Math.floor((min - center + HALF_EXTENT) / VOXEL_SIZE)));
    }
    private static int upper(double max, double center) {
        // Half-open voxel bounds: a surface ending on a boundary does not occupy the next voxel.
        return Math.max(0, Math.min(GRID - 1, (int) Math.ceil((max - center + HALF_EXTENT) / VOXEL_SIZE) - 1));
    }
}
