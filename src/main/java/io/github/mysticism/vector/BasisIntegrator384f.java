package io.github.mysticism.vector;

/**
 * Rotate the basis so that the latent step direction (from 3D movement) turns
 * toward the attunement by a FRACTION of the remaining angle per block moved.
 *
 * - Let Δ3D = (dx,dy,dz) in world units.
 * - Let u = normalize(Δℓ) where Δℓ = dx*i + dy*j + dz*k (latent step dir).
 * - Let â = normalize(attunement) (direction only).
 * - Let θ = arccos(<u, â>) be the remaining angle to the target direction.
 * - Let f = clamp(eta * |Δ3D|, 0..1) be the fraction this tick ("eta" ≈ % per block).
 * - Apply an exact 2D rotation by α = f * θ in the plane span{u, t}, where t is â's
 *   component orthogonal to u. Apply the SAME rotation to i, j, k.
 *
 * This yields smooth, monotone convergence in all axes and naturally eases near alignment.
 */
public final class BasisIntegrator384f {
    private BasisIntegrator384f() {}

    /** @return true if the basis changed. */
    public static boolean step(Basis384f B, Vec384f attunement, double dx, double dy, double dz, float eta) {
        // 1) 3D movement magnitude (blocks)
        final double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dist < 1e-8) return false; // idle

        // 2) Δℓ = dx*i + dy*j + dz*k ; u = normalize(Δℓ)
        final Vec384f dL = lincomb(B.i, B.j, B.k, (float)dx, (float)dy, (float)dz);
        final float len = dL.length();
        if (len < 1e-8f) return false;
        final Vec384f u = dL.clone().mul(1f / len); // unit

        // 3) â = normalize(attunement) ; project orthogonal part t = unit(â - <â,u>u)
        float aLen = attunement.length();
        if (aLen < 1e-8f) return false; // no target direction
        final Vec384f aHat = attunement.clone().mul(1f / aLen);

        float ua = clamp(u.dot(aHat), -1f, 1f); // cos(theta)
        // Already aligned enough?
        if (ua > 1f - 1e-8f) return false;

        Vec384f t = aHat.clone().sub(u.clone().mul(ua)); // orth component
        float tLen = t.length();
        if (tLen < 1e-8f) return false;
        t.mul(1f / tLen); // unit, orth to u

        // 4) Fraction of remaining angle this step (eta ≈ percent per block)
        // alpha = theta * clamp(eta * |Δ3D|, 0..1)
        final double theta = Math.acos(ua);
        final float fraction = clamp((float)(eta * dist), 0f, 1f);
        final float alpha = (float)(theta * fraction);
        if (alpha < 1e-9f) return false;

        // 5) Apply exact plane rotation by angle "alpha" in span{u, t} to i, j, k
        rotateInPlaneExact(B.i, u, t, alpha);
        rotateInPlaneExact(B.j, u, t, alpha);
        rotateInPlaneExact(B.k, u, t, alpha);

        // 6) Tiny renorm guard (rotation should preserve norms; fp noise only)
        renormIfNeeded(B.i);
        renormIfNeeded(B.j);
        renormIfNeeded(B.k);

        // Keep cached normalized arrays fresh (if you ever call v.norm()).
        B.i.updateNorm(); B.j.updateNorm(); B.k.updateNorm();
        return true;
    }

    /* ----------------- internals ----------------- */

    private static Vec384f lincomb(Vec384f i, Vec384f j, Vec384f k, float dx, float dy, float dz) {
        float[] out = new float[384];
        float[] a = i.data, b = j.data, c = k.data;
        for (int n = 0; n < 384; n++) out[n] = a[n]*dx + b[n]*dy + c[n]*dz;
        return new Vec384f(out);
    }

    /**
     * Exact rotation in the 2D plane spanned by orthonormal (u, t).
     * For any vector v: decompose v = a*u + b*t + w, rotate (a,b) by alpha, keep w unchanged.
     */
    private static void rotateInPlaneExact(Vec384f v, Vec384f u, Vec384f t, float alpha) {
        final float a = v.dot(u);
        final float b = v.dot(t);
        final float ca = (float)Math.cos(alpha);
        final float sa = (float)Math.sin(alpha);
        // new coefficients in the (u,t) plane
        final float a2 = a * ca - b * sa;
        final float b2 = a * sa + b * ca;
        // v := (v - a*u - b*t) + a2*u + b2*t
        addScaled(v, u, a2 - a);
        addScaled(v, t, b2 - b);
    }

    /** v := v + s * src (zero-alloc) */
    private static void addScaled(Vec384f v, Vec384f src, float s) {
        if (s == 0f) return;
        float[] a = v.data, b = src.data;
        for (int n = 0; n < 384; n++) a[n] += b[n] * s;
    }

    private static void renormIfNeeded(Vec384f v) {
        float l2 = v.length();
        if (Math.abs(l2 - 1f) > 1e-5f && l2 > 1e-12f) v.mul(1f / l2);
    }

    private static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }
}
