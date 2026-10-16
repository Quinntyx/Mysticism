package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.BlockPoint;
import java.util.*;
import java.util.function.Predicate;

/** Loaded-source discovery only selects observation seeds, never owners or chunk-shaped
 * landmarks. The existing source-coordinate Ensure pipeline proves connectivity/ownership.
 * One candidate per absolute vertical band keeps caves at different heights represented. */
public final class VerticalSeeds {
    public static final int SURFACE_MARGIN = 4;
    public static final long BAND = 32;
    public static final List<Integer> LATTICE = List.of(2, 6, 10, 14);
    // Unit vertical stride: a four-block stride silently missed thin cave air layers.
    public static final long Y_STEP = 1;

    public record Probe(BlockPoint position, boolean air, long surfaceY) {
        public Probe { Objects.requireNonNull(position); }
    }
    public record Column(long x, long z, long surfaceY) {}

    private static final Comparator<BlockPoint> POINTS = Comparator
        .comparingLong(BlockPoint::y).thenComparingLong(BlockPoint::x).thenComparingLong(BlockPoint::z);

    private VerticalSeeds() {}

    /** Sky and surface-adjacent air already have the surface hint. This is a candidate
     * filter, NOT proof of enclosure: Ensure still detects sky escape and biome boundaries. */
    public static boolean underground(Probe probe) {
        return probe.air() && probe.position().y() < probe.surfaceY() - SURFACE_MARGIN;
    }
    public static long band(long y) { return Math.floorDiv(y, BAND); }

    public static List<BlockPoint> column(long x, long z, long bottom, long surfaceY) {
        if (surfaceY <= bottom) return List.of();
        ArrayList<BlockPoint> result = new ArrayList<>();
        for (long y = bottom + 1; y < surfaceY; y += Y_STEP) result.add(new BlockPoint(x, y, z));
        return List.copyOf(result);
    }

    private static void retain(Map<Long, BlockPoint> bands, BlockPoint p) {
        bands.merge(band(p.y()), p, (a, b) -> POINTS.compare(a, b) <= 0 ? a : b);
    }

    /** Lowest candidate per band, then x/z; independent of collection iteration order. */
    public static List<BlockPoint> seeds(Collection<Probe> probes, int limit) {
        if (limit < 0) throw new IllegalArgumentException("seed limit");
        TreeMap<Long, BlockPoint> bands = new TreeMap<>();
        for (Probe probe : probes) if (underground(probe)) retain(bands, probe.position());
        return bands.values().stream().limit(limit).toList();
    }

    /** Resumable detached coordinates, not a retained WorldChunk. Every probe is observed
     * through the caller on its owner thread, with a strict per-advance read budget. Only
     * one candidate per band is retained; a tall column does not allocate a probe list.
     * Completed candidates can wait here while the source hint queue is full. */
    public static final class Scan {
        private final List<Column> columns;
        private final long bottom, top;
        private final TreeMap<Long, BlockPoint> bands = new TreeMap<>();
        private int column;
        private long y;
        private int lastSampled;

        public Scan(List<Column> columns, long bottom, long top) {
            if (bottom >= top || bottom < Integer.MIN_VALUE || top > Integer.MAX_VALUE
                    || columns.size() > LATTICE.size() * LATTICE.size())
                throw new IllegalArgumentException("vertical scan bounds");
            this.columns = List.copyOf(columns);
            this.bottom = bottom;
            this.top = top;
            y = bottom + 1;
            skipFinishedColumns();
        }
        private long ceiling() {
            return Math.min(top, columns.get(column).surfaceY() - SURFACE_MARGIN);
        }
        private void skipFinishedColumns() {
            while (column < columns.size() && y >= ceiling()) { column++; y = bottom + 1; }
        }
        public boolean complete() { return column == columns.size(); }
        public int lastSampled() { return lastSampled; }
        public int seedCount() { return bands.size(); }

        public boolean advance(int budget, Predicate<BlockPoint> air) {
            if (budget < 1) throw new IllegalArgumentException("vertical probe budget");
            Objects.requireNonNull(air);
            lastSampled = 0;
            while (!complete() && lastSampled < budget) {
                Column c = columns.get(column);
                BlockPoint p = new BlockPoint(c.x(), y, c.z());
                boolean observedAir = air.test(p);
                y += Y_STEP;
                lastSampled++;
                if (observedAir) retain(bands, p);
                skipFinishedColumns();
            }
            return complete();
        }
        public Optional<BlockPoint> pollSeed() {
            if (!complete()) throw new IllegalStateException("vertical scan incomplete");
            var first = bands.pollFirstEntry();
            return first == null ? Optional.empty() : Optional.of(first.getValue());
        }
    }
}
