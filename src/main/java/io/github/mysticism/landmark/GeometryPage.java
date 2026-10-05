package io.github.mysticism.landmark;

import java.util.List;
import java.util.Objects;

/** Bounded absolute-coordinate page. Leaves store block occupancy plus a resolvable palette index. */
public record GeometryPage(String id, long revision, Bounds bounds, BlockPalette palette, SparseOctree<BlockSample> cells) {
    public static final int MAX_SIDE=64;
    public static final int MAX_LEAVES=32768;
    public GeometryPage {
        Objects.requireNonNull(id); Objects.requireNonNull(palette); Objects.requireNonNull(cells);
        if (id.isBlank() || revision<0 || bounds.maxX()-bounds.minX()>MAX_SIDE || bounds.maxY()-bounds.minY()>MAX_SIDE
                || bounds.maxZ()-bounds.minZ()>MAX_SIDE) throw new IllegalArgumentException("geometry page limits");
        for (var cell:cells.cells(MAX_LEAVES)) {
            if (!bounds.contains(cell.bounds()) || cell.value().paletteIndex()>=palette.states().size()) throw new IllegalArgumentException("invalid geometry leaf");
            String block=palette.state(cell.value().paletteIndex()).blockId();
            boolean air=block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air");
            if (air != (cell.value().occupancy()==BlockSample.Occupancy.AIR)) throw new IllegalArgumentException("occupancy/palette mismatch");
        }
    }
    public List<SparseOctree.Cell<BlockSample>> knownCells() { return cells.cells(MAX_LEAVES); }
}
