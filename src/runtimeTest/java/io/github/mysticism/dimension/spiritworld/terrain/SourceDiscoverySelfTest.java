package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.BlockPalette;
import io.github.mysticism.landmark.BlockPoint;
import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SourceLandmarks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Real render/view-distance source discovery regressions, no JUnit or Minecraft server boot.
 * Covers extent derivation, tile planning, scheduler lifecycle, observed-cell conversion and
 * far-field node merging/supersede. Registry bootstrapping is unavailable in a bare JVM, so
 * tile conversion is exercised through hand-built source tiles. */
public final class SourceDiscoverySelfTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void equal(Object expected,Object actual,String why){checks++;if(!expected.equals(actual))throw new AssertionError(why+": expected "+expected+" got "+actual);}

    private static final String STONE="minecraft:stone", DIRT="minecraft:dirt";

    private static SourceLandmarks.Cell cell(long x,long y,long z,String blockId) {
        return new SourceLandmarks.Cell(new BlockPoint(x,y,z),new BlockPalette.State(blockId,Map.of()),"minecraft:plains",true);
    }
    private static SourceLandmarks.Cell air(long x,long y,long z){return cell(x,y,z,"minecraft:air");}

    private static SourceMeshBuilder.Tile cube(String blockId) {
        return new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material(blockId,Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0,false,true);
    }

    private static BlockPos pos(long x,long y,long z){return new BlockPos(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z));}

    private static void extents() {
        check(SourceDiscovery.horizontalRadius(0)==SourceDiscovery.MIN_RADIUS,"no zero/negative view extent");
        equal(SourceDiscovery.TILE,SourceDiscovery.horizontalRadius(1),"one chunk still discovers a full tile");
        equal(160,SourceDiscovery.horizontalRadius(10),"default ten-chunk view discovers its full extent");
        equal(SourceDiscovery.MAX_RADIUS,SourceDiscovery.horizontalRadius(32),"extent is capped for huge view distances");
        equal(64,SourceDiscovery.verticalRadius(160),"vertical extent is bounded");
        equal(32,SourceDiscovery.verticalRadius(32),"small views keep at least one tile of height");
        equal(128,SourceDiscovery.meshHorizon(2),"mesh horizon never shrinks below the previous fixed locality");
        check(SourceDiscovery.meshHorizon(10)>128,"render-distance views widen the mesh horizon");
        equal(SourceDiscovery.MAX_HORIZON,SourceDiscovery.meshHorizon(32),"mesh horizon is bounded");
        check(SourceDiscovery.retentionRadius(10)>SourceDiscovery.horizontalRadius(10),"retention exceeds the discovery extent by one tile");
    }

    private static void planning() {
        Vec3d center=new Vec3d(100.5,64,200.5);
        List<Bounds> tiles=SourceDiscovery.plan(center,2);
        check(!tiles.isEmpty(),"planning produces tiles");
        equal(27,tiles.size(),"small views plan the full surrounding box");
        Vec3d previous=null;double previousDistance=-1;
        for(var tile:tiles) {
            equal(32L,tile.maxX()-tile.minX(),"every planned tile is exactly one bounded region volume wide");
            equal(32L,tile.maxY()-tile.minY(),"every planned tile is exactly one bounded region volume tall");
            equal(32L,tile.maxZ()-tile.minZ(),"every planned tile is exactly one bounded region volume deep");
            equal(0L,tile.minX()%32,"tiles align to a global grid for stable replans");
            equal(0L,tile.minY()%32,"tiles align to a global grid for stable replans");
            equal(0L,tile.minZ()%32,"tiles align to a global grid for stable replans");
            double distance=new Vec3d(tile.minX()+16,tile.minY()+16,tile.minZ()+16).squaredDistanceTo(center);
            check(distance>=previousDistance,"tiles are ordered center-out");
            check(previous==null || !previous.equals(new Vec3d(tile.minX(),tile.minY(),tile.minZ())),"no duplicate tiles");
            previous=new Vec3d(tile.minX(),tile.minY(),tile.minZ());previousDistance=distance;
        }
        Bounds containing=null;for(var tile:tiles)if(tile.contains((long)Math.floor(center.x),(long)Math.floor(center.y),(long)Math.floor(center.z)))containing=tile;
        check(containing!=null,"the player's own tile is planned");
        double minimum=new Vec3d(tiles.getFirst().minX()+16,tiles.getFirst().minY()+16,tiles.getFirst().minZ()+16).squaredDistanceTo(center);
        double own=new Vec3d(containing.minX()+16,containing.minY()+16,containing.minZ()+16).squaredDistanceTo(center);
        check(own<=minimum,"the containing tile is among the nearest planned tiles");
        // The whole render-distance box is covered at cell granularity, including corners.
        Vec3d large=new Vec3d(0.5,128,0.5);
        List<Bounds> wide=SourceDiscovery.plan(large,10);
        int horizontal=SourceDiscovery.horizontalRadius(10),vertical=SourceDiscovery.verticalRadius(horizontal);
        for(long[] probe:new long[][]{{-horizontal,128-vertical,-horizontal},{horizontal-1,128+vertical-1,horizontal-1},{-horizontal,128,horizontal-1},{horizontal-1,128-vertical,0}}) {
            boolean found=false;for(var tile:wide)if(tile.contains(probe[0],probe[1],probe[2])){found=true;break;}
            check(found,"render-distance corners are covered ("+probe[0]+","+probe[1]+","+probe[2]+")");
        }
        equal(SourceDiscovery.plan(large,16).size(),bruteForceTileCount(large,16),"no truncation: every tile intersecting the view box is scheduled");
        check(SourceDiscovery.plan(large,16).size()>768,"a 16-chunk view schedules beyond the discarded plan-tail cap");
        equal(wide,SourceDiscovery.plan(large,10),"planning is deterministic");
        equal(tiles.size(),SourceDiscovery.plan(new Vec3d(center.x,center.y+64,center.z),2).size(),"vertical recentering replans the same shape");
    }

    private static void extentFilter() {
        Vec3d center=new Vec3d(0,64,0);
        check(!SourceDiscovery.beyond(new Bounds(0,64,0,32,96,32),center,2),"the containing tile is retained");
        check(SourceDiscovery.beyond(new Bounds(4096,64,4096,4128,96,4128),center,2),"distant tiles expire from retention");
    }

    private static int bruteForceTileCount(Vec3d center,int viewDistanceChunks) {
        int horizontal=SourceDiscovery.horizontalRadius(viewDistanceChunks),vertical=SourceDiscovery.verticalRadius(horizontal);
        long fx=(long)Math.floor(center.x),fy=(long)Math.floor(center.y),fz=(long)Math.floor(center.z);
        int count=0;
        for(long tx=Math.floorDiv(fx-horizontal-32,32);tx<=Math.floorDiv(fx+horizontal+32,32);tx++)
            for(long ty=Math.floorDiv(fy-vertical-32,32);ty<=Math.floorDiv(fy+vertical+32,32);ty++)
                for(long tz=Math.floorDiv(fz-horizontal-32,32);tz<=Math.floorDiv(fz+horizontal+32,32);tz++) {
                    Bounds b=new Bounds(tx*32,ty*32,tz*32,tx*32+32,ty*32+32,tz*32+32);
                    if(b.minX()<=fx+horizontal&&b.maxX()-1>=fx-horizontal&&b.minY()<=fy+vertical&&b.maxY()-1>=fy-vertical
                            &&b.minZ()<=fz+horizontal&&b.maxZ()-1>=fz-horizontal)count++;
                }
        return count;
    }

    private static void eventualFullCoverage() {
        // A 16-chunk view schedules far more tiles than the old cap; the bounded issue rate must
        // eventually issue and cover every one of them.
        Vec3d center=new Vec3d(7.5,128,-3.25);
        var all=new SourceDiscovery.Scheduler();
        all.replan(center,16);
        int planned=SourceDiscovery.plan(center,16).size();
        int issued=0;Bounds tile;
        while(issued<=planned && (tile=all.next(0))!=null){all.pending(tile);all.completed(tile,true,0);issued++;}
        equal(planned,issued,"eventual full coverage: every scheduled tile is issued");
        check(all.next(0)==null,"full coverage leaves nothing queued");
        // Axis-aligned source points at the extent edge are included, never omitted.
        int horizontal=SourceDiscovery.horizontalRadius(16);
        boolean edgeCovered=false;for(Bounds b:SourceDiscovery.plan(center,16))if(b.contains((long)Math.floor(center.x)+horizontal-1,128,128))edgeCovered=true;
        check(edgeCovered,"axis-aligned extent-edge source points are scheduled");
    }

    private static void localAdmission() {
        // Near samples keep the exact unchanged allowance; the far lane admits distant discovered
        // terrain even when near samples would otherwise exhaust the whole mesh budget first.
        var deep=new SourceDiscovery.LocalAdmission(false,640);
        for(int n=0;n<640;n++)check(deep.admit(0),"near cells keep the unchanged deep allowance");
        check(!deep.admit(0),"near lane exhausts exactly at the previous limit");
        check(deep.admit(33*33.0),"far discovery is admitted after near exhaustion");
        for(int n=1;n<SourceDiscovery.LocalAdmission.FAR_ADMISSION;n++)check(deep.admit(40*40.0),"the far lane admits its bounded budget");
        check(!deep.admit(40*40.0),"far admission is bounded");
        check(deep.exhausted(),"both lanes spent stops scanning");
        check(!deep.admit(0) && !deep.admit(40*40.0),"spent lanes stay spent");
        var shallow=new SourceDiscovery.LocalAdmission(true,1024);
        for(int n=0;n<1024;n++)check(shallow.admit(0),"near cells keep the unchanged shallow allowance");
        check(!shallow.admit(0),"shallow near lane exhausts exactly at the previous limit");
        check(shallow.admit(100*100.0),"shallow far lane is separate from near samples");
        // Lane classification is by distance only: near cells never steal the far budget.
        var edge=new SourceDiscovery.LocalAdmission(false,1);
        check(edge.admit(SourceDiscovery.NEAR_EXACT_RADIUS*SourceDiscovery.NEAR_EXACT_RADIUS),"at the exact near radius the cell is near");
        check(!edge.admit(SourceDiscovery.NEAR_EXACT_RADIUS*SourceDiscovery.NEAR_EXACT_RADIUS),"an exhausted near lane rejects further near cells");
        check(edge.admit(SourceDiscovery.NEAR_EXACT_RADIUS*SourceDiscovery.NEAR_EXACT_RADIUS+1),"beyond the near radius the far lane admits");
    }

    private static void scheduler() {
        var scheduler=new SourceDiscovery.Scheduler();
        Vec3d center=new Vec3d(100.5,64,200.5);
        var expired=scheduler.replan(center,2);
        check(expired.isEmpty(),"a fresh plan expires nothing");
        Set<Bounds> seen=new HashSet<>();
        Bounds first=scheduler.next(0);check(first!=null,"the nearest tile is issued first");
        scheduler.pending(first);seen.add(first);
        var second=scheduler.next(0);check(second!=null && !second.equals(first),"pending tiles are not re-issued");
        scheduler.completed(first,true,10);
        check(scheduler.next(10).equals(second),"pending tile keeps its queue position across completions");
        int planned=SourceDiscovery.plan(center,2).size();
        while(seen.size()<planned){var tile=scheduler.next(10);if(tile==null)break;scheduler.pending(tile);seen.add(tile);scheduler.completed(tile,true,10);}
        check(seen.size()==planned,"every planned tile is issued exactly once");
        check(scheduler.next(10)==null,"completed coverage is never re-issued");
        // Incomplete reads keep their data, defer to other queued tiles, then wait out a shorter cooldown.
        var partial=new SourceDiscovery.Scheduler();partial.replan(center,1);
        var incomplete=partial.next(0);partial.pending(incomplete);partial.completed(incomplete,false,0);
        var deferred=partial.next(0);check(deferred!=null && !deferred.equals(incomplete),"incomplete reads defer to the next queued tile");
        partial.pending(deferred);partial.completed(deferred,true,0);
        while(true){var tile=partial.next(0);if(tile==null)break;partial.pending(tile);partial.completed(tile,true,0);}
        check(partial.next(0)==null,"incomplete reads wait out their retry cooldown");
        check(partial.next(SourceDiscovery.RETRY_INCOMPLETE_TICKS-1)==null,"retry cooldown covers the full wait");
        check(incomplete.equals(partial.next(SourceDiscovery.RETRY_INCOMPLETE_TICKS)),"incomplete reads retry after the cooldown");
        // Failures back off longer than incomplete reads.
        var failed=new SourceDiscovery.Scheduler();failed.replan(center,1);
        var broken=failed.next(0);failed.pending(broken);failed.failed(broken,0);
        while(true){var tile=failed.next(0);if(tile==null)break;failed.pending(tile);failed.completed(tile,true,0);}
        check(failed.next(0)==null,"failed reads back off");
        check(failed.next(SourceDiscovery.RETRY_INCOMPLETE_TICKS)==null,"failures back off longer than incomplete reads");
        check(broken.equals(failed.next(SourceDiscovery.RETRY_ERROR_TICKS)),"failed reads eventually retry");
        // Replanning retains covered tiles and expires only those beyond the extent.
        var moved=new SourceDiscovery.Scheduler();moved.replan(new Vec3d(0,64,0),1);
        var original=moved.next(0);check(original!=null,"a moved plan issues tiles");
        while(true){var next=moved.next(0);if(next==null)break;moved.pending(next);moved.completed(next,true,0);}
        var dropped=moved.replan(new Vec3d(0,64,0).add(4112,16,4112),1);
        check(dropped.size()>0,"recentering beyond the extent expires old coverage");
        var reissued=moved.next(0);check(reissued!=null && !reissued.equals(original),"expired coverage is re-issued around the new center");
        check(reissued.contains(4112L,80L,4112L),"the new center's own tile is re-issued first");
    }

    private static void observedNodes() {
        Vec3d tileCenter=new Vec3d(16,80,16);
        // A uniform unowned 16^3 stone body (octree-aligned) plus a surface variation; air never reaches conversion.
        Map<BlockPos,SourceMeshBuilder.Tile> observed=new HashMap<>();
        for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++)observed.put(pos(192+x,64+y,192+z),cube(STONE));
        observed.put(pos(192,80,192),cube(DIRT));
        var nodes=SourceDiscovery.farNodes(observed,tileCenter);
        check(!nodes.isEmpty(),"observed solids become far-field nodes");
        long merged=nodes.stream().filter(n->n.side()==16).count();
        equal(1L,merged,"uniform unowned source merges into one octree node");
        equal(1L,nodes.stream().filter(n->n.tile().material().blockId().equals(DIRT)).count(),"varied surface material stays its own node");
        check(nodes.stream().allMatch(n->n.position().getX()>=192&&n.position().getX()<208),"nodes stay inside the observed region");
        // Near-field compaction keeps strict per-owner provenance: unowned uniform tiles never merge there.
        var strict=SourceMeshBuilder.compact(observed,tileCenter,Map.of());
        check(strict.size()>nodes.size(),"near-field compaction never merges unowned tiles");
        var permissive=SourceMeshBuilder.compact(observed,tileCenter,Map.of(),true);
        equal(nodes.size(),permissive.size(),"discovery conversion matches the merge-unowned compaction");
        // Far nodes are ordered center-out so the bounded mesh budget spends cells nearest first.
        Vec3d farCorner=new Vec3d(192,64,192);
        for(int n=1;n<nodes.size();n++) {
            double a=SourceDiscoverySelfTest.distance(nodes.get(n-1),farCorner),b=SourceDiscoverySelfTest.distance(nodes.get(n),farCorner);
            check(a<=b,"far nodes sort nearest-first for bounded mesh budgets");
        }
        // Air/duplicate cell filtering is a pure pre-step of conversion.
        var cells=List.of(cell(0,0,0,STONE),cell(0,0,0,STONE),air(0,1,0),air(1,0,0));
        var solids=blocksAvailable()?SourceDiscovery.observedSolids(cells):null;
        if(solids!=null) {
            equal(1L,solids.size(),"duplicate observations collapse and air never converts");
        } else {
            System.out.println("SourceDiscoverySelfTest: block registry unavailable in bare JVM; cell->tile conversion checked via hand-built tiles");
        }
        check(SourceDiscovery.farNodes(Map.of(),tileCenter).isEmpty(),"air-only observations produce no nodes");
    }

    private static double distance(SourceMeshBuilder.Node n,Vec3d center) {
        BlockPos at=n.position();
        return SourceMeshBuilder.distanceSquared(new Box(at.getX(),at.getY(),at.getZ(),at.getX()+n.side(),at.getY()+n.side(),at.getZ()+n.side()),center);
    }

    private static boolean blocksAvailable() {
        try {return SourceMeshBuilder.stored(new BlockPalette.State(STONE,Map.of()),pos(0,0,0))!=null;}
        catch(Throwable broken){return false;}
    }

    private static void farFieldSupersede() {
        // Discovered far nodes stand beside persisted geometry and near tiles replace both in the sampled cut.
        Vec3d tileCenter=new Vec3d(160,80,160);
        Map<BlockPos,SourceMeshBuilder.Tile> observed=new HashMap<>();
        for(int x=0;x<32;x++)for(int y=0;y<32;y++)for(int z=0;z<32;z++)observed.put(pos(144+x,64+y,144+z),cube(x<16&&y<16&&z<16?STONE:DIRT));
        var discovered=SourceDiscovery.farNodes(observed,tileCenter);
        check(!discovered.isEmpty(),"far discovery produces nodes");
        Bounds cut=new Bounds(144,64,144,152,72,152); // an actually sampled near window
        Map<BlockPos,SourceMeshBuilder.Tile> nearTiles=new HashMap<>();
        for(int x=144;x<152;x++)for(int y=64;y<72;y++)for(int z=144;z<152;z++)nearTiles.put(pos(x,y,z),cube(STONE));
        var near=SourceMeshBuilder.compact(nearTiles,new Vec3d(148,68,148));
        var combined=SourceMeshBuilder.replaceNear(new ArrayList<>(discovered),near,cut,new Vec3d(148,68,148));
        check(combined.containsAll(near),"near nodes supersede far discovery inside the sampled window");
        for(var node:near)for(var far:combined) {
            if(node.equals(far))continue;
            var a=new Bounds(node.position().getX(),node.position().getY(),node.position().getZ(),node.position().getX()+node.side(),node.position().getY()+node.side(),node.position().getZ()+node.side());
            var b=new Bounds(far.position().getX(),far.position().getY(),far.position().getZ(),far.position().getX()+far.side(),far.position().getY()+far.side(),far.position().getZ()+far.side());
            check(!a.intersects(b),"no far node overlaps the near sampled window after replacement");
        }
        var outside=combined.stream().filter(n->!near.contains(n)).toList();
        check(!outside.isEmpty(),"far discovery outside the sampled window is retained");
    }

    public static void main(String[] args) {
        extents();planning();extentFilter();scheduler();eventualFullCoverage();localAdmission();observedNodes();farFieldSupersede();
        System.out.println("SourceDiscoverySelfTest: "+checks+" checks passed");
    }
}
