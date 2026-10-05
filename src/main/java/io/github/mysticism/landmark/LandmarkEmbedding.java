package io.github.mysticism.landmark;

import io.github.mysticism.vector.Vec384f;
import java.util.Objects;
import java.util.Arrays;

/** Immutable vector snapshot. All vectors returned to callers are fresh defensive copies. */
public final class LandmarkEmbedding {
    private final EmbeddingProfile profile;
    private final float[] data;
    public LandmarkEmbedding(EmbeddingProfile profile, Vec384f vector) {
        this.profile=Objects.requireNonNull(profile); data=Objects.requireNonNull(vector).data();
        if (data.length != profile.dimensions()) throw new IllegalArgumentException("dimension mismatch");
        double sum=0;
        for (float f:data) { if (!Float.isFinite(f)) throw new IllegalArgumentException("nonfinite embedding"); sum+=(double)f*f; }
        if (profile.normalization()==EmbeddingProfile.Normalization.UNIT && Math.abs(sum-1)>1e-4)
            throw new IllegalArgumentException("profile requires unit vectors");
    }
    public EmbeddingProfile profile() { return profile; }
    public Vec384f vector() { return new Vec384f(data); }
    public float[] data() { return data.clone(); }
    public double distanceSquared(LandmarkEmbedding b) {
        profile.requireCompatible(b.profile); double sum=0;
        for (int i=0;i<data.length;i++) { double d=(double)data[i]-b.data[i]; sum+=d*d; } return sum;
    }
    public double dotDifference(LandmarkEmbedding origin, Vec384f axis) {
        profile.requireCompatible(origin.profile); float[] a=axis.data();
        if (a.length!=data.length) throw new IllegalArgumentException("axis dimension");
        double sum=0; for (int i=0;i<data.length;i++) sum+=((double)data[i]-origin.data[i])*a[i]; return sum;
    }
    @Override public boolean equals(Object b) { return b instanceof LandmarkEmbedding e && profile.equals(e.profile) && Arrays.equals(data,e.data); }
    @Override public int hashCode() { return 31*profile.hashCode()+Arrays.hashCode(data); }
}
