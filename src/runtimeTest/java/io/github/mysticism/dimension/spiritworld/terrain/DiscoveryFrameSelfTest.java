package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Production discovery -> retention -> projection -> spatial admission -> frame regressions.
 * Uses hand-built observed materials; no registry/server/model boot or invented source samples. */
public final class DiscoveryFrameSelfTest {
    private static int checks;
    private static final Vec3d X=new Vec3d(1,0,0),Y=new Vec3d(0,1,0),Z=new Vec3d(0,0,1);
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static SourceMeshBuilder.Tile cube(String id) {
        return new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material(id,Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0xf000f0,false,true);
    }
    private static final SourceMeshBuilder.Tile STONE=cube("minecraft:stone"),DIRT=cube("minecraft:dirt"),GRASS=cube("minecraft:grass_block");

    /** Same pure geometry/admission path used by SpiritTerrainService.append, without a live player. */
    private static TerrainMeshFrame frame(List<SourceMeshBuilder.Node> nodes,Vec3d viewer,int view,boolean shallow,Vec3d ax,Vec3d ay,Vec3d az) {
        int horizon=SourceDiscovery.meshHorizon(view);
        List<SourceDiscovery.Candidate> candidates=new ArrayList<>();
        for(var n:nodes) {
            var c=SourceDiscovery.project(n,viewer,viewer,ax,ay,az,viewer);
            if(c.distance()<=horizon*(double)horizon && Math.abs(c.x().dotProduct(c.y().crossProduct(c.z())))>=1e-6)candidates.add(c);
        }
        var admission=new SourceDiscovery.LocalAdmission(shallow?1024:640);
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();List<TerrainMeshFrame.Cell> cells=new ArrayList<>();Set<Long> keys=new HashSet<>();
        for(var c:SourceDiscovery.localOrder(candidates,viewer,horizon)) {
            long key=SourceMeshBuilder.key(c.node().ownerId(),c.source(),c.node().size());
            if(keys.contains(key) || !admission.admit(c.distance()))continue;
            keys.add(key);int index=materials.indexOf(c.node().tile().material());
            if(index<0){index=materials.size();materials.add(c.node().tile().material());}
            cells.add(c.cell(index,key,1));
        }
        check(cells.size()<= (shallow?1024:640)+SourceDiscovery.LocalAdmission.FAR_ADMISSION,"bounded frame admission");
        return new TerrainMeshFrame(1,shallow,"minecraft:overworld",viewer,viewer,materials,cells);
    }
    private static boolean sourceContains(TerrainMeshFrame.Cell c,double x,double y,double z) {
        return x>=c.sourceMin().x && x<c.sourceMin().x+c.size().x && y>=c.sourceMin().y && y<c.sourceMin().y+c.size().y && z>=c.sourceMin().z && z<c.sourceMin().z+c.size().z;
    }
    private static boolean sourceContains(SourceMeshBuilder.Node n,int x,int y,int z) {
        return SourceMeshBuilder.nodeBox(n).contains(new Vec3d(x+.5,y+.5,z+.5));
    }
    private static void layeredGround() {
        Vec3d viewer=new Vec3d(.5,64,.5);int view=10;
        var scheduler=new SourceDiscovery.Scheduler();scheduler.replan(viewer,view);
        Map<Bounds,List<SourceMeshBuilder.Node>> discovered=new LinkedHashMap<>();
        Bounds tile;int observed=0;
        while((tile=scheduler.next(0))!=null) {
            scheduler.pending(tile);Map<BlockPos,SourceMeshBuilder.Tile> solids=new HashMap<>();
            for(int y=61;y<=63;y++)if(y>=tile.minY() && y<tile.maxY())
                for(int x=(int)tile.minX();x<tile.maxX();x++)for(int z=(int)tile.minZ();z<tile.maxZ();z++) {
                    solids.put(new BlockPos(x,y,z),y==63?GRASS:y==62?DIRT:STONE);observed++;
                }
            discovered.put(tile,SourceDiscovery.farNodes(solids,new Vec3d(tile.minX()+16,tile.minY()+16,tile.minZ()+16)));
            scheduler.completed(tile,true,0);
        }
        var nodes=SourceDiscovery.retain(discovered,scheduler,viewer,SourceDiscovery.MAX_NODES,0);
        check(observed>300000,"ordinary layered ground exceeds a tiny cell neighborhood");
        check(nodes.size()<1000,"thin equal-material layers batch as exact prisms, not octree cubes");
        for(boolean shallow:new boolean[]{true,false}) {
            var f=frame(nodes,viewer,view,shallow,X,Y,Z);
            var collision=new MeshCollision.Index(f);
            for(double[] probe:new double[][]{{159.5,63.5,.5},{-159.5,63.5,.5},{.5,63.5,159.5},{.5,63.5,-159.5}}) {
                check(f.cells().stream().anyMatch(c->sourceContains(c,probe[0],probe[1],probe[2])),"ground reaches each requested 160-block view-distance edge, shallow="+shallow);
                Box body=new Box(probe[0]-.3,64.1,probe[2]-.3,probe[0]+.3,65.9,probe[2]+.3);
                check(collision.sweep(body,new Vec3d(0,-.2,0)).filter(h->h.normal().y>.99 && Math.abs(h.time()-.5)<1e-6).isPresent(),"admitted distant ground has actual SAT collision, not render-only terrain");
            }
            for(var c:f.cells()) {
                check(c.sourceMin().y>=61 && c.sourceMin().y+c.size().y<=64,"no geometry above or below observed ground");
                check(c.bounds().equals(c.bounds(SourceMeshBuilder.UNIT)),"render and collision use the same exact prism extent");
                check(c.axisX().equals(X.multiply(c.size().x)) && c.axisY().equals(Y.multiply(c.size().y)) && c.axisZ().equals(Z.multiply(c.size().z)),"source/render dimensions match");
            }
        }
        // Deformed deep views use the same nonuniform size columns, not cubic collision impostors.
        Vec3d skew=new Vec3d(.25,1,.15);var deformed=frame(nodes,viewer,view,false,X,skew,Z);
        check(deformed.cells().stream().anyMatch(c->Math.sqrt(SourceMeshBuilder.distanceSquared(c.bounds(),viewer))>140),"deep affine frame retains far ground");
        for(var c:deformed.cells()) {
            check(c.axisY().equals(skew.multiply(c.size().y)),"deep collision/render share rectangular affine projection");
            var middle=c.point(.5,.5,.5);
            check(!MeshCollision.bodyClear(deformed,new Box(middle.x-.001,middle.y-.001,middle.z-.001,middle.x+.001,middle.y+.001,middle.z+.001)),"deformed exact prism interior participates in SAT collision");
        }
    }
    private static void spatialAdmission() {
        // This deliberately cannot coalesce: varied source cells still get fair spatial coverage.
        Vec3d viewer=new Vec3d(.5,64,.5);List<SourceMeshBuilder.Node> nodes=new ArrayList<>();
        for(int x=-160;x<=160;x++)for(int z=-160;z<=160;z++)nodes.add(new SourceMeshBuilder.Node(new BlockPos(x,63,z),1,(x+z)%2==0?STONE:DIRT));
        var f=frame(nodes,viewer,10,true,X,Y,Z);
        int near=0,far=0;Set<String> quadrants=new HashSet<>();
        for(var c:f.cells())if(SourceMeshBuilder.distanceSquared(c.bounds(),viewer)<=32*32)near++;else {
            far++;if(SourceMeshBuilder.distanceSquared(c.bounds(),viewer)>140*140)quadrants.add((c.sourceMin().x<0?"-":"+")+(c.sourceMin().z<0?"-":"+"));
        }
        check(near==1024 && far==256,"near allowance unchanged and far lane remains bounded");
        check(quadrants.size()==4,"unmergeable cells reach all four far quadrants, not the nearest 33-block ring");
        Collections.reverse(nodes);
        check(f.cells().equals(frame(nodes,viewer,10,true,X,Y,Z).cells()),"spatial admission is independent of input/map iteration order");
        var full=new ArrayList<>(nodes);
        for(BlockPos pos:List.of(new BlockPos(511,63,0),new BlockPos(-511,63,0),new BlockPos(0,63,511),new BlockPos(0,63,-511)))
            full.add(new SourceMeshBuilder.Node(pos,1,GRASS));
        var wide=frame(full,viewer,32,false,X,Y,Z);
        for(double[] probe:new double[][]{{511.5,63.5,.5},{-510.5,63.5,.5},{.5,63.5,511.5},{.5,63.5,-510.5}})
            check(wide.cells().stream().anyMatch(c->sourceContains(c,probe[0],probe[1],probe[2])),"dense local cells do not starve 32-chunk distant observations");
    }
    private static void exactBatching() {
        List<SourceMeshBuilder.Node> source=new ArrayList<>();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)if(x!=3 || z!=3)
            source.add(new SourceMeshBuilder.Node(new BlockPos(x,0,z),1,STONE,x<4?"left":"right"));
        var partial=new SourceMeshBuilder.Tile(DIRT.material(),List.of(new Box(0,0,0,1,.5,1)),0xffffff,0xf000f0,false,false);
        var slab=new SourceMeshBuilder.Node(new BlockPos(0,1,0),1,partial,"left");source.add(slab);
        var batched=SourceDiscovery.coalesce(source);double volume=0;
        for(var n:batched) {
            volume+=n.size().x*n.size().y*n.size().z;
            check(!sourceContains(n,3,0,3),"batching never fills an observed hole");
            if(n.tile().equals(STONE))check(n.ownerId().equals("left")?n.position().getX()+n.size().x<=4:n.position().getX()>=4,"batches never cross ownership boundaries");
        }
        check(volume==64,"mesh batching preserves every observed source cell and no others");
        check(batched.contains(slab),"partial vanilla shapes remain unchanged");
        // Near replacement / exact-owner refinement must handle prisms without treating side as size.
        var prism=new SourceMeshBuilder.Node(new BlockPos(0,0,0),new Vec3d(8,1,8),STONE,"");
        Bounds cut=new Bounds(2,0,2,4,1,4);
        var replaced=SourceMeshBuilder.replaceNear(List.of(prism),List.of(),cut,Vec3d.ZERO);double retained=0;
        for(var n:replaced){retained+=n.size().x*n.size().y*n.size().z;check(!sourceContains(n,2,0,2),"near sampled air removes only the actual cut");}
        check(retained==60,"exact prism subtraction retains all outside ground");
        // Coalescing can produce odd-sized cubes; they are not power-of-two octree leaves.
        var odd=new SourceMeshBuilder.Node(BlockPos.ORIGIN,new Vec3d(3,3,3),STONE,"");
        var oddPieces=SourceMeshBuilder.replaceNear(List.of(odd),List.of(),new Bounds(1,1,1,2,2,2),Vec3d.ZERO);
        check(oddPieces.stream().mapToDouble(n->n.size().x*n.size().y*n.size().z).sum()==26,"odd cube subtraction does not lose the uncut outer shell");
        for(var n:oddPieces)check(!sourceContains(n,1,1,1),"odd cube subtraction never refills sampled air");
        Map<BlockPos,String> owners=new HashMap<>();for(int x=0;x<8;x++)for(int z=0;z<8;z++)owners.put(new BlockPos(x,0,z),x<4?"left":"right");
        var refined=SourceMeshBuilder.splitOwnership(List.of(prism),owners,new Bounds(0,0,0,8,1,8),new Vec3d(100,100,100));
        for(var n:refined)check(n.ownerId().equals("left")?n.position().getX()+n.size().x<=4:n.ownerId().equals("right") && n.position().getX()>=4,"prism ownership is exact after refinement");
        check(SourceMeshBuilder.key("",Vec3d.ZERO,new Vec3d(8,1,8))!=SourceMeshBuilder.key("",Vec3d.ZERO,new Vec3d(8,2,8)),"prism keys include all source dimensions");
    }
    private static void sparseNearReplacement() {
        // Two tiny observed patches in a much wider persisted/discovered surface, with observed
        // air at one end and a changed partial solid at the other. Their bounding box is NOT coverage.
        var air=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:air",Map.of()),List.of(),0,0,true,false);
        var slab=new SourceMeshBuilder.Tile(DIRT.material(),List.of(new Box(0,0,0,1,.5,1)),0xffffff,0xf000f0,false,false);
        List<SourceMeshBuilder.Node> base=List.of(
                new SourceMeshBuilder.Node(new BlockPos(-32,63,-16),new Vec3d(32,1,32),STONE,"left"),
                new SourceMeshBuilder.Node(new BlockPos(0,63,-16),new Vec3d(32,1,32),STONE,"right"));
        Map<BlockPos,SourceMeshBuilder.Tile> sampled=new HashMap<>();
        sampled.put(new BlockPos(-31,63,0),air);sampled.put(new BlockPos(30,63,0),slab);
        var near=SourceMeshBuilder.compact(sampled,Vec3d.ZERO);
        var result=SourceMeshBuilder.replaceNear(base,near,sampled.keySet(),Vec3d.ZERO);
        check(result.containsAll(near),"sparse solid/slab near nodes are preserved unchanged");
        check(result.stream().mapToDouble(n->n.size().x*n.size().y*n.size().z).sum()==2047,"only observed air removes solid volume, not the sparse bounding box");
        for(int x=-32;x<32;x++)for(int z=-16;z<16;z++) {
            final int px=x,pz=z;
            var hits=result.stream().filter(n->sourceContains(n,px,63,pz)).toList();
            boolean removed=x==-31 && z==0;
            check(hits.size()==(removed?0:1),"every unknown gap retains exactly one original source cell at "+x+","+z);
            if(!removed) {
                var n=hits.getFirst();boolean changed=x==30 && z==0;
                check(n.tile().equals(changed?slab:STONE),"only sampled solid changes material/collision");
                check(changed?n.ownerId().isEmpty():n.ownerId().equals(x<0?"left":"right"),"unsampled far provenance remains unchanged");
            }
        }
        var f=frame(result,new Vec3d(.5,64,.5),4,true,X,Y,Z);
        Box gapBody=new Box(-.3,64.1,-.3,.3,65.9,.3);
        check(new MeshCollision.Index(f).sweep(gapBody,new Vec3d(0,-.2,0)).isPresent(),"unknown gap between sparse patches still has rendered SAT floor collision");
        var reverse=new LinkedHashSet<>(sampled.keySet().stream().sorted(Comparator.comparingLong((BlockPos p)->p.asLong()).reversed()).toList());
        check(result.equals(SourceMeshBuilder.replaceNear(base,near,reverse,Vec3d.ZERO)),"sparse-mask compaction is deterministic");
        check(base.equals(SourceMeshBuilder.replaceNear(base,List.of(),Set.of(),Vec3d.ZERO)),"empty observations preserve every base node");
        // Dense coverage, negative coordinates and odd prism extents use the same exact mask path.
        var odd=new SourceMeshBuilder.Node(new BlockPos(-3,-2,-3),new Vec3d(7,3,7),STONE,"odd");
        Set<BlockPos> mask=new HashSet<>();
        for(BlockPos p:BlockPos.iterate(-2,-2,-2,2,-1,2))mask.add(p.toImmutable());
        var pieces=SourceMeshBuilder.replaceNear(List.of(odd),List.of(),mask,Vec3d.ZERO);
        check(pieces.stream().mapToDouble(n->n.size().x*n.size().y*n.size().z).sum()==97,"odd prism subtracts exactly 50 sampled cells across signed grid boundaries");
        for(int x=-3;x<4;x++)for(int y=-2;y<1;y++)for(int z=-3;z<4;z++) {
            int px=x,py=y,pz=z;
            long hits=pieces.stream().filter(n->sourceContains(n,px,py,pz)).count();
            check(hits==(mask.contains(new BlockPos(x,y,z))?0:1),"dense mask neither fills observed air nor removes unknown cells");
        }
        Set<BlockPos> all=new HashSet<>();for(BlockPos p:BlockPos.iterate(-3,-2,-3,3,0,3))all.add(p.toImmutable());
        check(SourceMeshBuilder.replaceNear(List.of(odd),List.of(),all,Vec3d.ZERO).isEmpty(),"fully sampled air removes the whole odd prism");
        // Fragmented observations cannot rely on a single rectangle or uniform octree parent.
        var cube=new SourceMeshBuilder.Node(new BlockPos(64,64,64),16,STONE,"cube");
        Set<BlockPos> checker=new HashSet<>();
        for(BlockPos p:BlockPos.iterate(64,64,64,79,79,79))if((p.getX()+p.getY()+p.getZ())%2==0)checker.add(p.toImmutable());
        var shell=SourceMeshBuilder.replaceNear(List.of(cube),List.of(),checker,Vec3d.ZERO);
        check(shell.size()==2048 && shell.stream().allMatch(n->n.size().equals(new Vec3d(1,1,1))),"fragmented mask retains exact unsampled octree cells");
        check(shell.stream().noneMatch(n->checker.contains(n.position())),"fragmented sampled air never reappears");
    }

    private static void evictionMovement() {
        Vec3d center=new Vec3d(.5,64,.5);var scheduler=new SourceDiscovery.Scheduler();scheduler.replan(center,2);
        Bounds own=SourceDiscovery.tile(center,0,0,0),evicted=SourceDiscovery.tile(center,1,0,0);
        while(true){var tile=scheduler.next(0);if(tile==null)break;scheduler.pending(tile);scheduler.completed(tile,true,0);}
        var sample=List.of(new SourceMeshBuilder.Node(new BlockPos(0,64,0),1,STONE),new SourceMeshBuilder.Node(new BlockPos(1,64,0),1,DIRT));
        Map<Bounds,List<SourceMeshBuilder.Node>> data=new HashMap<>();data.put(own,sample);data.put(evicted,sample);
        check(SourceDiscovery.retain(data,scheduler,center,2,0).size()==2 && !data.containsKey(evicted),"budget eviction drops only the farther tile");
        check(!SourceDiscovery.beyond(evicted,center,2),"evicted tile remains inside retention");
        scheduler.replan(center,2);check(scheduler.next(0)==null,"budget pressure cannot thrash stationary reads");
        check(evicted.equals(scheduler.next(SourceDiscovery.RETRY_ERROR_TICKS)),"evicted coverage is invalidated and eventually retries even while stationary");
        // Slow movement across several replans must clear the eviction backoff cumulatively.
        for(int step=4;step<=32;step+=4)scheduler.replan(center.add(step,0,0),2);
        check(evicted.equals(scheduler.next(1)),"moving toward an evicted retained tile promotes real rediscovery");
        scheduler.pending(evicted);scheduler.completed(evicted,true,1);data.put(evicted,sample);
        check(SourceDiscovery.retain(data,scheduler,center.add(32,0,0),4,1).size()==4,"freed budget retains rediscovered tile");
        check(!evicted.equals(scheduler.next(1)),"retained completion is not repeatedly reissued");
        var corner=SourceDiscovery.tile(center,5,0,5);
        check(!SourceDiscovery.beyond(corner,center,10),"view-box corners do not expire every stationary replan");
        check(SourceDiscovery.effectiveViewDistance(10,4)==4 && SourceDiscovery.effectiveViewDistance(4,10)==4,"discovery respects each player's effective server/client view distance");
        check(SourceDiscovery.meshHorizon(16)>=256,"server and renderer horizon no longer truncate the 16-chunk extent at 192/64 blocks");
        check(SourceDiscovery.horizontalRadius(32)==512 && SourceDiscovery.meshHorizon(32)>=512,"the full vanilla view-distance slider is honored");
        var full=SourceDiscovery.plan(center,32);
        for(long[] probe:new long[][]{{512,64,0},{-512,64,0},{0,64,512},{0,64,-512}})
            check(full.stream().anyMatch(b->b.contains(probe[0],probe[1],probe[2])),"32-chunk source-distance edges are scheduled");
    }
    public static void main(String[] args) {
        layeredGround();spatialAdmission();exactBatching();sparseNearReplacement();evictionMovement();
        System.out.println("DiscoveryFrameSelfTest: "+checks+" checks passed");
    }
}
