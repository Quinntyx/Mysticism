package io.github.mysticism.client.spiritworld.terrain;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** CPU-side model transform, kept testable without a GL context. */
public final class TerrainModelTransform {
    private TerrainModelTransform() {}

    public static Matrix4f position(TerrainMeshFrame.Cell cell, Vec3d anchor, Box primitive) {
        Vec3d min = cell.min().subtract(anchor), x = cell.axisX(), y = cell.axisY(), z = cell.axisZ();
        if (primitive != null) {
            min = cell.point(primitive.minX, primitive.minY, primitive.minZ).subtract(anchor);
            x = x.multiply(primitive.getLengthX());
            y = y.multiply(primitive.getLengthY());
            z = z.multiply(primitive.getLengthZ());
        }
        return new Matrix4f().m00((float)x.x).m01((float)x.y).m02((float)x.z)
                .m10((float)y.x).m11((float)y.y).m12((float)y.z)
                .m20((float)z.x).m21((float)z.y).m22((float)z.z)
                .m30((float)min.x).m31((float)min.y).m32((float)min.z);
    }
}
