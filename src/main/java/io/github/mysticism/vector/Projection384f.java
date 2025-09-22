package io.github.mysticism.vector;

import net.minecraft.util.math.Vec3d;

/**
 * 384D → 3D projection utilities used by the client renderer.
 * <p>
 * Algorithm:
 * 1) Δ = obj - player (384D)
 * 2) (x,y,z) = (<Δ,i>, <Δ,j>, <Δ,k>)  where {i,j,k} is the player's Basis384f
 * 3) worldPos = playerWorldPos + (x,y,z)
 * <p>
 * This is AI generated and may be prone to bugs, but I can't be bothered to make sure the maths are right at the moment.
 */
public final class Projection384f {
    private Projection384f() {}

    /**
     * Projects the 384D object position onto the viewer's 3D basis and anchors it in world space.
     */
    public static Vec3d projectToWorld(Vec384f objectPos384, Vec384f playerPos384,
                                       Basis384f basis, Vec3d playerWorldPos, float scale) {
        // Δ = object - player  (384D)
        float x = 0f, y = 0f, z = 0f;

        // Access raw arrays for speed (these fields are package-private in your Vec384f).
        final float[] o = objectPos384.data;
        final float[] p = playerPos384.data;

        final float[] I = basis.i.data;
        final float[] J = basis.j.data;
        final float[] K = basis.k.data;

        for (int n = 0; n < 384; n++) {
            float d = o[n] - p[n];
            x += d * I[n] * scale;
            y += d * J[n] * scale;
            z += d * K[n] * scale;
        }
        return playerWorldPos.add(x, y, z);
    }
}
