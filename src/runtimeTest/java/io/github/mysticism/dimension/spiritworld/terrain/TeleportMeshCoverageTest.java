package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.navigation.MovementProvenance;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import java.util.UUID;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

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

    private static void relocationProvenance() {
        for(Vec3d delta:List.of(new Vec3d(.3,0,0),new Vec3d(0,-3.92,0),new Vec3d(4,0,0),
                new Vec3d(6,0,0),new Vec3d(8,0,0),new Vec3d(100,0,0))) {
            check(SpiritTerrainService.relocation(delta,Vec3d.ZERO,false)==null,
                    "distance alone never invents a teleport: "+delta);
            check(delta.equals(SpiritTerrainService.relocation(delta,Vec3d.ZERO,true)),
                    "explicit teleport retains its full displacement at any size: "+delta);
        }
        check(new Vec3d(0,-.25,0).equals(SpiritTerrainService.relocation(new Vec3d(0,-.25,0),Vec3d.ZERO,true)),
                "sub-block vertical teleports relocate too");
    }

    /** Exercise both real provenance consumers together with classification, shallow steering,
     * compaction, collision and accepted-frame readiness. A six-block burst used to freeze source
     * travel while q advanced six; a short /tp used to move source while q correctly stayed still. */
    private static void movementWindowConsistency() {
        UUID player=UUID.randomUUID(),other=UUID.randomUUID();
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();
        for(int x=-1;x<=9;x++)for(int z=-1;z<=1;z++)for(int y=127;y<=130;y++)
            tiles.put(new BlockPos(x,y,z),tile(y==127?STONE:AIR,y!=127));
        // The actual callback order must not matter: terrain and evolver drain independent copies.
        for(boolean terrainFirst:new boolean[]{false,true})for(boolean teleported:new boolean[]{false,true})
            for(Vec3d delta:List.of(new Vec3d(6,0,0),new Vec3d(8,0,0),new Vec3d(.25,0,0),
                    new Vec3d(0,.5,0),Vec3d.ZERO)) {
                try {
                    if(teleported){MovementProvenance.markServerTeleport(player);MovementProvenance.markServerTeleport(player);}
                    boolean terrainTeleport=false;
                    if(terrainFirst)terrainTeleport=MovementProvenance.drainTerrainTeleport(player);
                    boolean semanticTeleport=MovementProvenance.drainServerTeleport(player);
                    if(!terrainFirst)terrainTeleport=MovementProvenance.drainTerrainTeleport(player);
                    check(terrainTeleport==teleported && semanticTeleport==teleported,"both consumers see the same explicit teleport");
                    check(!MovementProvenance.drainTerrainTeleport(player) && !MovementProvenance.drainServerTeleport(player),
                            "each window consumes provenance exactly once");
                    check(!MovementProvenance.drainTerrainTeleport(other) && !MovementProvenance.drainServerTeleport(other),
                            "one player's relocation never changes another observer");
                    Vec3d now=FEET.add(delta),carrier=Vec3d.ZERO;
                    Vec3d jump=SpiritTerrainService.relocation(now,FEET,terrainTeleport);
                    if(jump!=null)carrier=carrier.add(jump);
                    Vec3d source=SpiritTerrainService.shallowSource(Vec3d.ZERO,now,carrier);
                    // requestTeleport's baseline hook may already have erased the semantic measured
                    // delta. Terrain must still preserve source mapping using its own previous body.
                    var sample=MovementIntegration.server(teleported?Vec3d.ZERO:delta,Vec3d.ZERO,semanticTeleport);
                    Vec3d expected=teleported?Vec3d.ZERO:delta;
                    check(sample.semanticDelta().squaredDistanceTo(expected)<1e-20,"semantic window uses the expected chosen travel");
                    check(source.subtract(FEET).squaredDistanceTo(expected)<1e-20,
                            "source and semantic travel agree, including six-block bursts and sub-block teleports");
                    Vec384f q=Vec384f.ZERO();Basis384f basis=new Basis384f();
                    var semantic=sample.semanticDelta();TraversalSteering.advance(q,basis,semantic.x,semantic.y,semantic.z);
                    Vec3d sourceDelta=source.subtract(FEET);
                    check(Math.abs(q.dot(basis.i)*96-sourceDelta.x)<1e-6
                                    && Math.abs(q.dot(basis.j)*96-sourceDelta.y)<1e-6
                                    && Math.abs(q.dot(basis.k)*96-sourceDelta.z)<1e-6,
                            "actual shallow navigation advance agrees with the terrain's mapped source delta");
                    var old=sampled(tiles,Vec3d.ZERO);var next=sampled(tiles,carrier);
                    check(MeshCollision.transitionClear(old,next,body(now),delta,keys(old)),
                            "provenance-consistent mapping publishes the matching collision frame");
                    check(coverage(tiles,source,false,next,carrier).ready(),
                            "accepted source mapping is truthfully collision-ready: delta="+delta+", teleport="+teleported);
                    if(teleported || delta.y==0)check(MeshCollision.ground(new MeshCollision.Index(next),body(now)).isPresent(),
                            "ordinary horizontal bursts and all explicit teleports retain usable ground collision");
                    if(teleported && delta.lengthSquared()>0)check(!coverage(tiles,source,false,old,carrier).ready(),
                            "stale pre-teleport collision cannot claim readiness even for a short teleport");
                    // Neither teleport's second copy may leak into the next ordinary movement window.
                    Vec3d walk=new Vec3d(.125,0,0),after=now.add(walk);
                    Vec3d nextJump=SpiritTerrainService.relocation(after,now,MovementProvenance.drainTerrainTeleport(player));
                    check(nextJump==null,"following ordinary movement does not repeat the carrier relocation");
                    var resumed=MovementIntegration.server(walk,Vec3d.ZERO,MovementProvenance.drainServerTeleport(player));
                    check(SpiritTerrainService.shallowSource(Vec3d.ZERO,after,carrier).subtract(source)
                            .squaredDistanceTo(resumed.semanticDelta())<1e-20,"next ordinary source and semantic windows resume together");
                } finally { MovementProvenance.clear(player);MovementProvenance.clear(other); }
            }
        MovementProvenance.markServerTeleport(player);MovementProvenance.clear(player);
        check(!MovementProvenance.drainTerrainTeleport(player) && !MovementProvenance.drainServerTeleport(player),
                "lifecycle clear retires both consumers' teleport evidence");
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

    /** Build via the production octree compactor, not a fake readiness flag. */
    private static TerrainMeshFrame sampled(Map<BlockPos,SourceMeshBuilder.Tile> tiles,Vec3d carrier) {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();List<TerrainMeshFrame.Material> materials=new ArrayList<>();
        long key=1;
        for(var node:SourceMeshBuilder.compact(tiles,FEET)) {
            var material=node.tile().material();if(!materials.contains(material))materials.add(material);
            Vec3d source=new Vec3d(node.position().getX(),node.position().getY(),node.position().getZ());
            double side=node.side();
            cells.add(new TerrainMeshFrame.Cell(key++,materials.indexOf(material),"landmark",source,source.add(carrier),
                    new Vec3d(side,side,side),new Vec3d(side,0,0),new Vec3d(0,side,0),new Vec3d(0,0,side),
                    0xffffffff,0,1,node.tile().collision()));
        }
        return new TerrainMeshFrame(1,true,"minecraft:overworld",Vec3d.ZERO,carrier,materials,cells);
    }
    private static Set<Long> keys(TerrainMeshFrame frame) {
        Set<Long> keys=new HashSet<>();if(frame!=null)for(var cell:frame.cells())keys.add(cell.key());return keys;
    }
    private static SpiritTerrainService.Coverage coverage(Map<BlockPos,SourceMeshBuilder.Tile> tiles,Vec3d at,
            boolean streaming,TerrainMeshFrame accepted,Vec3d carrier) {
        return SpiritTerrainService.coverage(tiles,at,streaming,accepted,"minecraft:overworld",Vec3d.ZERO,carrier,keys(accepted));
    }
    private static void coverageReadiness() {
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();
        Vec3d at=FEET;
        var unknown=coverage(tiles,at,false,null,Vec3d.ZERO);
        check(!unknown.ready() && !unknown.bodyKnown() && !unknown.supportKnown(),"unknown source samples are not ready");
        tiles.put(new BlockPos(0,128,0),tile(AIR,true));
        tiles.put(new BlockPos(0,129,0),tile(AIR,true));
        var bodyOnly=coverage(tiles,at,false,sampled(tiles,Vec3d.ZERO),Vec3d.ZERO);
        check(bodyOnly.bodyKnown() && !bodyOnly.supportKnown() && !bodyOnly.ready(),
                "review regression: known air at 128/129 cannot hide unknown floor at 127");
        check(SpiritTerrainService.coverageMessage(bodyOnly).contains("still unknown"),"unresolved support reports source loading");
        tiles.put(new BlockPos(0,127,0),tile(STONE,false));
        var accepted=sampled(tiles,Vec3d.ZERO);
        var ready=coverage(tiles,at,true,accepted,Vec3d.ZERO);
        check(ready.ready() && ready.bodyKnown() && ready.supportKnown() && ready.collisionKnown(),
                "resolved source support and matching accepted floor are ready");
        check(ready.streaming(),"broader streaming does not withhold resolved local coverage");
        var absent=coverage(tiles,at,false,null,Vec3d.ZERO);
        check(!absent.ready() && absent.bodyKnown() && absent.supportKnown() && !absent.collisionKnown(),
                "source coverage without an accepted frame is not collision readiness");
        check(SpiritTerrainService.coverageMessage(absent).contains("await matching local collision publication"),
                "resolved coverage awaiting publication never falsely reports unknown source cells");
        var omitted=new TerrainMeshFrame(2,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(),List.of());
        check(!coverage(tiles,at,false,omitted,Vec3d.ZERO).ready(),
                "frame budgets/stitching omitting a resolved floor do not report ready");
        var noProvenance=SpiritTerrainService.coverage(tiles,at,false,accepted,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,Set.of());
        check(!noProvenance.ready(),"an unrelated producer cannot prove local collision coverage");
        // Hold a source change in the cache before compaction/publication. The accepted floor is stale.
        tiles.put(new BlockPos(0,127,0),tile(AIR,true));
        check(!coverage(tiles,at,false,accepted,Vec3d.ZERO).ready(),
                "held/stale publication cannot report ready after the cached floor changes to air");
        var resolvedAir=coverage(tiles,at,false,sampled(tiles,Vec3d.ZERO),Vec3d.ZERO);
        check(resolvedAir.ready() && resolvedAir.supportKnown() && resolvedAir.collisionKnown(),
                "known air below the feet is resolved coverage, not a fabricated support floor");
        check(!coverage(tiles,at,true,accepted,Vec3d.ZERO).ready(),"streaming never overrides stale accepted collision");
        tiles.put(new BlockPos(0,127,0),tile(STONE,false));
        Vec3d jump=new Vec3d(100,0,0);
        check(!coverage(tiles,at,false,accepted,jump).ready(),"old accepted carrier is not ready after teleport");
        var relocated=sampled(tiles,jump);
        check(coverage(tiles,at,false,relocated,jump).ready(),"matching accepted relocated source floor is ready");
        check(new MeshCollision.Index(relocated).sweep(body(at.add(jump)).offset(0,.025,0),new Vec3d(0,-.15,0)).isPresent(),
                "ready relocated frame has usable support sweep collision");
        tiles.remove(new BlockPos(0,129,0));
        var loading=coverage(tiles,at,false,relocated,jump);
        check(!loading.ready() && !loading.bodyKnown() && !loading.streaming(),"one unknown body sample holds readiness");
        check(!coverage(tiles,new Vec3d(500.5,128,.5),false,relocated,jump).ready(),"coverage follows queried source feet");
    }

    private static void probeFootprintAndShapeReadiness() {
        Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();
        // The body spans two X cells: support under its edge must resolve, not just its center floor.
        Vec3d edge=new Vec3d(.95,128,.5);
        for(int x=0;x<=1;x++)for(int y=128;y<=129;y++)tiles.put(new BlockPos(x,y,0),tile(AIR,true));
        tiles.put(new BlockPos(0,127,0),tile(STONE,false));
        check(!coverage(tiles,edge,false,sampled(tiles,Vec3d.ZERO),Vec3d.ZERO).supportKnown(),
                "unknown floor at footprint edge with known center floor blocks readiness");
        tiles.put(new BlockPos(1,127,0),tile(AIR,true));
        check(coverage(tiles,edge,false,sampled(tiles,Vec3d.ZERO),Vec3d.ZERO).ready(),"resolved edge air counts as known");
        // At y=128.1 the ground sweep still reaches y=127.975, unlike a single feet-.05 lookup.
        tiles.remove(new BlockPos(0,127,0));
        check(!coverage(tiles,FEET.add(0,.1,0),false,sampled(tiles,Vec3d.ZERO),Vec3d.ZERO).supportKnown(),
                "readiness covers the full actual support sweep depth");
        // Shape updates must be accepted, not merely cached (slabs/stairs are not replaced by cubes).
        var slab=new SourceMeshBuilder.Tile(STONE,List.of(new Box(0,0,0,1,.5,1)),0xffffffff,0,false,false);
        tiles.put(new BlockPos(0,127,0),tile(STONE,false));
        var full=sampled(tiles,Vec3d.ZERO);
        tiles.put(new BlockPos(0,127,0),slab);
        check(!coverage(tiles,FEET,false,full,Vec3d.ZERO).collisionKnown(),"stale full cube does not match a cached slab");
        check(coverage(tiles,FEET,false,sampled(tiles,Vec3d.ZERO),Vec3d.ZERO).ready(),"published exact slab shape is ready");
        TerrainMeshFrame sampled=sampled(tiles,Vec3d.ZERO);
        var floor=sampled.cells().stream().filter(c->c.sourceMin().equals(new Vec3d(0,127,0))).findFirst().orElseThrow();
        var warped=new TerrainMeshFrame.Cell(floor.key(),floor.material(),floor.landmarkId(),floor.sourceMin(),floor.min().add(.2,0,0),
                floor.size(),floor.axisX(),floor.axisY(),floor.axisZ(),floor.color(),floor.light(),floor.opacity(),floor.collision());
        List<TerrainMeshFrame.Cell> wrongCells=new ArrayList<>(sampled.cells());wrongCells.set(wrongCells.indexOf(floor),warped);
        TerrainMeshFrame wrongTransform=new TerrainMeshFrame(3,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,sampled.materials(),wrongCells);
        check(!coverage(tiles,FEET,false,wrongTransform,Vec3d.ZERO).ready(),"stale local transform fails even with matching carrier metadata");
        Vec3d sourceOrigin=new Vec3d(140.3,64.1,-209.7),carrier=sourceOrigin.add(100,17,-30);
        List<TerrainMeshFrame.Cell> shifted=new ArrayList<>();
        for(var c:sampled.cells())shifted.add(new TerrainMeshFrame.Cell(c.key(),c.material(),c.landmarkId(),c.sourceMin(),
                carrier.add(c.sourceMin().subtract(sourceOrigin)),c.size(),c.axisX(),c.axisY(),c.axisZ(),c.color(),c.light(),c.opacity(),c.collision()));
        TerrainMeshFrame anchored=new TerrainMeshFrame(4,true,"minecraft:overworld",sourceOrigin,carrier,sampled.materials(),shifted);
        check(SpiritTerrainService.coverage(tiles,FEET,false,anchored,"minecraft:overworld",sourceOrigin,carrier,keys(anchored)).ready(),
                "fractional source origin and a vertical/horizontal carrier relocation still match actual shapes");
        check(!SpiritTerrainService.coverage(tiles,FEET,false,anchored,"minecraft:the_nether",sourceOrigin,carrier,keys(anchored)).ready(),
                "an accepted different source dimension cannot claim local readiness");
        var coarse=new TerrainMeshFrame(5,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),List.of(
                new TerrainMeshFrame.Cell(1,0,"landmark",new Vec3d(0,126,0),new Vec3d(0,126,0),new Vec3d(2,2,2),
                        new Vec3d(2,0,0),new Vec3d(0,2,0),new Vec3d(0,0,2),0xffffffff,0,1,List.of(SourceMeshBuilder.UNIT))));
        tiles.put(new BlockPos(0,127,0),tile(STONE,false));
        check(coverage(tiles,FEET,false,coarse,Vec3d.ZERO).ready(),"matching uniform octree cube covers a resolved floor sample");
        tiles.put(new BlockPos(0,127,0),slab);
        check(!coverage(tiles,FEET,false,coarse,Vec3d.ZERO).ready(),"a coarse cube cannot prove resolved non-cube shape coverage");
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

    private static void teleportAfterWalkingPublication() {
        Vec3d walk=new Vec3d(.3,0,0),jump=new Vec3d(100,0,0);
        TerrainMeshFrame old=frame(1,Vec3d.ZERO),next=frame(2,jump);
        Box teleportedBody=body(FEET.add(walk).add(jump));
        Vec3d observerDelta=walk.add(jump);
        check(!MeshCollision.transitionClear(old,next,teleportedBody,observerDelta),
                "review regression control: walking .3 then teleporting 100 held the nearby wall swept corridor");
        check(MeshCollision.transitionClear(old,next,teleportedBody,observerDelta,Set.of(1L,2L)),
                "shallow local motion uses accepted carrier delta (100), not body delta (100.3)");
        // More than one relocation before an acceptance must use the last accepted frame's carrier.
        TerrainMeshFrame twice=frame(3,jump.multiply(2));
        check(MeshCollision.transitionClear(old,twice,body(FEET.add(walk).add(jump.multiply(2))),walk.add(jump.multiply(2)),Set.of(1L,2L)),
                "pending relocation accumulation is measured from accepted carrier, not last tick");
        // Independently projected regions retained in a shallow view follow the body, NOT its carrier.
        TerrainMeshFrame mixedOld=new TerrainMeshFrame(4,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),List.of(
                old.cells().getFirst(),old.cells().get(1),cell(3,0,new Vec3d(2,129,0))));
        TerrainMeshFrame mixedNext=new TerrainMeshFrame(5,true,"minecraft:overworld",Vec3d.ZERO,jump,List.of(STONE),List.of(
                next.cells().getFirst(),next.cells().get(1),cell(3,0,new Vec3d(2,129,0).add(observerDelta))));
        check(MeshCollision.transitionClear(mixedOld,mixedNext,teleportedBody,observerDelta,Set.of(1L,2L)),
                "mixed shallow/deep producer cells each use their independent accepted displacement");
        check(!MeshCollision.transitionClear(mixedOld,mixedNext,teleportedBody,observerDelta,Set.of(1L,2L,3L)),
                "regression control: treating a deep representative as carrier-aligned falsely holds its swept corridor");
        TerrainMeshFrame intrusive=new TerrainMeshFrame(6,true,"minecraft:overworld",Vec3d.ZERO,jump,List.of(STONE),List.of(
                next.cells().getFirst(),cell(2,0,new Vec3d(100,128,0))));
        check(!MeshCollision.transitionClear(old,intrusive,teleportedBody,observerDelta,Set.of(1L,2L)),
                "carrier exemption never admits real wall motion into the relocated body");
        check(new MeshCollision.Index(next).sweep(teleportedBody.offset(0,.025,0),new Vec3d(0,-.15,0)).isPresent(),
                "teleport after walking retains usable floor collision in the published frame");
    }

    /** Merge contract: relocation keeps affine support, then the real walk-alignment sweep must
     * still flatten it. The relocation exception must neither restore the old AABB-only rejection
     * nor exempt a subsequent change of axes from the continuous collision proof. */
    private static void affineSupportAfterTeleport() {
        for(double degrees:new double[]{-5,5}) {
            double angle=Math.toRadians(degrees),s=Math.sin(angle),c=Math.cos(angle);
            Vec3d x=new Vec3d(c,s,0),y=new Vec3d(-s,c,0),z=UNIT_AXES_Z;
            Vec3d feet=new Vec3d(.5,1+.3*Math.abs(s),.5),jump=new Vec3d(100,17,-200);
            Vec3d min=x.multiply(-feet.x).add(y.multiply(-feet.y)).add(z.multiply(-feet.z));
            var tilted=new TerrainMeshFrame.Cell(1,0,"landmark",Vec3d.ZERO,min,new Vec3d(1,1,1),x,y,z,
                    -1,0,1,List.of(SourceMeshBuilder.UNIT));
            var relocated=new TerrainMeshFrame.Cell(1,0,"landmark",Vec3d.ZERO,min.add(jump),new Vec3d(1,1,1),x,y,z,
                    -1,0,1,List.of(SourceMeshBuilder.UNIT));
            var flat=new TerrainMeshFrame.Cell(1,0,"landmark",Vec3d.ZERO,jump.subtract(feet),new Vec3d(1,1,1),
                    UNIT_AXES_X,UNIT_AXES_Y,z,-1,0,1,List.of(SourceMeshBuilder.UNIT));
            var before=MeshTransitionClearanceTest.frame(tilted);
            var after=MeshTransitionClearanceTest.frame(relocated);
            var walking=MeshTransitionClearanceTest.frame(flat);
            Box body=body(jump);
            check(MeshCollision.transitionClear(before,after,body,jump,Set.of()),"teleport publishes the unchanged affine support");
            check(MeshCollision.ground(new MeshCollision.Index(after),body).isPresent(),"relocated tilted mesh provides real grounding");
            check(relocated.bounds().intersects(body.expand(-1e-5)),"fixture retains misleading block-shaped AABB overlap");
            check(MeshCollision.bodyClear(after,body),"real tilted support clears the relocated body");
            check(MeshCollision.transitionClear(after,walking,body,Vec3d.ZERO,Set.of()),"post-teleport walk alignment passes affine continuity proof");
            check(MeshCollision.ground(new MeshCollision.Index(walking),body).isPresent(),"aligned walking mesh retains real ground support");
            var intruding=new TerrainMeshFrame.Cell(1,0,"landmark",Vec3d.ZERO,flat.min().add(0,.1,0),flat.size(),
                    flat.axisX(),flat.axisY(),flat.axisZ(),-1,0,1,flat.collision());
            check(!MeshCollision.transitionClear(after,MeshTransitionClearanceTest.frame(intruding),body,Vec3d.ZERO,Set.of()),
                    "relocation exception does not admit axis-changing support that enters the body");
        }
    }

    /** Both endpoints are outside the removed five-block cull, but true surface motion crosses
     * the body. Exercise the observer/carrier-aware overload used by publication, not only the
     * legacy three-argument alignment entrypoint. */
    private static void distantCrossingStillHeld() {
        var old=new TerrainMeshFrame(1,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),
                List.of(cell(1,0,new Vec3d(-8,128,0))));
        var next=new TerrainMeshFrame(2,true,"minecraft:overworld",Vec3d.ZERO,new Vec3d(100,0,0),List.of(STONE),
                List.of(cell(1,0,new Vec3d(24,128,0))));
        check(MeshCollision.bodyClear(old,body(FEET)) && MeshCollision.bodyClear(next,body(FEET)),"distant crossing endpoints clear the player");
        check(!MeshCollision.transitionClear(old,next,body(FEET),new Vec3d(100.3,0,0),Set.of(1L)),
                "carrier/observer-aware publication still rejects distant-to-distant wall crossing");
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
        relocationProvenance();
        movementWindowConsistency();
        carrierContinuity();
        coverageReadiness();
        probeFootprintAndShapeReadiness();
        rigidObserverPublication();
        teleportAfterWalkingPublication();
        affineSupportAfterTeleport();
        distantCrossingStillHeld();
        matchingUsableCollision();
        System.out.println("TeleportMeshCoverageTest: "+checks+" checks passed");
    }
}
