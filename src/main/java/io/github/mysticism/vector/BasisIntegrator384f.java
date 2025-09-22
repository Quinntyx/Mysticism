package io.github.mysticism.vector;

/**
 *  Code that updates basis as you walk through spirit world.
 *  <p>
 *  AI-generated. Definitely maybe has some logic errors, if there's any weird bugs going on probably look here first?
 *  I'm just sick and can't be bothered to write real maths code myself right now or think about whether this is correct.
 *  */
public final class BasisIntegrator384f {
    private BasisIntegrator384f() {}

    /** One integration step. Returns true if basis changed. */
    public static boolean step(Basis384f B, Vec384f attunement, double dx, double dy, double dz, float eta) {
        if ((dx*dx + dy*dy + dz*dz) < 1e-10) return false; // idle

        // Δℓ = dx i + dy j + dz k
        Vec384f dL = lincomb(B.i, B.j, B.k, (float)dx, (float)dy, (float)dz);

        float len = dL.length();
        if (len < 1e-6f) return false;

        // u = normalize(Δℓ)
        Vec384f u = dL.clone().mul(1f / len);

        // t = a - <a,u> u  (push toward attunement direction orthogonal to current Δℓ)
        float au = attunement.dot(u);
        Vec384f t = attunement.clone().sub(u.clone().mul(au));
        float tlen = t.length();
        if (tlen < 1e-6f) return false; // already aligned

        t.mul(1f / tlen); // normalize

        // i' = i + η dx t ; etc.
        addScaled(B.i, t, (float)dx * eta);
        addScaled(B.j, t, (float)dy * eta);
        addScaled(B.k, t, (float)dz * eta);

        // re-orthonormalize cheaply
        gramSchmidt(B);

        return true;
    }

    private static Vec384f lincomb(Vec384f i, Vec384f j, Vec384f k, float dx, float dy, float dz) {
        // tmp = i*dx + j*dy + k*dz
        float[] out = new float[384];
        float[] a=i.data, b=j.data, c=k.data; // same package for fast path; else loop with getters
        for (int n=0; n<384; n++) out[n] = a[n]*dx + b[n]*dy + c[n]*dz;
        return new Vec384f(out);
    }
    private static void addScaled(Vec384f dst, Vec384f src, float s) {
        float[] a = dst.data, b = src.data;
        for (int n=0; n<384; n++) a[n] += b[n] * s;
        dst.updateNorm(); // mark clean & store normalized cache
    }
    private static void gramSchmidt(Basis384f B) {
        // i := normalize(i)
        float ilen = B.i.length(); if (ilen > 1e-6f) B.i.mul(1f/ilen).updateNorm();
        // j := j - <j,i> i
        float ji = B.j.dot(B.i); B.j.sub(B.i.clone().mul(ji));
        float jlen = B.j.length(); if (jlen > 1e-6f) B.j.mul(1f/jlen).updateNorm();
        // k := k - <k,i> i - <k,j> j
        float ki = B.k.dot(B.i), kj = B.k.dot(B.j);
        B.k.sub(B.i.clone().mul(ki)).sub(B.j.clone().mul(kj));
        float klen = B.k.length(); if (klen > 1e-6f) B.k.mul(1f/klen).updateNorm();
    }
}
