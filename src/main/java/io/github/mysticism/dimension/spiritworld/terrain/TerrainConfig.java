package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;

/** Hard resource limits, independent of landmark/chunk density. No nearest-K query. */
public record TerrainConfig(double semanticRadius, double clusterRadius, FogHorizons fog,
        int catalogLimit, int representatives, int pagesPerLandmark, int leavesPerLandmark,
        int regionLimit, int probesPerTick, int regionsPerTick, int selectionPeriod) {
    public static final int REGION_SIDE = 8, CELLS_PER_REGION = 512;
    public static final TerrainConfig DEFAULT = new TerrainConfig(1.25, .18,
            new FogHorizons(48, 64, 160), 256, 8, 2, 65536, 128, 16, 1, 100);
    public TerrainConfig {
        if (!Double.isFinite(semanticRadius) || semanticRadius <= 0 || clusterRadius <= 0
                || clusterRadius > semanticRadius || catalogLimit < 1 || catalogLimit > 256
                || representatives < 1 || representatives > 8 || pagesPerLandmark < 1
                || pagesPerLandmark > 8 || leavesPerLandmark < 1 || leavesPerLandmark > 65536
                || regionLimit < 1 || regionLimit > 128 || probesPerTick < 1 || probesPerTick > 32
                || regionsPerTick != 1 || selectionPeriod < 20 || fog.hidden() + 32 >= fog.prefetch())
            throw new IllegalArgumentException("terrain budgets/horizons");
    }
    public RepresentativeSelector selector() {
        return new RepresentativeSelector(new RepresentativeSelector.Config(semanticRadius,
                clusterRadius, catalogLimit, representatives, 32, 2, .7, .5, .2, .08, 1));
    }
    public double mutationDistance() { return fog.hidden() + 32; }
}
