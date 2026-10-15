package io.github.mysticism.client.spiritworld.terrain;

import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Camera-space batch culling, separate from source-space discovery/ownership and collision.
 * Takes the current effective (client/server-clamped) view distance in chunks, not a fixed locality. */
final class TerrainVisibility {
    private TerrainVisibility() {}

    static boolean isVisible(Box bounds, Vec3d camera, int viewDistanceChunks, Frustum frustum) {
        double radius = Math.max(0, viewDistanceChunks) * 16.0;
        // Measure the nearest point, not the batch center: large/overflow VBOs can straddle the horizon.
        double x = Math.max(Math.max(bounds.minX - camera.x, 0), camera.x - bounds.maxX);
        double y = Math.max(Math.max(bounds.minY - camera.y, 0), camera.y - bounds.maxY);
        double z = Math.max(Math.max(bounds.minZ - camera.z, 0), camera.z - bounds.maxZ);
        return x*x + y*y + z*z <= radius*radius && (frustum == null || frustum.isVisible(bounds));
    }
}
