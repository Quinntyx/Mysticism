package io.github.mysticism.vector;

public class Vec384f implements Cloneable {
    public static Vec384f ZERO() {
        return new Vec384f(new float[384], new float[384], false);
    }

    final float[] data;
    final float[] norm;
    private boolean dirty = false;


    public Vec384f(float[] vec) {
        if (vec.length != 384)
            throw new IllegalArgumentException("dim mismatch: " + vec.length + " != 384");
        this.data = vec.clone();
        this.norm = new float[384];
        this.dirty = true;
    }

    public synchronized void updateNorm() {
        float len = this.length();
        if (len > 0) for (int i = 0; i < 384; i ++)
            this.norm[i] = this.data[i] / len;
        dirty = false;
    }

    private synchronized float[] _norm() {
        if (this.dirty) this.updateNorm();
        return this.norm;
    }

    public float[] norm() {
        return this._norm().clone();
    }

    public float[] data() {
        return this.data.clone();
    }

    Vec384f(float[] vec, float[] norm, boolean dirty) {
        this.data = vec;
        this.norm = norm;
        this.dirty = dirty;
    }

    public float l2sq() {
        float l = 0;
        for (float v : this.data) l += v * v;
        return l;
    }

    public float length() {
        return (float) Math.sqrt(this.l2sq());
    }

    public synchronized Vec384f add(Vec384f other) {
        for (int i = 0; i < 384; i++) {
            this.data[i] += other.data[i];
        }
        this.dirty = true;
        return this;
    }

    public synchronized Vec384f sub(Vec384f other) {
        for (int i = 0; i < 384; i++) {
            this.data[i] -= other.data[i];
        }
        this.dirty = true;
        return this;
    }

    public synchronized Vec384f mul(float factor) {
        for (int i = 0; i < 384; i++) {
            this.data[i] *= factor;
        }

        return this;
    }

    public synchronized Vec384f converge(Vec384f target, float factor) {
        for (int i = 0; i < 384; i++) {
            float difference = target.data[i] - this.data[i];

            this.data[i] += difference * factor;
        }
        this.dirty = true;
        return this;
    }

    public Vec384f clone() {
        synchronized (this) {
            return new Vec384f(this.data.clone(), this.norm.clone(), this.dirty);
        }
    }

    public float dot(Vec384f other) {
        float out = 0;
        for (int i = 0; i < 384; i++) {
            out += this.data[i] * other.data[i];
        }

        return out;
    }

    public float squareDistance(Vec384f other) {
        return this.clone().sub(other).l2sq();
    }

    public float cosine(Vec384f other) {
        float out = 0;
        for (int i = 0; i < 384; i++) {
            out += this._norm()[i] * other._norm()[i];
        }

        return out;
    }

    public int[] toBits() {
        int[] bits = new int[384];
        for (int i = 0; i < 384; i++) bits[i] = Float.floatToIntBits(this.data[i]);
        return bits;
    }

    public static Vec384f fromBits(int[] bits) {
        float[] f = new float[384];
        for (int i = 0; i < 384; i++) f[i] = Float.intBitsToFloat(bits[i]);
        return new Vec384f(f, new float[384], true);
    }
}
