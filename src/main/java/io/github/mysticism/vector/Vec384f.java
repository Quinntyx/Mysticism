package io.github.mysticism.vector;

import java.util.Arrays;
import java.util.Objects;

/** Legacy name; current dimensions are EmbeddingSpace.DIMENSIONS, never MiniLM.
 * Mutable for existing movement callers; every external owner must take a clone.
 * Arrays are private, copied on input/output, and operations validate provenance.
 */
public final class Vec384f implements Cloneable {
    private final float[] data;
    private final String fingerprint;
    private float[] normalized;

    public static Vec384f ZERO() { return new Vec384f(new float[EmbeddingSpace.DIMENSIONS]); }
    public Vec384f(float[] values) { this(values, EmbeddingSpace.FINGERPRINT); }
    public Vec384f(float[] values, String fingerprint) {
        if (values.length != EmbeddingSpace.DIMENSIONS) throw new IllegalArgumentException("dim mismatch: " + values.length);
        this.data = values.clone();
        this.fingerprint = Objects.requireNonNull(fingerprint);
        validate(this.data);
    }
    private static void validate(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite vector component");
    }
    public String fingerprint() { return fingerprint; }
    private void compatible(Vec384f other) {
        if (!fingerprint.equals(other.fingerprint)) throw new IllegalArgumentException("Mixed embedding models/profiles");
    }
    public synchronized float[] data() { return data.clone(); }
    public synchronized void updateNorm() {
        double sum = 0;
        for (float v : data) sum += (double)v*v;
        double length = Math.sqrt(sum);
        normalized = new float[EmbeddingSpace.DIMENSIONS];
        if (length > 0) for (int i=0;i<data.length;i++) normalized[i]=(float)(data[i]/length);
    }
    public synchronized float[] norm() { if (normalized == null) updateNorm(); return normalized.clone(); }
    public float l2sq() { double sum=0; for(float v:data()) sum+=(double)v*v; return (float)sum; }
    public float length() { double sum=0; for(float v:data()) sum+=(double)v*v; return (float)Math.sqrt(sum); }
    private synchronized Vec384f combine(float[] other, float factor) {
        float[] result = data.clone();
        for(int i=0;i<result.length;i++) result[i] += other[i]*factor;
        validate(result); System.arraycopy(result,0,data,0,data.length); normalized=null; return this;
    }
    public Vec384f add(Vec384f other) { compatible(other); return combine(other.data(),1); }
    public Vec384f sub(Vec384f other) { compatible(other); return combine(other.data(),-1); }
    public synchronized Vec384f mul(float factor) {
        if (!Float.isFinite(factor)) throw new IllegalArgumentException("Nonfinite scale");
        float[] result=data.clone(); for(int i=0;i<result.length;i++) result[i]*=factor;
        validate(result); System.arraycopy(result,0,data,0,data.length); normalized=null; return this;
    }
    public Vec384f converge(Vec384f target, float factor) {
        compatible(target);
        if (!Float.isFinite(factor)) throw new IllegalArgumentException("Nonfinite convergence");
        float f=Math.max(0,Math.min(1,factor)); float[] other=target.data();
        synchronized(this) {
            float[] result=data.clone();
            for(int i=0;i<result.length;i++) result[i]=(float)((1.0-f)*data[i]+f*other[i]);
            validate(result); System.arraycopy(result,0,data,0,data.length); normalized=null;
        }
        return this;
    }
    @Override public Vec384f clone() { return new Vec384f(data(),fingerprint); }
    public float dot(Vec384f other) { compatible(other); float[] a=data(),b=other.data(); double sum=0; for(int i=0;i<a.length;i++) sum+=(double)a[i]*b[i]; return (float)sum; }
    public float squareDistance(Vec384f other) { compatible(other); float[] a=data(),b=other.data(); double sum=0; for(int i=0;i<a.length;i++){ double d=(double)a[i]-b[i];sum+=d*d; } return (float)sum; }
    public float cosine(Vec384f other) { compatible(other); float[] a=norm(),b=other.norm(); double sum=0; for(int i=0;i<a.length;i++) sum+=(double)a[i]*b[i]; return (float)Math.max(-1,Math.min(1,sum)); }
    public int[] toBits() { float[] values=data(); int[] bits=new int[values.length]; for(int i=0;i<bits.length;i++) bits[i]=Float.floatToIntBits(values[i]); return bits; }
    public static Vec384f fromBits(int[] bits) {
        if(bits.length!=EmbeddingSpace.DIMENSIONS) throw new IllegalArgumentException("Invalid vector bit length: "+bits.length);
        float[] values=new float[bits.length]; for(int i=0;i<bits.length;i++) values[i]=Float.intBitsToFloat(bits[i]); return new Vec384f(values);
    }
}
