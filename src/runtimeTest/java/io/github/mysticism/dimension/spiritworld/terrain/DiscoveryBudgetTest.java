package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;

/** Discovery retention budget regressions: bounded eviction work, exact-support protection,
 *  last-resort floor, deterministic air-first ordering and render-distance coverage that is
 *  never silently shrunk. Pure and deterministic: no server, no registry, no game boot. */
public final class DiscoveryBudgetTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}

    private static final Vec3d FOCUS=new Vec3d(0.5,64.5,0.5);
    /** Discovered sky samples are AIR; discovered ground samples are solid. */
    private static final Predicate<BlockPos> AIR=p->p.getY()>64;

    private static Set<BlockPos> discoveredSurface(int radius,int solidDepth,int airHeight) {
        Set<BlockPos> tiles=new HashSet<>();
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            for(int y=1;y<=solidDepth;y++)tiles.add(new BlockPos(x,64-y,z));
            for(int y=1;y<=airHeight;y++)tiles.add(new BlockPos(x,64+y,z));
        }
        return tiles;
    }

    /** Apply the budget like the service does: bounded passes until convergence. */
    private static Set<BlockPos> runRetention(Set<BlockPos> discovered,Vec3d focus,Predicate<BlockPos> air,int maxPasses) {
        Set<BlockPos> retained=new HashSet<>(discovered);
        int passes=0;long evicted=0;
        for(int pass=0;pass<maxPasses;pass++) {
            List<BlockPos> victims=DiscoveryBudget.evictionPlan(retained.size(),retained,focus,air);
            if(victims.isEmpty())break;
            check(victims.size()<=DiscoveryBudget.MAX_EVICTED_PER_PASS,
                    "Eviction work must stay bounded per pass, got "+victims.size());
            retained.removeAll(victims);passes++;evicted+=victims.size();
        }
        check(DiscoveryBudget.evictionPlan(retained.size(),retained,focus,air).isEmpty(),
                "Retention must converge under the cap (passes="+passes+" evicted="+evicted
                        +" discovered="+discovered.size()+" retained="+retained.size()+")");
        return retained;
    }

    private static void withinCapEvictsNothing() {
        Set<BlockPos> small=discoveredSurface(32,1,2);
        check(DiscoveryBudget.evictionPlan(small.size(),small,FOCUS,AIR).isEmpty(),
                "A window within the cap must never evict discovered samples");
        check(DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES,small,FOCUS,AIR).isEmpty(),
                "Exactly at the cap is still within budget");
        check(DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+1,Set.of(),FOCUS,AIR).isEmpty(),
                "No positions means no victims");
    }

    private static void renderDistanceCoveragePreserved() {
        // A long shallow walk inside the 128-block render distance: one solid surface layer plus
        // two discovered sky samples per column out to radius 90.
        int radius=90;
        Set<BlockPos> discovered=discoveredSurface(radius,1,2);
        long discoveredSolids=discovered.stream().filter(p->!AIR.test(p)).count();
        Set<BlockPos> retained=runRetention(discovered,FOCUS,AIR,600);
        check(retained.size()<=DiscoveryBudget.MAX_TILES,"Retention cap must still be enforced");
        // Redundant far AIR absorbs the pressure first: the only air left is inside the exact radius.
        long retainedFarAir=retained.stream().filter(AIR)
                .filter(p->p.getSquaredDistance(FOCUS)>24.0*24.0).count();
        check(retainedFarAir==0,"Far AIR must be evicted before any solid sample");
        // Solids are evicted farthest-first only, so coverage keeps spanning the discovery footprint
        // instead of the legacy all-at-once 32-block silent cut.
        long retainedSolids=retained.stream().filter(p->!AIR.test(p)).count();
        check(retainedSolids>=discoveredSolids-64*64-1024,
                "Farthest-first eviction must keep nearly all discovered solids ("+retainedSolids+"/"+discoveredSolids+")");
        double maxSolid=retained.stream().filter(p->!AIR.test(p))
                .mapToDouble(p->Math.sqrt(p.getSquaredDistance(FOCUS))).max().orElse(0);
        check(maxSolid>=radius-8,"Retained solid coverage "+maxSolid+" must span the discovered render footprint");
        check(maxSolid>32,"Retention must not silently shrink visibility to the legacy 32-block radius");
    }

    private static void exactSupportRadiusProtected() {
        Set<BlockPos> discovered=discoveredSurface(90,1,2);
        Set<BlockPos> retained=runRetention(discovered,FOCUS,AIR,600);
        for(BlockPos p:discovered)
            if(p.getSquaredDistance(FOCUS)<=24.0*24.0)
                check(retained.contains(p),"Exact-support sample "+p+" must never be evicted while farther samples exist");
    }

    private static void lastResortRespectsMinimumFloor() {
        // Worst case: everything discovered sits inside the exact radius and the window is over cap.
        Set<BlockPos> discovered=new HashSet<>();
        for(int x=-24;x<=24;x++)for(int y=-24;y<=24;y++)for(int z=-24;z<=24;z++) {
            BlockPos p=new BlockPos(x,64+y,z);
            if(p.getSquaredDistance(FOCUS)<=24.0*24.0)discovered.add(p);
        }
        check(discovered.size()>DiscoveryBudget.MAX_TILES,"Fixture must exceed the cap");
        Set<BlockPos> retained=runRetention(discovered,FOCUS,p->false,600);
        check(retained.size()<=DiscoveryBudget.MAX_TILES,"Cap enforced even with no far victims");
        for(BlockPos p:discovered)
            if(p.getSquaredDistance(FOCUS)<=8.0*8.0)
                check(retained.contains(p),"Minimum support floor sample "+p+" must never be evicted");
        // Farthest-first holds even in the fallback: no retained sample is farther than an evicted one.
        double retainedMax=retained.stream().mapToDouble(p->p.getSquaredDistance(FOCUS)).max().orElse(0);
        for(BlockPos p:discovered)if(!retained.contains(p))
            check(p.getSquaredDistance(FOCUS)>=retainedMax,"Eviction must be farthest-first");
    }

    private static void airFirstAndDeterministic() {
        Vec3d focus=new Vec3d(0.5,0.5,0.5);
        BlockPos farAir=new BlockPos(40,10,0),farAirTie=new BlockPos(0,10,40),farSolid=new BlockPos(0,-10,40),nearSolid=new BlockPos(1,0,1);
        check(farAir.getSquaredDistance(focus)==farAirTie.getSquaredDistance(focus),"Fixture tie required");
        check(farAirTie.getSquaredDistance(focus)==farSolid.getSquaredDistance(focus),"Fixture tie required");
        Predicate<BlockPos> air=p->p.equals(farAir)||p.equals(farAirTie);
        Set<BlockPos> tiles=Set.of(farAir,farAirTie,farSolid,nearSolid);
        List<BlockPos> first=DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+1,tiles,focus,air);
        check(first.size()==1,"One unit of excess evicts exactly one victim");
        check(air.test(first.getFirst()),"Far AIR must be evicted before an equally far solid");
        List<BlockPos> second=DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+1,tiles,focus,air);
        check(first.equals(second),"Eviction plan must be deterministic");
        List<BlockPos> allAir=DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+2,tiles,focus,air);
        check(allAir.size()==2 && allAir.stream().allMatch(air),"Equal-distance AIR ties fill the plan before any solid");
        check(allAir.stream().mapToLong(BlockPos::asLong).boxed().toList().equals(allAir.stream().mapToLong(BlockPos::asLong).sorted().boxed().toList()),
                "Equal-distance AIR ties break by position, deterministically");
        List<BlockPos> solidToo=DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+3,tiles,focus,air);
        check(solidToo.contains(farSolid) && solidToo.size()==3 && air.test(solidToo.getFirst()) && air.test(solidToo.get(1)),
                "Far solid is evicted after all far AIR, before near samples");
    }

    private static void airEvictionKeepsRenderGeometry() {
        Vec3d center=new Vec3d(0,64,0);
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();
        SourceMeshBuilder.Tile stone=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:stone",Map.of()),
                List.of(new Box(0,0,0,1,1,1)),0xffffff,0,false,true);
        SourceMeshBuilder.Tile air=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:air",Map.of()),
                List.of(),0xffffff,0,true,false);
        for(int x=-40;x<=40;x++)for(int z=-40;z<=40;z++){tiles.put(new BlockPos(x,63,z),stone);tiles.put(new BlockPos(x,64,z),air);}
        List<SourceMeshBuilder.Node> compactBefore=SourceMeshBuilder.compact(tiles,center);
        check(!compactBefore.isEmpty(),"Fixture must compact to render nodes");
        // Simulate cap pressure: only AIR victims are planned and removed.
        List<BlockPos> victims=DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+DiscoveryBudget.MAX_EVICTED_PER_PASS,
                tiles.keySet(),center,p->tiles.get(p).air());
        check(victims.size()==DiscoveryBudget.MAX_EVICTED_PER_PASS,"Bounded eviction batch expected");
        check(victims.stream().allMatch(p->tiles.get(p).air()),"Only redundant AIR may be evicted");
        // The real merge path includes old persisted SOLID at positions now observed as AIR.
        List<SourceMeshBuilder.Node> persisted=new ArrayList<>();
        for(BlockPos p:victims)persisted.add(new SourceMeshBuilder.Node(p,1,stone,"old-owner"));
        BlockPos untouched=new BlockPos(60,64,60);persisted.add(new SourceMeshBuilder.Node(untouched,1,stone,"old-owner"));
        List<SourceMeshBuilder.Node> before=SourceMeshBuilder.replaceNear(persisted,compactBefore,tiles.keySet(),center);
        NegativeCoverage negative=new NegativeCoverage();
        for(BlockPos p:victims){negative.add(p);tiles.remove(p);}
        check(SourceMeshBuilder.compact(tiles,center).equals(compactBefore),
                "Air retention eviction must not change a single rendered/compacted node");
        List<SourceMeshBuilder.Node> after=SourceMeshBuilder.replaceNear(persisted,SourceMeshBuilder.compact(tiles,center),tiles.keySet(),negative,center);
        check(after.equals(before),"AIR eviction must not resurrect persisted SOLID through replaceNear");
        for(BlockPos p:victims)check(after.stream().noneMatch(n->covers(n,p)),"Obsolete solid/collision resurrected at "+p);
        check(after.stream().anyMatch(n->n.position().equals(untouched)),"Unobserved persisted stone must survive");
        // A new cursor / empty tile cache must still be suppressed by session-local negative coverage.
        List<SourceMeshBuilder.Node> restarted=SourceMeshBuilder.replaceNear(persisted,List.of(),Set.of(),negative,center);
        check(restarted.size()==1 && restarted.getFirst().position().equals(untouched),"Stream restart forgot negative coverage");
        BlockPos fresh=victims.getFirst();negative.remove(fresh);tiles.put(fresh,stone);
        List<SourceMeshBuilder.Node> changed=SourceMeshBuilder.replaceNear(persisted,SourceMeshBuilder.compact(tiles,center),tiles.keySet(),negative,center);
        check(changed.stream().filter(n->covers(n,fresh)).count()==1,"A fresh solid observation must supersede AIR exactly once");
        TerrainMeshFrame frame=published(after,1024,true);
        check(frame.cells().stream().noneMatch(c->victims.stream().anyMatch(p->sourceContains(c,p))),
                "Published visible/collidable frame restored an obsolete AIR cell");
        MeshCollision.Index collision=new MeshCollision.Index(frame);
        for(BlockPos p:victims)check(collision.clearRay(new Vec3d(p.getX()+.5,p.getY()+1.5,p.getZ()+.5),
                new Vec3d(p.getX()+.5,p.getY()+.1,p.getZ()+.5)),"Evicted AIR restored an actual SAT collider");
    }

    /** Simulated service schedule: each near-patch commit merges up to 4096 staged samples, runs one
     *  commit-time pass, then per-tick bounded passes for EVERY retained window until the next commit
     *  (near staging ingests 4096 cells at 128 cells/tick, i.e. ~32 ticks). Sustained commits must stay
     *  under the cap and never touch the exact support radius. */
    private static void sustainedCommitsStayBounded() {
        Set<BlockPos> retained=new HashSet<>();
        Set<BlockPos> protectedNear=samplesWithinExactRadius();
        for(int commit=0;commit<24;commit++) {
            int base=commit*16-8; // disjoint 16-block patch marching down +x
            Set<BlockPos> patch=new HashSet<>();
            for(int x=base;x<base+16;x++)for(int z=-8;z<8;z++) {
                for(int y=56;y<65;y++)patch.add(new BlockPos(x,y,z));
                for(int y=65;y<72;y++)patch.add(new BlockPos(x,y,z));
            }
            check(patch.size()==4096,"Fixture must model a full 16-block staged commit");
            retained.addAll(patch);
            // Commit-time pass, then ~32 per-tick passes before the next commit.
            List<BlockPos> victims=DiscoveryBudget.evictionPlan(retained.size(),retained,FOCUS,AIR);
            check(victims.size()<=DiscoveryBudget.MAX_EVICTED_PER_PASS,"Commit pass must stay bounded");
            retained.removeAll(victims);
            for(int t=0;t<32 && !DiscoveryBudget.evictionPlan(retained.size(),retained,FOCUS,AIR).isEmpty();t++) {
                victims=DiscoveryBudget.evictionPlan(retained.size(),retained,FOCUS,AIR);
                check(victims.size()<=DiscoveryBudget.MAX_EVICTED_PER_PASS,"Tick pass must stay bounded");
                retained.removeAll(victims);
            }
            check(retained.size()<=DiscoveryBudget.MAX_TILES,
                    "Sustained commits must converge under the cap, commit "+commit+" retained "+retained.size());
            for(BlockPos p:protectedNear)check(retained.contains(p),"Exact-support sample "+p+" evicted during sustained commits");
        }
    }
    private static Set<BlockPos> samplesWithinExactRadius() {
        Set<BlockPos> samples=new HashSet<>();
        for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)for(int y=56;y<72;y++)samples.add(new BlockPos(x,y,z));
        return samples;
    }

    private static void sampledCoverageReplacesOnlyProvenCells() {
        Vec3d center=new Vec3d(40,64,0);
        SourceMeshBuilder.Tile stone=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:stone",Map.of()),
                List.of(new Box(0,0,0,1,1,1)),0xffffff,0,false,true);
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();
        Function<int[],BlockPos> at=a->new BlockPos(a[0],a[1],a[2]);
        // Two disjoint 16-block sampled patches at x=-8..7 and x=72..87 with a wide unsampled gap between.
        for(int[] patch:new int[][]{{-8},{72}})for(int x=patch[0];x<patch[0]+16;x++)for(int z=-8;z<8;z++) {
            tiles.put(at.apply(new int[]{x,63,z}),stone);
            tiles.put(at.apply(new int[]{x,64,z}),stone);
        }
        List<SourceMeshBuilder.Node> near=SourceMeshBuilder.compact(tiles,center);
        check(!near.isEmpty(),"Fixture must compact sampled patches");
        List<SourceMeshBuilder.Node> base=new ArrayList<>();
        List<BlockPos> gapPositions=new ArrayList<>();
        for(int x=24;x<=55;x++)for(int z=-8;z<8;z++)gapPositions.add(new BlockPos(x,63,z)); // inside the bbox, never sampled
        for(BlockPos p:gapPositions)base.add(new SourceMeshBuilder.Node(p,1,stone,""));
        base.add(new SourceMeshBuilder.Node(new BlockPos(0,63,0),1,stone,""));        // under patch A: proven sampled
        base.add(new SourceMeshBuilder.Node(new BlockPos(4,56,0),8,stone,""));        // straddles patch A's x edge and its top layer
        base.add(new SourceMeshBuilder.Node(new BlockPos(-32,56,0),8,stone,""));      // outside every patch
        Set<BlockPos> sampled=tiles.keySet();
        List<SourceMeshBuilder.Node> result=SourceMeshBuilder.replaceNear(base,near,sampled,center);
        Set<String> keys=new HashSet<>();
        for(SourceMeshBuilder.Node n:result)keys.add(n.position()+"/"+n.side());
        // Gap geometry inside the overall sampled extent but never sampled itself must survive intact.
        for(BlockPos p:gapPositions)check(keys.contains(p+"/1"),"Unsampled gap node "+p+" was erased by the sampled bbox");
        long sampledCell=result.stream().filter(n->n.position().equals(new BlockPos(0,63,0))).count();
        check(sampledCell==1,"Proven sampled cell must be replaced by exactly one near-patch node, got "+sampledCell);
        check(keys.contains(new BlockPos(-32,56,0)+"/8"),"Fully unsampled coarse node must survive");
        check(!keys.contains(new BlockPos(4,56,0)+"/8"),"Straddling coarse node must be refined, not kept wholesale");
        check(keys.contains(new BlockPos(4,56,0)+"/4"),"Unsampled bottom half of a straddling node must survive");
        check(keys.contains(new BlockPos(8,56,0)+"/4"),"Unsampled beyond-edge half of a straddling node must survive");
        check(keys.contains(new BlockPos(8,60,0)+"/4"),"Unsampled upper beyond-edge quarter must survive");
        check(!keys.contains(new BlockPos(4,60,4)+"/4"),"Mixed sampled split child must be refined, not kept wholesale");
        check(keys.contains(new BlockPos(4,60,4)+"/2") || keys.contains(new BlockPos(4,60,4)+"/1"),"Refinement must keep the unsampled remainder of a mixed child");
    }

    private static boolean covers(SourceMeshBuilder.Node n,BlockPos p) {
        BlockPos at=n.position();return p.getX()>=at.getX() && p.getX()<at.getX()+n.side()
                && p.getY()>=at.getY() && p.getY()<at.getY()+n.side() && p.getZ()>=at.getZ() && p.getZ()<at.getZ()+n.side();
    }
    private static boolean sourceContains(TerrainMeshFrame.Cell c,BlockPos p) {
        Vec3d min=c.sourceMin(),size=c.size();return p.getX()>=min.x && p.getX()<min.x+size.x
                && p.getY()>=min.y && p.getY()<min.y+size.y && p.getZ()>=min.z && p.getZ()<min.z+size.z;
    }
    private static SourceMeshBuilder.Tile solid(String id) {
        return new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material(id,Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0,false,true);
    }
    /** The exact production append stage and real immutable frame, not retained-coordinate evidence. */
    private static TerrainMeshFrame published(List<SourceMeshBuilder.Node> nodes,int limit,boolean shallow) {
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();List<TerrainMeshFrame.Cell> cells=new ArrayList<>();
        Vec3d x=shallow?new Vec3d(1,0,0):new Vec3d(.9,0,.2),y=new Vec3d(0,1,0),z=shallow?new Vec3d(0,0,1):new Vec3d(-.2,0,.9);
        MeshPublication.append(nodes,"minecraft:overworld",FOCUS,FOCUS,x,y,z,FOCUS,1,limit,materials,new HashMap<>(),cells,new HashSet<>());
        if(shallow)cells=MeshStitcher.stitch(cells,materials,"",true,FOCUS);
        return new TerrainMeshFrame(1,shallow,"minecraft:overworld",FOCUS,FOCUS,materials,cells);
    }
    private static void publishedSurfaceSpansRenderDistance() {
        Set<BlockPos> retained=runRetention(discoveredSurface(90,1,2),FOCUS,AIR,600);
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();SourceMeshBuilder.Tile stone=solid("minecraft:stone");
        for(BlockPos p:retained)if(!AIR.test(p))tiles.put(p,stone);
        List<SourceMeshBuilder.Node> nodes=SourceMeshBuilder.compact(tiles,FOCUS);
        check(nodes.size()>1024,"One-layer/unknown-owner fixture must exceed the old nearest-only mesh budget");
        for(boolean shallow:new boolean[]{true,false}) {
            int limit=shallow?1024:640;TerrainMeshFrame frame=published(nodes,limit,shallow);
            check(frame.cells().size()<=limit,"Published frame exceeded window/wire budget");
            check(frame.cells().size()<nodes.size(),"Far one-layer surface must coalesce, not require an unbounded frame");
            for(BlockPos p:tiles.keySet())check(frame.cells().stream().anyMatch(c->sourceContains(c,p)),
                    "Published "+(shallow?"shallow":"deep")+" surface lost discovered solid "+p);
            MeshCollision.Index collision=new MeshCollision.Index(frame);
            for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}) {
                BlockPos farPoint=new BlockPos(70*sx,63,70*sz);
                check(tiles.containsKey(farPoint),"Retention fixture must contain the distant quadrant witness");
                var distant=frame.cells().stream().filter(c->sourceContains(c,farPoint)).findFirst();
                check(distant.isPresent(),"Published frame lost distant quadrant "+sx+","+sz);
                var c=distant.orElseThrow();double u=(farPoint.getX()+.5-c.sourceMin().x)/c.size().x,w=(farPoint.getZ()+.5-c.sourceMin().z)/c.size().z;
                check(!collision.clearRay(c.point(u,1.5,w),c.point(u,-.5,w)),
                        "Published distant terrain has no actual affine SAT collision");
            }
            for(var cell:frame.cells()) {
                check(cell.collision().equals(List.of(SourceMeshBuilder.UNIT)),"LOD changed exact solid collision");
                check(cell.bounds().equals(cell.bounds(cell.collision().getFirst())),"Visible affine cell differs from collision envelope");
            }
        }
    }
    private static void publicationDoesNotBridgeHolesOrOwners() {
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();Map<BlockPos,String> owners=new HashMap<>();
        SourceMeshBuilder.Tile stone=solid("minecraft:stone");
        for(int x=32;x<=90;x++)for(int z=-12;z<=12;z++)if(x!=60 && z!=0) {
            BlockPos p=new BlockPos(x,63,z);tiles.put(p,stone);owners.put(p,x<60?"left":"right");
        }
        TerrainMeshFrame frame=published(SourceMeshBuilder.compact(tiles,FOCUS,owners),1024,true);
        for(var c:frame.cells())for(BlockPos p:BlockPos.iterate(BlockPos.ofFloored(c.sourceMin()),BlockPos.ofFloored(c.sourceMin().add(c.size()).add(-1,-1,-1)))) {
            check(tiles.containsKey(p),"Coalescing invented a solid over AIR/unknown "+p);
            check(c.landmarkId().equals(owners.get(p)),"Coalescing crossed source octree ownership");
        }
        for(BlockPos p:tiles.keySet())check(frame.cells().stream().anyMatch(c->sourceContains(c,p)),"Exact rectangular publication dropped "+p);
        // Partial/non-cube geometry must never be stretched into a whole solid rectangle.
        SourceMeshBuilder.Tile slab=new SourceMeshBuilder.Tile(stone.material(),List.of(new Box(0,0,0,1,.5,1)),0xffffff,0,false,false);
        List<SourceMeshBuilder.Node> partial=List.of(new SourceMeshBuilder.Node(new BlockPos(40,63,0),1,slab,"left"),
                new SourceMeshBuilder.Node(new BlockPos(41,63,0),1,slab,"left"));
        TerrainMeshFrame partialFrame=published(partial,1024,true);
        check(partialFrame.cells().size()==2 && partialFrame.cells().stream().allMatch(c->c.size().equals(new Vec3d(1,1,1)) && c.collision().equals(slab.collision())),
                "Partial model/collision was coalesced like a full cube");
    }
    private static void heterogeneousPublicationKeepsHorizon() {
        List<SourceMeshBuilder.Node> nodes=new ArrayList<>();
        SourceMeshBuilder.Tile a=solid("minecraft:stone"),b=solid("minecraft:dirt");
        for(int x=-90;x<=90;x++)for(int z=-90;z<=90;z++)nodes.add(new SourceMeshBuilder.Node(new BlockPos(x,63,z),1,((x+z)&1)==0?a:b,""));
        for(boolean shallow:new boolean[]{true,false}) {
            TerrainMeshFrame frame=published(nodes,shallow?1024:640,shallow);
            check(frame.cells().size()==(shallow?1024:640),"Heterogeneous fixture should exercise the hard publication budget");
            for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1})check(frame.cells().stream().anyMatch(c->c.sourceMin().x*sx>60 && c.sourceMin().z*sz>60),
                    "Unmergeable terrain publication collapsed to nearest-only locality");
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++) {
                BlockPos p=new BlockPos(x,63,z);check(frame.cells().stream().anyMatch(c->sourceContains(c,p)),"Body/support guard lost exact cell "+p);
            }
            List<SourceMeshBuilder.Node> reversed=new ArrayList<>(nodes);Collections.reverse(reversed);
            check(published(reversed,shallow?1024:640,shallow).equals(frame),"Publication must be input-order deterministic");
        }
    }
    private static void negativeCoverageIsBoundedAndExact() {
        NegativeCoverage negative=new NegativeCoverage();Set<BlockPos> expected=new HashSet<>();
        for(int x=-20;x<=20;x++)for(int y=-3;y<=3;y++)for(int z=-2;z<=2;z++) {
            BlockPos p=new BlockPos(x,y,z);check(negative.add(p),"New negative cell not recorded");expected.add(p);
        }
        check(new HashSet<>(negative).equals(expected),"Packed negative coverage corrupts coordinates, especially negatives");
        List<SourceMeshBuilder.Node> base=List.of(new SourceMeshBuilder.Node(new BlockPos(-16,-16,-16),16,solid("minecraft:stone"),"source"));
        List<SourceMeshBuilder.Node> merged=SourceMeshBuilder.replaceNear(base,List.of(),Set.of(),negative,Vec3d.ZERO);
        check(SourceMeshBuilder.replaceNear(base,List.of(),expected,negative,Vec3d.ZERO).equals(merged),
                "Duplicate tile/mask coverage must not falsely erase an unsampled persisted bucket");
        for(BlockPos p:BlockPos.iterate(-16,-16,-16,-1,-1,-1))check(merged.stream().anyMatch(n->covers(n,p))!=expected.contains(p),
                "Coarse persisted merge does not precisely subtract packed AIR at "+p);
        NegativeCoverage full=new NegativeCoverage();
        for(int i=0;i<NegativeCoverage.MAX_BUCKETS;i++)check(full.add(new BlockPos(i*16,0,0)),"Mask admission stopped early");
        BlockPos refused=new BlockPos(NegativeCoverage.MAX_BUCKETS*16,0,0);
        check(!full.canRecord(refused) && !full.add(refused),"Negative mask memory must have a hard bound");
        BlockPos sameBucket=new BlockPos(1,0,0);check(full.add(sameBucket),"Existing bucket must still accept exact negative bits");
        check(DiscoveryBudget.evictionPlan(DiscoveryBudget.MAX_TILES+1,Set.of(refused),Vec3d.ZERO,p->true,full::canRecord).isEmpty(),
                "AIR that cannot retain negative coverage must not be evicted");
    }
    private static void admissionAndNegativeCopiesStayBounded() {
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();Set<BlockPos> live=new HashSet<>();
        NegativeCoverage negative=new NegativeCoverage();SourceMeshBuilder.Tile stone=solid("minecraft:stone");
        SourceMeshBuilder.Tile air=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:air",Map.of()),List.of(),0,0,true,false);
        BlockPos p=new BlockPos(-33,64,-17);negative.add(p);
        check(DiscoveryBudget.admit(tiles,live,negative,p,stone,false)==DiscoveryBudget.Admission.STALE && tiles.isEmpty(),
                "Reloaded stored SOLID must not supersede a negative live observation");
        NegativeCoverage acquired=new NegativeCoverage();acquired.copyFrom(negative);
        check(DiscoveryBudget.admit(tiles,live,acquired,p,stone,true)==DiscoveryBudget.Admission.UPDATED && !acquired.contains(p) && negative.contains(p),
                "Fresh solid must replace AIR without mutating another source window's mask");
        for(int i=1;tiles.size()<DiscoveryBudget.MAX_STAGED_TILES;i++)tiles.put(new BlockPos(i,0,0),stone);
        BlockPos unseen=new BlockPos(-1000,64,0);
        check(DiscoveryBudget.admit(tiles,live,acquired,unseen,air,true)==DiscoveryBudget.Admission.FULL && !live.contains(unseen),
                "Hard admission must bound both staged tiles and live-position tracking");
        check(DiscoveryBudget.admit(tiles,live,acquired,p,air,true)==DiscoveryBudget.Admission.UPDATED && tiles.get(p).air(),
                "A live AIR update at a known position must remain admissible under memory pressure");
        check(DiscoveryBudget.admit(tiles,live,acquired,p,stone,false)==DiscoveryBudget.Admission.UNCHANGED && tiles.get(p).air(),
                "Staged snapshot ingestion must not overwrite still-retained live AIR either");
        check(tiles.size()==DiscoveryBudget.MAX_STAGED_TILES,"Admission ceiling grew while saturated");
    }
    private static void reflectedSourceKeysAreUnique() {
        Set<Long> keys=new HashSet<>();
        for(int x=-90;x<=90;x++)for(int z=-90;z<=90;z++)
            check(keys.add(SourceMeshBuilder.key("same-source-owner",new Vec3d(x,63,z),1)),
                    "Reflected source coordinates collided and would be dropped at publication: "+x+","+z);
    }
    private static void persistedReservoirKeepsDistantGeometry() {
        List<SourceMeshBuilder.Node> reservoir=new ArrayList<>();SourceMeshBuilder.Tile stone=solid("minecraft:stone");
        java.util.function.ToDoubleFunction<SourceMeshBuilder.Node> distance=n->SourceMeshBuilder.distanceSquared(
                new Box(Vec3d.of(n.position()),Vec3d.of(n.position()).add(n.side(),n.side(),n.side())),FOCUS);
        int visits=0;
        for(int x=-90;x<=90;x++)for(int z=-90;z<=90;z++) {
            reservoir.add(new SourceMeshBuilder.Node(new BlockPos(x,63,z),1,stone,"persisted"));
            if(++visits%32==0 && reservoir.size()>TerrainGeometryStream.MAX_NODES)
                reservoir=MeshPublication.coverageNodes(reservoir,TerrainGeometryStream.MAX_NODES,FOCUS,distance);
            check(reservoir.size()<=TerrainGeometryStream.MAX_NODES+32,"Streaming reservoir transient work grew beyond one batch");
        }
        reservoir=MeshPublication.coverageNodes(reservoir,TerrainGeometryStream.MAX_NODES,FOCUS,distance);
        check(reservoir.size()==TerrainGeometryStream.MAX_NODES,"Persisted source reservoir must remain bounded");
        for(boolean shallow:new boolean[]{true,false}) {
            TerrainMeshFrame frame=published(reservoir,shallow?1024:640,shallow);
            for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1})check(frame.cells().stream().anyMatch(c->c.sourceMin().x*sx>60 && c.sourceMin().z*sz>60),
                    "Bounded persisted streaming erased distant quadrant before publication");
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
                BlockPos p=new BlockPos(x,63,z);check(frame.cells().stream().anyMatch(c->sourceContains(c,p)),"Persisted reservoir lost the body support guard");
            }
        }
    }
    private static void contract() {
        check(DiscoveryBudget.RENDER_DISTANCE==128,"Render-distance coverage contract is explicit");
        check(DiscoveryBudget.MIN_RETENTION_RADIUS<DiscoveryBudget.EXACT_RETENTION_RADIUS,"Floor sits below exact retention");
        check(DiscoveryBudget.EXACT_RETENTION_RADIUS<DiscoveryBudget.RENDER_DISTANCE,"Exact retention stays inside the coverage contract");
        check(DiscoveryBudget.MAX_EVICTED_PER_PASS>0 && DiscoveryBudget.MAX_TILES>0,"Budgets are positive");
    }

    public static void main(String[] args) {
        withinCapEvictsNothing();
        renderDistanceCoveragePreserved();
        exactSupportRadiusProtected();
        lastResortRespectsMinimumFloor();
        airFirstAndDeterministic();
        airEvictionKeepsRenderGeometry();
        sustainedCommitsStayBounded();
        sampledCoverageReplacesOnlyProvenCells();
        publishedSurfaceSpansRenderDistance();
        publicationDoesNotBridgeHolesOrOwners();
        heterogeneousPublicationKeepsHorizon();
        negativeCoverageIsBoundedAndExact();
        admissionAndNegativeCopiesStayBounded();
        reflectedSourceKeysAreUnique();
        persistedReservoirKeepsDistantGeometry();
        contract();
        System.out.println("DiscoveryBudgetTest: "+assertions+" assertions passed");
    }
}
