package io.github.mysticism.vector;

import net.minecraft.util.math.Vec3d;

/**
 * 384D → 3D projection:
 * world = anchor + scale * (<obj - you, i>, <obj - you, j>, <obj - you, k>)
 * No normalization, no clamping.
 *
 * Pass an *interpolated* anchor (e.g., player.getCameraPosVec(tickDelta)).
 */
public final class Projection384f {
    private Projection384f() {}

    public static Vec3d projectToWorld(
            Vec384f obj, Vec384f you, Basis384f basis,
            Vec3d anchorWorld, float scale
    ) {
        float x = 0f, y = 0f, z = 0f;

        final float[] od = obj.data;
        final float[] yd = you.data;
        final float[] I  = basis.i.data;
        final float[] J  = basis.j.data;
        final float[] K  = basis.k.data;

        for (int n = 0; n < 384; n++) {
            float d = od[n] - yd[n];
            x += d * I[n];
            y += d * J[n];
            z += d * K[n];
        }
        return anchorWorld.add(x * scale, y * scale, z * scale);
    }
}
