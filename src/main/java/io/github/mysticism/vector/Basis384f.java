package io.github.mysticism.vector;

import java.util.Arrays;
import java.util.List;

public class Basis384f {
    public Vec384f i;
    public Vec384f j;
    public Vec384f k;

    public Basis384f(Vec384f i, Vec384f j, Vec384f k) {
        this.i = i;
        this.j = j;
        this.k = k;
    }

    public Basis384f() {
        this(Vec384f.ZERO(), Vec384f.ZERO(), Vec384f.ZERO());
    }

    private static Basis384f createUnInit() {
        return new Basis384f(null, null, null);
    }

    public int[] toBits() {
        return Arrays.stream(new int[][]{i.toBits(), j.toBits(), k.toBits()})
                .flatMapToInt(Arrays::stream)
                .toArray();
    }

    public static Basis384f fromBits(int[] bits) {
        return new Basis384f(
                Vec384f.fromBits(Arrays.copyOfRange(bits, 0, 384)),
                Vec384f.fromBits(Arrays.copyOfRange(bits, 384, 768)),
                Vec384f.fromBits(Arrays.copyOfRange(bits, 768, 1152))
        );
    }
}
