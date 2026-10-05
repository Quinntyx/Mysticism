package io.github.mysticism.landmark;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Immutable page references and explicit unknown frontiers. Empty frontiers permit finalization;
 * they are not proof of cave/mountain classification or physical connectivity.
 */
public record SourceGeometry(List<GeometryPage> pages, List<FrontierFace> frontiers) {
    /** Resolved material includes its originating page; palette indices are page-local. */
    public record MaterialCell(Bounds bounds, BlockSample sample, BlockPalette.State material, String pageId) {}
    public SourceGeometry {
        pages=pages.stream().sorted(Comparator.comparing(GeometryPage::id)).toList();
        if(pages.stream().map(GeometryPage::id).distinct().count()!=pages.size()) throw new IllegalArgumentException("duplicate page");
        // Page AABBs may overlap (two disconnected masks in the same observation cube).
        // Known cells may not: the physical reader must have one unambiguous material.
        for(int i=0;i<pages.size();i++) for(int j=i+1;j<pages.size();j++) {
            GeometryPage a=pages.get(i),b=pages.get(j);
            if(a.bounds().intersects(b.bounds())) for(var cell:a.knownCells())
                if(!b.cells().query(cell.bounds(),1).isEmpty()) throw new IllegalArgumentException("overlapping known geometry requires reconciliation");
        }
        frontiers=frontiers.stream().sorted().distinct().toList();
    }
    public boolean frontierClosed() { return frontiers.isEmpty(); }
    public List<SparseOctree.Cell<BlockSample>> query(Bounds range,int maxCells) {
        if(maxCells<0) throw new IllegalArgumentException("cell budget");
        List<SparseOctree.Cell<BlockSample>> out=new ArrayList<>();
        for(GeometryPage p:pages) if(p.bounds().intersects(range)) out.addAll(p.cells().query(range,maxCells-out.size()));
        return List.copyOf(out);
    }
    public List<MaterialCell> queryMaterials(Bounds range,int maxCells) {
        if(maxCells<0) throw new IllegalArgumentException("cell budget");
        List<MaterialCell> out=new ArrayList<>();
        for(GeometryPage p:pages) if(p.bounds().intersects(range))
            for(var cell:p.cells().query(range,maxCells-out.size()))
                out.add(new MaterialCell(cell.bounds(),cell.value(),p.palette().state(cell.value().paletteIndex()),p.id()));
        return List.copyOf(out);
    }
    public java.util.Optional<MaterialCell> materialAt(long x,long y,long z) {
        for(GeometryPage p:pages) if(p.bounds().contains(x,y,z)) {
            BlockSample sample=p.cells().sample(x,y,z);
            if(sample!=null) return java.util.Optional.of(new MaterialCell(Bounds.cube(x,y,z,1),sample,p.palette().state(sample.paletteIndex()),p.id()));
        }
        return java.util.Optional.empty();
    }
    public BlockSample sample(long x,long y,long z) {
        for(GeometryPage p:pages) if(p.bounds().contains(x,y,z)) {
            BlockSample sample=p.cells().sample(x,y,z); if(sample!=null) return sample;
        }
        return null;
    }
}
