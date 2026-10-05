package io.github.mysticism.vector;

import java.util.Arrays;
import java.util.List;

public class Basis384f implements Cloneable {
    public Vec384f i;
    public Vec384f j;
    public Vec384f k;

    public Basis384f(Vec384f i, Vec384f j, Vec384f k) {
        EmbeddingSpace.requireCurrent(i); EmbeddingSpace.requireCurrent(j); EmbeddingSpace.requireCurrent(k);
        this.i = i.clone();
        this.j = j.clone();
        this.k = k.clone();
    }

    public Basis384f() {
        this(unitAxis(0), unitAxis(1), unitAxis(2));
    }

    private static Vec384f unitAxis(int index) {
        float[] values = new float[EmbeddingSpace.DIMENSIONS];
        values[index] = 1;
        return new Vec384f(values);
    }

    public int[] toBits() {
        EmbeddingSpace.requireCurrent(i); EmbeddingSpace.requireCurrent(j); EmbeddingSpace.requireCurrent(k);
        return Arrays.stream(new int[][]{i.toBits(), j.toBits(), k.toBits()})
                .flatMapToInt(Arrays::stream)
                .toArray();
    }

    public static Basis384f fromBits(int[] bits) {
        int d = EmbeddingSpace.DIMENSIONS;
        if (bits.length != 3*d) throw new IllegalArgumentException("Invalid basis bit length");
        return new Basis384f(
                Vec384f.fromBits(Arrays.copyOfRange(bits, 0, d)),
                Vec384f.fromBits(Arrays.copyOfRange(bits, d, 2*d)),
                Vec384f.fromBits(Arrays.copyOfRange(bits, 2*d, 3*d))
        );
    }

    public Basis384f clone() {
        return new Basis384f(
            i.clone(),
            j.clone(),
            k.clone()
        );
    }
}
