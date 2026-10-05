package io.github.mysticism.landmark;

import java.util.List;
import java.util.Objects;

/** Immutable bounded source-coordinate page, with palette-resolvable occupancy. */
public final class GeometryPage {
    public static final int MAX_SIDE=64, MAX_LEAVES=32768;
    private final String id;
    private final long revision;
    private final Bounds bounds;
    private final BlockPalette palette;
    private final SparseOctree<BlockSample> cells;
    public GeometryPage(String id,long revision,Bounds bounds,BlockPalette palette,SparseOctree<BlockSample> cells) {
        this(id,revision,bounds,palette,cells,false);
    }
    private GeometryPage(String id,long revision,Bounds bounds,BlockPalette palette,SparseOctree<BlockSample> cells,boolean validated) {
        validateHeader(id,revision,bounds); this.id=id; this.revision=revision; this.bounds=bounds;
        this.palette=Objects.requireNonNull(palette); this.cells=Objects.requireNonNull(cells);
        if(!validated) for(var cell:cells.cells(MAX_LEAVES)) validateLeaf(bounds,palette,cell.bounds(),cell.value());
    }
    static void validateHeader(String id,long revision,Bounds bounds) {
        Objects.requireNonNull(id); Objects.requireNonNull(bounds);
        if(id.isBlank() || revision<0 || bounds.maxX()-bounds.minX()>MAX_SIDE || bounds.maxY()-bounds.minY()>MAX_SIDE
                || bounds.maxZ()-bounds.minZ()>MAX_SIDE) throw new IllegalArgumentException("geometry page limits");
    }
    static void validateLeaf(Bounds bounds,BlockPalette palette,Bounds region,BlockSample sample) {
        if(!bounds.contains(region) || sample.paletteIndex()>=palette.states().size()) throw new IllegalArgumentException("invalid geometry leaf");
        String block=palette.state(sample.paletteIndex()).blockId();
        boolean air=block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air");
        if(air!=(sample.occupancy()==BlockSample.Occupancy.AIR)) throw new IllegalArgumentException("occupancy/palette mismatch");
    }
    // Only the incremental codec may certify a page; no public unchecked factory.
    static GeometryPage decoded(String id,long revision,Bounds bounds,BlockPalette palette,SparseOctree<BlockSample> cells) {
        return new GeometryPage(id,revision,bounds,palette,cells,true);
    }
    public String id() { return id; }
    public long revision() { return revision; }
    public Bounds bounds() { return bounds; }
    public BlockPalette palette() { return palette; }
    public SparseOctree<BlockSample> cells() { return cells; }
    public List<SparseOctree.Cell<BlockSample>> knownCells() { return cells.cells(MAX_LEAVES); }
    @Override public boolean equals(Object other) {
        return other instanceof GeometryPage p && revision==p.revision && id.equals(p.id) && bounds.equals(p.bounds)
                && palette.equals(p.palette) && cells.equals(p.cells);
    }
    @Override public int hashCode() { return Objects.hash(id,revision,bounds,palette,cells); }
    @Override public String toString() { return "GeometryPage[id="+id+", revision="+revision+", bounds="+bounds+"]"; }
}
