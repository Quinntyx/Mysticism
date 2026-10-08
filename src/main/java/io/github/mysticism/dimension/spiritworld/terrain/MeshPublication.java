package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Publication, not retention, owns the mesh budget. Merge only exact equal opaque cuboids
 * outside the body guard; a one-layer floor need not lose its horizon just because a cubic
 * octree cannot merge it. If exact geometry still exceeds the wire budget, admit real cells
 * throughout the footprint, not a nearest-only prefix. No inferred solids or collision-only LOD. */
final class MeshPublication {
    static final double BODY_RADIUS=5;
    record Patch(Vec3d min,Vec3d size,SourceMeshBuilder.Tile tile,String owner) {}
    private record MergeKey(double a,double b,double sa,double sb,SourceMeshBuilder.Tile tile,String owner) {}
    private record Candidate(Patch patch,Vec3d min,Vec3d x,Vec3d y,Vec3d z,Box bounds,double distance) {}
    private record Bucket(int x,int y,int z) {}
    private MeshPublication() {}

    static List<TerrainMeshFrame.Cell> append(List<SourceMeshBuilder.Node> nodes,String dimension,Vec3d sourceOrigin,
            Vec3d root,Vec3d ax,Vec3d ay,Vec3d az,Vec3d viewer,float alpha,int limit,
            List<TerrainMeshFrame.Material> materials,Map<TerrainMeshFrame.Material,Integer> palette,
            List<TerrainMeshFrame.Cell> cells,Set<Long> keys) {
        if(Math.abs(ax.dotProduct(ay.crossProduct(az)))<1e-6)return List.of();
        List<Patch> fine=new ArrayList<>(),far=new ArrayList<>();
        for(var n:nodes) {
            Patch patch=new Patch(new Vec3d(n.position().getX(),n.position().getY(),n.position().getZ()),
                    new Vec3d(n.side(),n.side(),n.side()),n.tile(),n.ownerId());
            Candidate c=project(patch,sourceOrigin,root,ax,ay,az,viewer);
            if(c.distance>(double)DiscoveryBudget.RENDER_DISTANCE*DiscoveryBudget.RENDER_DISTANCE)continue;
            if(n.tile().cube() && n.tile().collision().equals(List.of(SourceMeshBuilder.UNIT)) && c.distance>BODY_RADIUS*BODY_RADIUS)far.add(patch);
            else fine.add(patch);
        }
        // Two fixed sweeps, O(n log n), no volume expansion and no material/owner averaging.
        for(int pass=0;pass<2;pass++)for(int axis:new int[]{0,2,1})far=merge(far,axis);
        fine.addAll(far);
        List<Candidate> projected=new ArrayList<>(fine.size());
        for(Patch patch:fine)projected.add(project(patch,sourceOrigin,root,ax,ay,az,viewer));
        List<TerrainMeshFrame.Cell> added=new ArrayList<>();
        for(Candidate c:select(projected,Math.min(limit,TerrainMeshFrame.MAX_CELLS-cells.size()),viewer)) {
            Patch patch=c.patch;Integer material=palette.get(patch.tile.material());
            if(material==null){if(materials.size()==TerrainMeshFrame.MAX_MATERIALS)continue;
                material=materials.size();materials.add(patch.tile.material());palette.put(patch.tile.material(),material);}
            long key=key(patch.owner.isEmpty()?"unknown:"+dimension:patch.owner,patch.min,patch.size);
            if(!keys.add(key))continue;
            var tile=patch.tile;
            var cell=new TerrainMeshFrame.Cell(key,material,patch.owner,patch.min,c.min,patch.size,c.x,c.y,c.z,
                    tile.color(),tile.light(),alpha,tile.collision());
            cells.add(cell);added.add(cell);
        }
        return added;
    }
    private static long key(String owner,Vec3d min,Vec3d size) {
        // Keep old cubic keys, including held-frame provenance and incremental network identity.
        if(size.x==size.y && size.y==size.z)return SourceMeshBuilder.key(owner,min,(int)size.x);
        long h=SourceMeshBuilder.key(owner,min,0);
        h=SourceMeshBuilder.hashDouble(h,size.x);
        h=SourceMeshBuilder.hashDouble(h,size.y);
        return SourceMeshBuilder.hashDouble(h,size.z);
    }
    private static Candidate project(Patch p,Vec3d source,Vec3d root,Vec3d ax,Vec3d ay,Vec3d az,Vec3d viewer) {
        Vec3d offset=p.min.subtract(source),min=root.add(ax.multiply(offset.x)).add(ay.multiply(offset.y)).add(az.multiply(offset.z));
        Vec3d x=ax.multiply(p.size.x),y=ay.multiply(p.size.y),z=az.multiply(p.size.z);
        Box b=new Box(min.x+Math.min(0,x.x)+Math.min(0,y.x)+Math.min(0,z.x),
                min.y+Math.min(0,x.y)+Math.min(0,y.y)+Math.min(0,z.y),min.z+Math.min(0,x.z)+Math.min(0,y.z)+Math.min(0,z.z),
                min.x+Math.max(0,x.x)+Math.max(0,y.x)+Math.max(0,z.x),
                min.y+Math.max(0,x.y)+Math.max(0,y.y)+Math.max(0,z.y),min.z+Math.max(0,x.z)+Math.max(0,y.z)+Math.max(0,z.z));
        return new Candidate(p,min,x,y,z,b,SourceMeshBuilder.distanceSquared(b,viewer));
    }
    private static double component(Vec3d p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
    private static Vec3d with(Vec3d p,int axis,double value){return axis==0?new Vec3d(value,p.y,p.z):axis==1?new Vec3d(p.x,value,p.z):new Vec3d(p.x,p.y,value);}
    private static List<Patch> merge(List<Patch> input,int axis) {
        int a=(axis+1)%3,b=(axis+2)%3;
        Map<MergeKey,List<Patch>> groups=new HashMap<>();
        for(Patch p:input)groups.computeIfAbsent(new MergeKey(component(p.min,a),component(p.min,b),component(p.size,a),component(p.size,b),p.tile,p.owner),ignored->new ArrayList<>()).add(p);
        List<Patch> result=new ArrayList<>();
        for(List<Patch> group:groups.values()) {
            group.sort(Comparator.comparingDouble(p->component(p.min,axis)));Patch previous=null;
            for(Patch p:group) {
                if(previous!=null && component(previous.min,axis)+component(previous.size,axis)==component(p.min,axis)
                        && component(previous.size,axis)+component(p.size,axis)<=64) {
                    previous=new Patch(previous.min,with(previous.size,axis,component(previous.size,axis)+component(p.size,axis)),previous.tile,previous.owner);
                } else {if(previous!=null)result.add(previous);previous=p;}
            }
            if(previous!=null)result.add(previous);
        }
        return result;
    }
    /** Same spatial admission for the persisted-octree reservoir; nearest-only retention must
     * not erase the horizon before the frame assembler ever sees it. */
    static List<SourceMeshBuilder.Node> coverageNodes(Collection<SourceMeshBuilder.Node> nodes,int limit,Vec3d focus,
            java.util.function.ToDoubleFunction<SourceMeshBuilder.Node> distance) {
        List<Candidate> candidates=new ArrayList<>(nodes.size());
        Vec3d x=new Vec3d(1,0,0),y=new Vec3d(0,1,0),z=new Vec3d(0,0,1);
        for(var n:nodes) {
            Patch patch=new Patch(Vec3d.of(n.position()),new Vec3d(n.side(),n.side(),n.side()),n.tile(),n.ownerId());
            Candidate c=project(patch,Vec3d.ZERO,Vec3d.ZERO,x,y,z,focus);
            candidates.add(new Candidate(patch,c.min,c.x,c.y,c.z,c.bounds,distance.applyAsDouble(n)));
        }
        List<SourceMeshBuilder.Node> result=new ArrayList<>();
        for(Candidate c:select(candidates,limit,focus))result.add(new SourceMeshBuilder.Node(
                net.minecraft.util.math.BlockPos.ofFloored(c.patch.min),(int)c.patch.size.x,c.patch.tile,c.patch.owner));
        return result;
    }
    private static final Comparator<Candidate> ORDER=Comparator.comparingDouble(Candidate::distance)
            .thenComparingDouble(c->c.patch.min.x).thenComparingDouble(c->c.patch.min.y).thenComparingDouble(c->c.patch.min.z)
            .thenComparing(c->c.patch.owner).thenComparingDouble(c->c.patch.size.x)
            .thenComparingDouble(c->c.patch.size.y).thenComparingDouble(c->c.patch.size.z);
    private static List<Candidate> select(List<Candidate> input,int limit,Vec3d viewer) {
        if(limit<=0)return List.of();input.sort(ORDER);if(input.size()<=limit)return input;
        List<Candidate> result=new ArrayList<>();Set<Candidate> selected=new HashSet<>();
        // Exact near/body geometry wins. Other cells are distributed across world-space buckets.
        int bodyBudget=limit-Math.min(128,limit/4);
        for(Candidate c:input)if(c.distance<=BODY_RADIUS*BODY_RADIUS && result.size()<bodyBudget){result.add(c);selected.add(c);}
        int remaining=limit-result.size();if(remaining==0)return result;
        List<Candidate> far=new ArrayList<>();for(Candidate c:input)if(!selected.contains(c))far.add(c);
        Map<Bucket,Candidate> representatives=null;
        // Deep projections can expose a wider source footprint than their world-space range.
        for(long side=8;side<=(1L<<32);side*=2) {
            Map<Bucket,Candidate> bins=new HashMap<>();
            for(Candidate c:far) {
                Vec3d center=c.bounds.getCenter().subtract(viewer);
                Bucket bucket=new Bucket((int)Math.floor(center.x/side),(int)Math.floor(center.y/side),(int)Math.floor(center.z/side));
                // Stable closest geometry within each bin; admission cannot collapse to a central radius.
                bins.putIfAbsent(bucket,c);
            }
            representatives=bins;if(bins.size()<=remaining)break;
        }
        List<Candidate> distributed=new ArrayList<>(representatives.values());distributed.sort(ORDER);
        for(Candidate c:distributed)if(result.size()<limit){result.add(c);selected.add(c);}
        for(Candidate c:far)if(result.size()<limit && selected.add(c))result.add(c);
        result.sort(ORDER);return result;
    }
}
