package io.github.mysticism.landmark;

/** Absolute source block coordinates; never chunk-local. */
public record BlockPoint(long x, long y, long z) implements Comparable<BlockPoint> {
    @Override public int compareTo(BlockPoint b) {
        int c = Long.compare(x, b.x); if (c == 0) c = Long.compare(y, b.y);
        return c == 0 ? Long.compare(z, b.z) : c;
    }
}
