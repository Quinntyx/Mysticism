package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** After /tp the displayed terrain must carry matching usable collision, with truthful readiness while
 * coverage genuinely loads. Regression suite for the carrier relocation, rigid-observer transition
 * publication and shallow coverage readiness contracts. No server, model or game assets required. */
public final class TeleportMeshCoverageTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value)throw new AssertionError(why); }

    private static final TerrainMeshFrame.Material STONE=new TerrainMeshFrame.Material("minecraft:stone",Map.of());
    private static final TerrainMeshFrame.Material AIR=new TerrainMeshFrame.Material("minecraft:air",Map.of());
    private static final Vec3d UNIT_AXES_X=new Vec3d(1,0,0),UNIT_AXES_Y=new Vec3d(0,1,0),UNIT_AXES_Z=new Vec3d(0,0,1);

    private static SourceMeshBuilder.Tile tile(TerrainMeshFrame.Material material,boolean air) {
        return new SourceMeshBuilder.Tile(material,air?List.of():List.of(SourceMeshBuilder.UNIT),0xffffffff,0,air,!air);
    }
    private static TerrainMeshFrame.Cell cell(long key,int material,Vec3d min) {
        return new TerrainMeshFrame.Cell(key,material,"landmark",min,min,new Vec3d(1,1,1),UNIT_AXES_X,UNIT_AXES_Y,UNIT_AXES_Z,
                0xffffffff,0,1.0f,List.of(SourceMeshBuilder.UNIT));
    }
    /** Floor slab at y[127,128] plus a body-height wall two blocks in +X; body stands on the floor at x=0.5. */
    private static TerrainMeshFrame frame(long revision,Vec3d origin) {
        return new TerrainMeshFrame(revision,true,"minecraft:overworld",origin,origin,List.of(STONE),List.of(
                cell(1,0,new Vec3d(origin.x,127,origin.z)),
                cell(2,0,new Vec3d(origin.x+2,128,origin.z))));
    }
    private static final Vec3d FEET=new Vec3d(.5,128,.5);
    private static Box body(Vec3d feet){return new Box(feet.x-.3,feet.y,feet.z-.3,feet.x+.3,feet.y+1.8,feet.z+.3);}

    private static void relocationThreshold() {
        check(SpiritTerrainService.relocation(new Vec3d(.3,0,0),Vec3d.ZERO)==null,"ordinary walking pace is not a relocation");
        check(SpiritTerrainService.relocation(new Vec3d(0,-3.92,0),Vec3d.ZERO)==null,"terminal fall velocity is not a relocation");
        check(SpiritTerrainService.relocation(new Vec3d(4,0,0),Vec3d.ZERO)==null,"exactly four blocks is ordinary movement");
        Vec3d jump=SpiritTerrainService.relocation(new Vec3d(100,0,0),Vec3d.ZERO);
        check(jump!=null && jump.equals(new Vec3d(100,0,0)),"a /tp-sized per-tick jump is a relocation carrying its delta");
        check(SpiritTerrainService.relocation(new Vec3d(0,-40,0),Vec3d.ZERO)!=null,"vertical teleports relocate too");
    }

    private static void carrierContinuity() {
        Vec3d origin=new Vec3d(1000,64,-2000),carrier=new Vec3d(0,128,0),walked=new Vec3d(10.5,128,3.25);
        Vec3d before=SpiritTerrainService.shallowSource(origin,walked,carrier);
        Vec3d jump=new Vec3d(-321,17,900),teleported=walked.add(jump);
        // Fix: the carrier moves with the body, so the mapped source position is unchanged by the teleport.
        Vec3d after=SpiritTerrainService.shallowSource(origin,teleported,carrier.add(jump));
        check(after.equals(before),"carrier relocation preserves the mapped source position (no source/semantic travel)");
        // Bug being fixed: a stale carrier drained the mapping into origin+(teleport delta), chasing
        // ungenerated source regions below a falling body forever.
        Vec3d drifted=SpiritTerrainService.shallowSource(origin,teleported,carrier);
        check(!drifted.equals(before),"stale-carrier mapping demonstrably drifted before the fix");
        Vec3d advanced=SpiritTerrainService.shallowSource(origin,walked.add(new Vec3d(.3,0,0)),carrier);
        check(Math.abs(advanced.distanceTo(before)-.3)<1e-9,
                "ordinary movement still advances the source mapping by the body delta");
    }

    private static void coverageReadiness() {
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();
        Vec3d at=new Vec3d(.5,128,.5);
        check(!SpiritTerrainService.coverage(tiles,at,false).ready(),"unknown body cells are not ready");
        check(!SpiritTerrainService.coverage(tiles,at,false).bodyKnown(),"unknown body cells are reported unknown");
        // Known air above, solid floor below: the exact cells the body overlaps must all be known.
        tiles.put(new BlockPos(0,128,0),tile(AIR,true));
        tiles.put(new BlockPos(0,129,0),tile(AIR,true));
        tiles.put(new BlockPos(0,127,0),tile(STONE,false));
        SpiritTerrainService.Coverage ready=SpiritTerrainService.coverage(tiles,at,true);
        check(ready.ready() && ready.bodyKnown(),"known near coverage is ready even while a broader region still streams");
        check(ready.streaming(),"streaming state is surfaced truthfully");
        tiles.remove(new BlockPos(0,129,0));
        SpiritTerrainService.Coverage loading=SpiritTerrainService.coverage(tiles,at,false);
        check(!loading.ready() && !loading.bodyKnown() && !loading.streaming(),
                "a single unknown body cell holds readiness while not streaming");
        check(SpiritTerrainService.coverage(tiles,new Vec3d(500.5,128,.5),false).ready()==false,
                "coverage follows the queried body position");
    }

    private static void rigidObserverPublication() {
        Vec3d origin=Vec3d.ZERO,jump=new Vec3d(100,0,0);
        TerrainMeshFrame accepted=frame(1,origin);
        // Post-teleport re-anchor: the same window re-roots around the relocated body.
        TerrainMeshFrame relocated=frame(2,origin.add(jump));
        Box oldBody=body(FEET),newBody=body(FEET.add(jump));
        // The fix: rigid observer motion never holds publication...
        check(MeshCollision.transitionClear(accepted,relocated,newBody,jump),
                "terrain re-rooted exactly with the teleported body publishes immediately");
        check(MeshCollision.transitionClear(null,relocated,newBody,jump),"a first frame always publishes");
        // ...while the previous behaviour falsely held it: the swept corridor of the wall cell covered the body.
        check(!MeshCollision.transitionClear(accepted,relocated,newBody,Vec3d.ZERO),
                "regression control: without the observer delta the relocated wall corridor held publication");
        // Genuine surface motion into the body is still held, relocation or not.
        TerrainMeshFrame intruding=new TerrainMeshFrame(3,true,"minecraft:overworld",origin,origin,List.of(STONE),List.of(
                cell(1,0,Vec3d.ZERO),cell(2,0,new Vec3d(0,128,0)))); // wall moved over the body
        check(!MeshCollision.transitionClear(accepted,intruding,oldBody,jump),
                "a surface that moves differently from the observer still holds the transition");
        TerrainMeshFrame appearing=new TerrainMeshFrame(4,true,"minecraft:overworld",origin,origin,List.of(STONE),List.of(
                cell(1,0,Vec3d.ZERO),cell(2,0,new Vec3d(2,128,0)),cell(3,0,new Vec3d(0,129,0))));
        check(!MeshCollision.transitionClear(accepted,appearing,oldBody,Vec3d.ZERO),
                "new geometry intersecting the body is held even when the rest of the frame is unchanged");
        // Exact-delta tolerance: re-rooted cells recompute min through different float arithmetic.
        TerrainMeshFrame fp=new TerrainMeshFrame(5,true,"minecraft:overworld",origin,origin.add(jump),List.of(STONE),List.of(
                cell(1,0,new Vec3d(origin.x*1.0+100,127,origin.z)),
                cell(2,0,new Vec3d(2+100+1e-9,128,0))));
        check(MeshCollision.transitionClear(accepted,fp,newBody,jump),"floating-point rigid motion is still recognized");
    }

    private static boolean sameBox(Box a,Box b) {
        return a.minX==b.minX && a.minY==b.minY && a.minZ==b.minZ && a.maxX==b.maxX && a.maxY==b.maxY && a.maxZ==b.maxZ;
    }

    private static void matchingUsableCollision() {
        Vec3d jump=new Vec3d(100,0,0);
        TerrainMeshFrame before=frame(1,Vec3d.ZERO),after=frame(2,Vec3d.ZERO.add(jump));
        // The collision surface moves exactly with the displayed geometry: the floor top under the
        // relocated feet is the same relative support surface as before the teleport.
        var beforeFloor=before.cells().getFirst();
        var afterFloor=after.cells().getFirst();
        check(sameBox(afterFloor.bounds(afterFloor.collision().getFirst()),
                beforeFloor.bounds(beforeFloor.collision().getFirst()).offset(jump)),
                "displayed floor collision re-roots with the teleport");
        Box newBody=body(FEET.add(jump));
        check(MeshCollision.bodyClear(after,newBody),"the relocated body is not embedded in the re-rooted terrain");
        check(!MeshCollision.bodyClear(after,body(FEET.add(jump).add(0,-1,0))),
                "standing inside the floor is (still) rejected: collision is usable, not decorative");
        // Every collision primitive of every cell stays inside the displayed cell volume (render/SAT share columns).
        for(var c:after.cells()) {
            Box displayed=c.bounds();
            for(Box collision:c.collision()) {
                Box world=c.bounds(collision);
                check(world.minX>=displayed.minX-1e-9 && world.maxX<=displayed.maxX+1e-9
                        && world.minY>=displayed.minY-1e-9 && world.maxY<=displayed.maxY+1e-9
                        && world.minZ>=displayed.minZ-1e-9 && world.maxZ<=displayed.maxZ+1e-9,
                        "collision primitive stays within its displayed cell");
            }
        }
    }

    public static void main(String[] args) {
        relocationThreshold();
        carrierContinuity();
        coverageReadiness();
        rigidObserverPublication();
        matchingUsableCollision();
        System.out.println("TeleportMeshCoverageTest: "+checks+" checks passed");
    }
}
