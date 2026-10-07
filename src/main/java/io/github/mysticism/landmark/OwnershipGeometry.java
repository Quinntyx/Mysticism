package io.github.mysticism.landmark;

import java.util.*;

/** Worker-only, source-coordinate 3D ownership algebra. Expanded-cell limit is per operation,
 * not a catalogue limit. Sparse unowned/UNKNOWN space is never silently filled. */
final class OwnershipGeometry {
    static final int CELL_BUDGET=131072;
    static final int[][] DIRECTIONS={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    record Material(BlockPalette.State state,boolean air) {}
    static Map<BlockPoint,Material> expand(SourceGeometry geometry) {
        Map<BlockPoint,Material> result=new HashMap<>();
        for(var page:geometry.pages())for(var cell:page.knownCells()) {
            var b=cell.bounds();var value=new Material(page.palette().state(cell.value().paletteIndex()),cell.value().occupancy()==BlockSample.Occupancy.AIR);
            for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++) {
                if(result.size()==CELL_BUDGET)throw new IllegalArgumentException("ownership operation cell budget");
                if(result.put(new BlockPoint(x,y,z),value)!=null)throw new IllegalArgumentException("overlapping owned masks");
            }
        }
        return result;
    }
    static BlockPoint next(BlockPoint p,int[] d){return new BlockPoint(p.x()+d[0],p.y()+d[1],p.z()+d[2]);}
    static boolean adjacent(Set<BlockPoint> a,Set<BlockPoint> b){for(var p:a)for(var d:DIRECTIONS)if(b.contains(next(p,d)))return true;return false;}
    static List<Set<BlockPoint>> components(Set<BlockPoint> input) {
        Set<BlockPoint> unseen=new HashSet<>(input);List<Set<BlockPoint>> result=new ArrayList<>();ArrayDeque<BlockPoint> frontier=new ArrayDeque<>();
        while(!unseen.isEmpty()) {
            Set<BlockPoint> part=new HashSet<>();BlockPoint start=unseen.iterator().next();unseen.remove(start);frontier.add(start);
            while(!frontier.isEmpty()){var p=frontier.removeFirst();part.add(p);for(var d:DIRECTIONS){var n=next(p,d);if(unseen.remove(n))frontier.add(n);}}
            result.add(part);if(result.size()>256)throw new IllegalArgumentException("ownership component budget");
        }
        result.sort(Comparator.comparing(part->part.stream().min(Comparator.comparingLong(BlockPoint::x).thenComparingLong(BlockPoint::y).thenComparingLong(BlockPoint::z)).orElseThrow(),Comparator.comparingLong(BlockPoint::x).thenComparingLong(BlockPoint::y).thenComparingLong(BlockPoint::z)));
        return result;
    }
    static SourceGeometry geometry(String dimension,String id,long revision,Map<BlockPoint,Material> cells,List<FrontierFace> frontiers,int detail) {return geometry(dimension,id,revision,cells,frontiers,detail,null);}
    static SourceGeometry geometry(String dimension,String id,long revision,Map<BlockPoint,Material> cells,List<FrontierFace> frontiers,int detail,BlockPoint precise) {
        if(detail!=1&&detail!=2&&detail!=4)throw new IllegalArgumentException("source detail");
        Map<BlockPoint,Map<BlockPoint,Material>> groups=new TreeMap<>(Comparator.comparingLong(BlockPoint::x).thenComparingLong(BlockPoint::y).thenComparingLong(BlockPoint::z));
        cells.forEach((p,v)->groups.computeIfAbsent(new BlockPoint(Math.floorDiv(p.x(),8)*8,Math.floorDiv(p.y(),8)*8,Math.floorDiv(p.z(),8)*8),k->new HashMap<>()).put(p,v));
        List<GeometryPage> pages=new ArrayList<>();
        for(var entry:groups.entrySet()) {
            BlockPoint base=entry.getKey();Bounds bounds=Bounds.cube(base.x(),base.y(),base.z(),8);
            List<BlockPalette.State> palette=entry.getValue().values().stream().map(Material::state).distinct().sorted(Comparator.comparing(BlockPalette.State::blockId).thenComparing(v->v.properties().toString())).toList();
            var tree=SparseOctree.<BlockSample>empty(bounds,1,8);
            // Lossless octree coalescing is automatic. Far low-importance storage can quantize
            // materials in *fully known, homogeneous occupancy* cubes; near refinement uses detail=1.
            Set<BlockPoint> assigned=new HashSet<>();int side=Math.clamp(detail,1,4);
            for(var p:entry.getValue().keySet().stream().sorted(Comparator.comparingLong(BlockPoint::x).thenComparingLong(BlockPoint::y).thenComparingLong(BlockPoint::z)).toList()) {
                if(assigned.contains(p))continue;Material v=entry.getValue().get(p);int size=precise!=null&&Math.max(Math.max(Math.abs(p.x()-precise.x()),Math.abs(p.y()-precise.y())),Math.abs(p.z()-precise.z()))<8+side?1:side;
                while(size>1){long x=Math.floorDiv(p.x(),size)*size,y=Math.floorDiv(p.y(),size)*size,z=Math.floorDiv(p.z(),size)*size;boolean uniform=true;
                    for(long a=x;a<x+size;a++)for(long b=y;b<y+size;b++)for(long c=z;c<z+size;c++){var n=entry.getValue().get(new BlockPoint(a,b,c));if(n==null || n.air!=v.air)uniform=false;}
                    if(uniform)break;size/=2;
                }
                Bounds cube=Bounds.cube(Math.floorDiv(p.x(),size)*size,Math.floorDiv(p.y(),size)*size,Math.floorDiv(p.z(),size)*size,size);
                tree=tree.with(cube,new BlockSample(v.air?BlockSample.Occupancy.AIR:BlockSample.Occupancy.SOLID,palette.indexOf(v.state)),256);
                for(long x=cube.minX();x<cube.maxX();x++)for(long y=cube.minY();y<cube.maxY();y++)for(long z=cube.minZ();z<cube.maxZ();z++)assigned.add(new BlockPoint(x,y,z));
            }
            pages.add(new GeometryPage(LandmarkIds.geometryPage(dimension,id,base,8),revision,bounds,new BlockPalette(palette),tree));
        }
        if(pages.size()>LandmarkNbt.MAX_GEOMETRY_REFS)throw new IllegalArgumentException("ownership page budget");
        return new SourceGeometry(pages,frontiers);
    }
    static Bounds bounds(BlockPoint anchor,SourceGeometry geometry){Bounds b=Bounds.cube(anchor.x(),anchor.y(),anchor.z(),1);for(var p:geometry.pages())b=b.union(p.bounds());return b;}
}
