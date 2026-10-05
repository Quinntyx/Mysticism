package io.github.mysticism.landmark;

/** Explicit known occupancy. UNKNOWN is represented by absent octree cells/frontiers, never AIR.
 * SOLID means known non-air source material (including fluids); registry collision rules remain authoritative.
 */
public record BlockSample(Occupancy occupancy, int paletteIndex) {
    public enum Occupancy { SOLID, AIR }
    public BlockSample { java.util.Objects.requireNonNull(occupancy); if (paletteIndex<0) throw new IllegalArgumentException("palette index"); }
}
