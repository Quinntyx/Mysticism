package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.Predicate;

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
        victims.forEach(tiles::remove);
        check(SourceMeshBuilder.compact(tiles,center).equals(compactBefore),
                "Air retention eviction must not change a single rendered/compacted node");
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
        contract();
        System.out.println("DiscoveryBudgetTest: "+assertions+" assertions passed");
    }
}
