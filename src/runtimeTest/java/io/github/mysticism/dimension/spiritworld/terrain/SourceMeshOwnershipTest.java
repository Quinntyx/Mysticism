package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SparseOctree;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Real source compaction, octree growth and shared affine SAT; no game/model bootstrap. */
public final class SourceMeshOwnershipTest {
    private static final SourceMeshBuilder.Tile STONE=new SourceMeshBuilder.Tile(
            new TerrainMeshFrame.Material("minecraft:stone",Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0xf000f0,false,true);
    private static final SourceMeshBuilder.Tile AIR=new SourceMeshBuilder.Tile(
            new TerrainMeshFrame.Material("minecraft:air",Map.of()),List.of(),0xffffff,0xf000f0,true,false);
    private static final Vec3d FAR=new Vec3d(100,100,100);
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        sourceKeys();sourcePriority();sparseSamples();ownershipRebuild();grownSource();projectedCollision();randomizedMembership();
        System.out.println("SourceMeshOwnershipTest passed: "+checks+" checks");
    }
    private static List<SourceMeshBuilder.Node> base(BlockPos p,int side,String owner) {
        return List.of(new SourceMeshBuilder.Node(p,side,STONE,owner));
    }
    private static Map<BlockPos,SourceMeshBuilder.Node> expand(List<SourceMeshBuilder.Node> nodes) {
        Map<BlockPos,SourceMeshBuilder.Node> result=new HashMap<>();
        for(var n:nodes)for(BlockPos p:BlockPos.iterate(n.position(),n.position().add(n.side()-1,n.side()-1,n.side()-1)))
            check(result.put(p.toImmutable(),n)==null,"overlapping source nodes at "+p);
        return result;
    }
    private static void sourceKeys() {
        Set<Long> keys=new HashSet<>();
        for(String owner:List.of("a","b","unknown:minecraft:overworld"))
            for(int side=1;side<=64;side*=2)
                for(int x=-8;x<=8;x++)for(int y=-8;y<=8;y++)for(int z=-8;z<=8;z++) {
                    Vec3d source=new Vec3d(x,y,z);long key=SourceMeshBuilder.key(owner,source,side);
                    check(keys.add(key),"distinct signed source-grid cells must not alias (owner/position/side): "+source);
                    check(key==SourceMeshBuilder.key(owner,source,side),"stable source key");
                }
    }
    private static void sourcePriority() {
        Map<BlockPos,SourceMeshBuilder.Tile> samples=new HashMap<>();Map<BlockPos,String> owners=new HashMap<>();
        for(BlockPos p:BlockPos.iterate(new BlockPos(16,16,16),new BlockPos(19,19,19))) {
            samples.put(p.toImmutable(),STONE);owners.put(p.toImmutable(),"a");
        }
        BlockPos closer=new BlockPos(14,16,16);samples.put(closer,STONE);owners.put(closer,"a");
        var nodes=SourceMeshBuilder.compact(samples,new Vec3d(0,16,16),owners);
        check(nodes.size()==2 && nodes.get(1).side()==4,"uniform source cube compacts without overlap");
        check(nodes.getFirst().position().equals(closer),"budget priority uses actual source bounds, not symmetric expansion");
    }
    private static void sparseSamples() {
        BlockPos min=new BlockPos(-8,-8,-8),left=min,right=min.add(7,7,7);
        var samples=Map.of(left,AIR,right,STONE);
        var nodes=SourceMeshBuilder.compose(base(min,8,"old"),samples,Map.of(right,"new"),null,FAR);
        var mask=expand(nodes);
        check(mask.size()==511,"sparse replacement must not erase unsampled envelope");
        check(!mask.containsKey(left),"sampled AIR removes exactly one source cell");
        check(mask.get(right).ownerId().equals("new"),"sampled solid retains exact owner");
        check(mask.get(min.add(3,3,3)).ownerId().equals("old"),"unknown sample retains producing owner");
        check(expand(SourceMeshBuilder.compose(base(min,8,"old"),Map.of(),Map.of(),null,FAR)).size()==512,
                "empty live patch must retain persisted geometry");
        var full=new HashMap<BlockPos,SourceMeshBuilder.Tile>();
        for(BlockPos p:BlockPos.iterate(min,right))full.put(p.toImmutable(),AIR);
        check(SourceMeshBuilder.compose(base(min,8,"old"),full,Map.of(),null,FAR).isEmpty(),"complete AIR patch removes all geometry");
    }
    private static void ownershipRebuild() {
        BlockPos p=new BlockPos(-4,0,-4);Bounds range=Bounds.cube(-4,0,-4,2);
        Map<BlockPos,String> owners=new HashMap<>();
        for(BlockPos at:BlockPos.iterate(p,p.add(1,1,1)))owners.put(at.toImmutable(),at.getX()==-4?"a":"b");
        owners.remove(p.add(1,1,1));
        var samples=Map.of(p,STONE); // nonempty tiles used to bypass relabeling of the persisted leaves
        var nodes=SourceMeshBuilder.compose(base(p,4,"stale"),samples,owners,range,FAR);
        var mask=expand(nodes);
        for(var e:mask.entrySet()) {
            BlockPos at=e.getKey();String expected=range.contains(at.getX(),at.getY(),at.getZ())?owners.getOrDefault(at,""):"stale";
            check(expected.equals(e.getValue().ownerId()),"exact AIR/SOLID ownership is not an AABB or producer label");
        }
        check(mask.size()==64,"ownership relabeling cannot orphan geometry/collision");
        check(expand(SourceMeshBuilder.compose(base(p,4,"stale"),Map.of(),owners,range,FAR)).equals(mask),
                "geometry-only rebuilds must keep the same source ownership mask");
        var close=expand(SourceMeshBuilder.compose(base(p,4,"stale"),samples,owners,range,new Vec3d(-4,0,-4)));
        check(close.keySet().equals(mask.keySet()),"near/far refinement preserves absolute source membership");
        for(var at:mask.keySet())check(close.get(at).ownerId().equals(mask.get(at).ownerId()),
                "near/far refinement cannot reinterpret ownership in projected coordinates");
        check(expand(SourceMeshBuilder.compose(base(p,4,"stale"),samples,owners,range,FAR)).equals(mask),
                "later live recompaction reapplies ownership to the same persisted base");
        var grown=new HashMap<>(owners);grown.put(p.add(1,1,1),"b");
        check(expand(SourceMeshBuilder.compose(base(p,4,"stale"),samples,grown,range,FAR)).get(p.add(1,1,1)).ownerId().equals("b"),
                "newly discovered source cell becomes owned without moving geometry");
    }
    private static void grownSource() {
        var original=SparseOctree.<String>empty(Bounds.cube(-8,-8,-8,8),1,128)
                .with(Bounds.cube(-8,-8,-8,8),"a",10000);
        var grown=original.with(Bounds.cube(24,24,24,8),"b",10000)
                .with(Bounds.cube(-32,-32,-32,8),"c",10000);
        List<SourceMeshBuilder.Node> nodes=new ArrayList<>();
        var cursor=grown.cursor(grown.rootBounds());
        while(!cursor.complete())for(var c:cursor.advance(3,1)) {
            Bounds b=c.bounds();nodes.add(new SourceMeshBuilder.Node(new BlockPos((int)b.minX(),(int)b.minY(),(int)b.minZ()),
                    (int)(b.maxX()-b.minX()),STONE,c.value()));
        }
        var mask=expand(nodes);
        check(mask.size()==1536,"root growth neither duplicates nor loses source membership");
        check(grown.sample(-1,-1,-1).equals("a") && original.sample(24,24,24)==null,"growth preserves absolute coordinates and immutable parent");
        check(!mask.containsKey(BlockPos.ORIGIN),"expanded root envelope is UNKNOWN, not owned geometry");
        var n=mask.get(new BlockPos(-8,-8,-8));
        check(SourceMeshBuilder.key(n.ownerId(),new Vec3d(-8,-8,-8),n.side())
                ==SourceMeshBuilder.key("a",new Vec3d(-8,-8,-8),8),"growth preserves source identity/key");
    }
    private static TerrainMeshFrame project(List<SourceMeshBuilder.Node> nodes,Vec3d translation,Vec3d x,Vec3d y,Vec3d z) {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();
        for(var n:nodes) {
            Vec3d p=new Vec3d(n.position().getX(),n.position().getY(),n.position().getZ());int side=n.side();
            cells.add(new TerrainMeshFrame.Cell(SourceMeshBuilder.key(n.ownerId(),p,side),0,n.ownerId(),p,
                    translation.add(x.multiply(p.x)).add(y.multiply(p.y)).add(z.multiply(p.z)),new Vec3d(side,side,side),
                    x.multiply(side),y.multiply(side),z.multiply(side),0xffffff,0xf000f0,1,n.tile().collision()));
        }
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,translation,List.of(STONE.material()),cells);
    }
    private static void projectedCollision() {
        var nodes=SourceMeshBuilder.compose(base(new BlockPos(-2,0,-2),4,"a"),Map.of(new BlockPos(-2,0,-2),AIR),Map.of(),null,FAR);
        for(Vec3d translation:List.of(Vec3d.ZERO,new Vec3d(300,40,-500))) {
            // Independent observer-local shear/scale, with the same absolute source membership.
            var frame=project(nodes,translation,new Vec3d(.7,0,.2),new Vec3d(.2,1,0),new Vec3d(-.1,0,.8));
            var index=new MeshCollision.Index(frame);
            var identity=project(nodes,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1));
            for(var c:frame.cells()) {
                Vec3d top=c.point(.5,1,.5);
                Box body=new Box(top.x-.02,top.y+.2,top.z-.02,top.x+.02,top.y+.3,top.z+.02);
                var hit=index.sweep(body,new Vec3d(0,-.5,0));
                check(hit.isPresent(),"visible projected leaf retains affine collision");
                check(hit.orElseThrow().cell().sourceMin().equals(c.sourceMin()),"SAT contact returns producing source cell");
            }
            check(expand(nodes).size()==63,"projection/translation cannot change source membership");
            for(int i=0;i<frame.cells().size();i++)check(frame.cells().get(i).key()==identity.cells().get(i).key(),
                    "per-player projection does not change ownership identity");
        }
    }
    private static void randomizedMembership() {
        Random random=new Random(1937);BlockPos min=new BlockPos(-8,-8,-8);
        for(int round=0;round<40;round++) {
            Map<BlockPos,SourceMeshBuilder.Tile> samples=new HashMap<>();
            for(int i=0;i<50;i++)samples.put(min.add(random.nextInt(8),random.nextInt(8),random.nextInt(8)),random.nextBoolean()?AIR:STONE);
            var mask=expand(SourceMeshBuilder.compose(base(min,8,"a"),samples,Map.of(),null,FAR));
            for(BlockPos p:BlockPos.iterate(min,min.add(7,7,7)))
                check(mask.containsKey(p)==(samples.get(p)!=AIR),"randomized exact source replacement at "+p);
        }
    }
}
