package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import java.util.*;

/** Frozen source placements; no camera, tick, chunk index or mutable attunement in sampling. */
public final class TerrainField {
    public record Layer(LandmarkMetadata metadata, Placement placement, SourceGeometry geometry,
                        LandmarkEmbedding embedding, double importance) {
        public Layer {
            if (!metadata.id().equals(placement.landmarkId()) || geometry.pages().size() > 8
                    || !Double.isFinite(importance) || importance < 0 || importance > 1)
                throw new IllegalArgumentException("layer");
            metadata.header().baseEmbedding().profile().requireCompatible(embedding.profile());
        }
    }
    public record Sample(String landmarkId, BlockPalette.State material) {}
    private final long seed;
    private final List<Layer> layers;
    public TerrainField(long seed, Collection<Layer> layers) {
        if (layers.size() > 8) throw new IllegalArgumentException("layer budget");
        this.seed = seed;
        this.layers = layers.stream().sorted(Comparator.comparing(l -> l.metadata().id())).toList();
    }
    public List<Layer> layers() { return layers; }
    public Optional<Layer> layer(String id) { return layers.stream().filter(l -> l.metadata.id().equals(id)).findFirst(); }
    private SourceGeometry.MaterialCell cell(Layer l, long x, long y, long z) {
        Point3 a = l.placement.realmAnchor(); BlockPoint s = l.placement.sourceAnchor();
        double scale = l.placement.blocksPerSourceBlock();
        long sx = (long)Math.floor((x + .5 - a.x()) / scale + s.x());
        long sy = (long)Math.floor((y + .5 - a.y()) / scale + s.y());
        long sz = (long)Math.floor((z + .5 - a.z()) / scale + s.z());
        return l.geometry.materialAt(sx, sy, sz).orElse(null);
    }
    private boolean solid(Layer l, long x, long y, long z) {
        Point3 a=l.placement.realmAnchor(); BlockPoint s=l.placement.sourceAnchor(); double scale=l.placement.blocksPerSourceBlock();
        var sample=l.geometry.sample((long)Math.floor((x+.5-a.x())/scale+s.x()),
                (long)Math.floor((y+.5-a.y())/scale+s.y()),(long)Math.floor((z+.5-a.z())/scale+s.z()));
        return sample!=null && sample.occupancy()==BlockSample.Occupancy.SOLID;
    }
    /** Conservative shared occupancy union: AIR/UNKNOWN never punches holes in another floor.
     * The same seven-probe halo smooths boundary material weights on both sides of a region.
     * We deliberately do not invent solid cells in observed air (safe entry corridors survive). */
    public Sample sample(long x, long y, long z) {
        Sample[] values = new Sample[layers.size()]; double[] weights = new double[layers.size()];
        int n = 0; double total = 0;
        for (Layer l : layers) {
            var c = cell(l,x,y,z);
            if (c == null || c.sample().occupancy() != BlockSample.Occupancy.SOLID) continue;
            int neighbors = 1;
            if (solid(l,x-1,y,z)) neighbors++; if (solid(l,x+1,y,z)) neighbors++;
            if (solid(l,x,y-1,z)) neighbors++; if (solid(l,x,y+1,z)) neighbors++;
            if (solid(l,x,y,z-1)) neighbors++; if (solid(l,x,y,z+1)) neighbors++;
            double weight = (.25 + .75*l.importance) * (.25 + .75*BorderDither.smoothstep(neighbors / 7.0));
            values[n] = new Sample(l.metadata.id(), c.material()); weights[n++] = weight; total += weight;
        }
        if (n == 0) return null;
        double pick = BorderDither.sample(seed, 0x5445525241494eL, x, y, z) * total;
        for (int i=0;i<n;i++) { pick -= weights[i]; if (pick < 0) return values[i]; }
        return values[n-1];
    }
    public boolean knownAir(String id, long x, long y, long z) {
        var l = layer(id).orElse(null); if (l == null) return false;
        var c = cell(l,x,y,z); return c != null && c.sample().occupancy() == BlockSample.Occupancy.AIR;
    }
}
