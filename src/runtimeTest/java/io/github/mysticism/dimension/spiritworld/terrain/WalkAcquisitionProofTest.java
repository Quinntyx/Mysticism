package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.navigation.SupportAlignment;
import io.github.mysticism.navigation.SpiritNavigationService;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Entity-free acquisition regression: real alignment, the production source/mesh proof, then the
 * real navigation component's walking transition. Does not bootstrap a server or claim gameplay. */
public final class WalkAcquisitionProofTest {
    private static final String DIMENSION="minecraft:overworld",LANDMARK="floor";
    private static final TerrainMeshFrame.Material STONE=new TerrainMeshFrame.Material("minecraft:stone",Map.of());
    private static final TerrainMeshFrame.Material AIR=new TerrainMeshFrame.Material("minecraft:air",Map.of());
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static Box body(Vec3d feet){return new Box(feet.x-.3,feet.y,feet.z-.3,feet.x+.3,feet.y+1.8,feet.z+.3);}
    private static SourceMeshBuilder.Tile tile(List<Box> shapes){
        return new SourceMeshBuilder.Tile(shapes.isEmpty()?AIR:STONE,shapes,-1,0,shapes.isEmpty(),shapes.equals(List.of(SourceMeshBuilder.UNIT)));
    }
    private static Map<BlockPos,SourceMeshBuilder.Tile> known(Vec3d feet){
        Box b=MeshCollision.supportFootprint(body(feet));var tiles=new HashMap<BlockPos,SourceMeshBuilder.Tile>();
        for(BlockPos pos:BlockPos.iterate(MathHelper.floor(b.minX),MathHelper.floor(b.minY),MathHelper.floor(b.minZ),
                MathHelper.floor(b.maxX),MathHelper.floor(b.maxY),MathHelper.floor(b.maxZ)))
            tiles.put(pos.toImmutable(),tile(List.of()));
        return tiles;
    }
    private static TerrainMeshFrame placed(Vec3d player,Vec3d feet,Basis384f observer,List<Box> shape){
        Basis384f grid=new Basis384f();Vec384f q=Vec384f.ZERO();
        Vec384f semantic=SpiritTerrainService.supportPivotSemantic(q,grid,feet,Vec3d.ZERO);
        return new TerrainMeshFrame(1,false,DIMENSION,Vec3d.ZERO,player,List.of(STONE),List.of(
                new TerrainMeshFrame.Cell(1,0,LANDMARK,Vec3d.ZERO,
                        SpiritTerrainService.frameCellMin(player,semantic,q,observer,grid,Vec3d.ZERO,Vec3d.ZERO),
                        new Vec3d(1,1,1),SpiritTerrainService.frameAxis(grid.i,observer),SpiritTerrainService.frameAxis(grid.j,observer),
                        SpiritTerrainService.frameAxis(grid.k,observer),-1,0,1,shape)));
    }
    private static TerrainMeshFrame walking(Vec3d player,Vec3d feet,List<Box> shape){
        return walking(player,feet,BlockPos.ORIGIN,shape);
    }
    private static TerrainMeshFrame walking(Vec3d player,Vec3d feet,BlockPos producer,List<Box> shape){
        Vec3d source=new Vec3d(producer.getX(),producer.getY(),producer.getZ());
        return new TerrainMeshFrame(2,true,DIMENSION,feet,player,List.of(STONE),List.of(
                new TerrainMeshFrame.Cell(1,0,LANDMARK,source,player.subtract(feet).add(source),new Vec3d(1,1,1),
                        new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),-1,0,1,shape)));
    }
    private static boolean proof(Map<BlockPos,SourceMeshBuilder.Tile> tiles,Vec3d feet,TerrainMeshFrame previous,
            TerrainMeshFrame next,Vec3d player){
        return SpiritTerrainService.walkAcquisitionClear(tiles,feet,previous,next,body(player),Vec3d.ZERO);
    }
    private static void alignedSupportBecomesWalking(){
        for(float degrees:new float[]{-5,5,-12,12,-20,20,-24,24})
                for(Vec3d player:new Vec3d[]{Vec3d.ZERO,new Vec3d(8123,70,-431.5),new Vec3d(29000000,255,-29000000)}){
            Basis384f grid=new Basis384f();double angle=Math.toRadians(degrees);
            float sine=(float)Math.sin(angle),cosine=(float)Math.cos(angle);
            Basis384f from=new Basis384f(grid.i.clone().mul(cosine).sub(grid.j.clone().mul(sine)),
                    grid.i.clone().mul(sine).add(grid.j.clone().mul(cosine)),grid.k);
            // Preserve the inverse-mapped feet throughout alignment, as production does. At 12°
            // y = 1.06237: the old floor(feet.y-.05) proof inspected the known AIR at y=1.
            Vec3d feet=new Vec3d(.5,1+.3*Math.abs(sine),.5);
            var tiles=known(feet);tiles.put(BlockPos.ORIGIN,tile(List.of(SourceMeshBuilder.UNIT)));
            if(Math.abs(degrees)>=12)check(tiles.get(BlockPos.ofFloored(feet.add(0,-.05,0))).air(),
                    "fixture reproduces the old false-air floor sample beyond five degrees");
            TerrainMeshFrame current=placed(player,feet,from,List.of(SourceMeshBuilder.UNIT));
            check(MeshCollision.bodyClear(current,body(player)),"initial tilted support clears the actual body");
            check(MeshCollision.ground(new MeshCollision.Index(current),body(player)).isPresent(),"initial tilted floor really supports the player");
            SpiritNavigation nav=new SpiritNavigation();nav.enterDeep();nav.setSemanticReady(true);nav.setSupportApproach(true);
            nav.target(DIMENSION,"captured-destination",new Vec3d(17.5,80,-30.5),grid);
            Vec3d target=nav.targetPosition();long epoch=nav.motionEpoch();
            SupportAlignment alignment=new SupportAlignment();int ticks=0;
            while(!alignment.complete() && ticks++<200){
                float fraction=alignment.nextFraction();Basis384f proposed=TraversalSteering.blend(from,grid,fraction);
                TerrainMeshFrame next=placed(player,feet,proposed,List.of(SourceMeshBuilder.UNIT));
                if(!MeshCollision.transitionClear(current,next,body(player))){alignment.refuse();continue;}
                check(MeshCollision.bodyClear(next,body(player)),"accepted alignment keeps real body clearance");
                check(MeshCollision.ground(new MeshCollision.Index(next),body(player)).isPresent(),"accepted alignment keeps real support");
                alignment.accept(fraction);current=next;
            }
            check(alignment.complete(),"tilted alignment completes before walk request expiry: "+degrees);
            var shallow=walking(player,feet,List.of(SourceMeshBuilder.UNIT));
            // These are the same final geometry gates acquireCurrentSupport runs before publishing.
            // Only a successful proof may reach attemptSupport's real mapping/state commit.
            boolean acquired=proof(tiles,feet,current,shallow,player);
            check(acquired,"source footprint AND mesh continuity permit walking acquisition at "+degrees+" degrees");
            var support=new SpiritTerrainService.WalkSupport(LANDMARK,DIMENSION,feet,grid,Vec384f.ZERO(),new Vec3d(0,1,0),1,1);
            check(acquired && SpiritNavigationService.commitSupportMapping(nav,support,
                    Optional.of(new SpiritTerrainService.SourcePosition(DIMENSION,feet,LANDMARK))),"validated source acquisition commits through the production walking-state transition");
            check(nav.active() && !nav.deep() && !nav.supportApproach(),"accepted acquisition actually leaves deep/request state for walking");
            check(nav.sourcePosition().equals(feet) && nav.landmarkId().equals(LANDMARK),"walking keeps exact inverse-mapped feet and owner, without snapping");
            check(nav.motionEpoch()>epoch,"walking transition invalidates flight prediction");
            check(nav.targetLandmarkId().equals("captured-destination") && nav.targetPosition().equals(target),"walking does not replace the captured destination");
        }
    }
    private static void footprintAndShapeEvidence(){
        Vec3d player=new Vec3d(100,80,-20),feet=new Vec3d(1.15,1.0624,.5);
        var tiles=known(feet);tiles.put(BlockPos.ORIGIN,tile(List.of(SourceMeshBuilder.UNIT)));
        var floor=walking(player,feet,List.of(SourceMeshBuilder.UNIT));
        check(tiles.get(BlockPos.ofFloored(feet.add(0,-.05,0))).air(),"off-center feet sample is air");
        check(proof(tiles,feet,floor,floor,player),"support can contact the footprint edge rather than the center voxel");
        tiles.remove(new BlockPos(1,0,0));
        check(!proof(tiles,feet,floor,floor,player),"an unresolved neighboring support sample is not accepted as known air");
        tiles=known(feet);tiles.put(BlockPos.ORIGIN,tile(List.of(new Box(0,0,0,.4,1,1))));
        check(!proof(tiles,feet,floor,floor,player),"a block identity is not support when its actual shape misses the footprint");
        tiles=known(feet);tiles.put(BlockPos.ORIGIN,tile(List.of(SourceMeshBuilder.UNIT)));
        tiles.put(new BlockPos(1,1,0),tile(List.of(SourceMeshBuilder.UNIT)));
        check(!proof(tiles,feet,floor,floor,player),"source body obstruction still blocks acquisition");
        Vec3d slabFeet=new Vec3d(.5,.5624,.5);var slabShape=List.of(new Box(0,0,0,1,.5,1));
        var slab=walking(player,slabFeet,slabShape);tiles=known(slabFeet);tiles.put(BlockPos.ORIGIN,tile(slabShape));
        check(proof(tiles,slabFeet,slab,slab,player),"actual half-slab contact is accepted, not replaced with a full-block stand-in");
        var penetrating=walking(player,slabFeet,List.of(SourceMeshBuilder.UNIT));
        check(!proof(tiles,slabFeet,slab,penetrating,player),"source support does not bypass final projected-body clearance");
        for(double gap:new double[]{.124,.126}){
            Vec3d at=new Vec3d(.5,1+gap,.5);tiles=known(at);tiles.put(BlockPos.ORIGIN,tile(List.of(SourceMeshBuilder.UNIT)));
            var frame=walking(player,at,List.of(SourceMeshBuilder.UNIT));
            boolean grounded=MeshCollision.ground(new MeshCollision.Index(frame),body(player)).isPresent();
            check(grounded==(gap<.125),"ground probe fixture straddles the actual drop limit");
            check(proof(tiles,at,frame,frame,player)==grounded,"source proof uses the same actual grounding reach, not a separate contact tolerance");
        }
    }
    /** Fence/wall support extends above its producing voxel. Its origin is outside the grounding
     * footprint even while its actual top face supports the body; the old origin-only proof misses
     * it. Also cover horizontal positive/negative local overhangs allowed by the mesh contract. */
    private static void neighboringProducerBecomesWalking(){
        Vec3d player=new Vec3d(8123,70,-431.5);
        for(int originX:new int[]{0,-1,-2,2})for(double gap:new double[]{0,.0624}){
            BlockPos producer=new BlockPos(originX,0,0);Vec3d feet=new Vec3d(.5,1.5+gap,.5);
            List<Box> shape=List.of(new Box(.375-originX,0,.375,.625-originX,1.5,.625));
            var tiles=known(feet);
            check(!tiles.containsKey(producer),"1.5-high shape producer lies outside the direct grounding footprint");
            tiles.put(producer,tile(shape));var frame=walking(player,feet,producer,shape);
            var previous=new TerrainMeshFrame(1,false,DIMENSION,feet,player,frame.materials(),frame.cells());
            check(MeshCollision.ground(new MeshCollision.Index(previous),body(player)).isPresent(),"displayed deep-frame tall shape supplies real mesh ground contact");
            check(MeshCollision.bodyClear(frame,body(player)),"standing above tall shape does not penetrate it");
            boolean acquired=proof(tiles,feet,previous,frame,player);
            check(acquired,"neighboring cached shape producer permits acquisition instead of expiring the walk request");
            var nav=new SpiritNavigation();nav.enterDeep();nav.setSemanticReady(true);nav.setSupportApproach(true);
            nav.target(DIMENSION,"captured-destination",new Vec3d(17.5,80,-30.5),new Basis384f());long epoch=nav.motionEpoch();
            var support=new SpiritTerrainService.WalkSupport(LANDMARK,DIMENSION,feet,new Basis384f(),Vec384f.ZERO(),new Vec3d(0,1,0),1,1);
            check(acquired && SpiritNavigationService.commitSupportMapping(nav,support,
                    Optional.of(new SpiritTerrainService.SourcePosition(DIMENSION,feet,LANDMARK))),"tall support acquisition reaches production walking-state commit");
            check(nav.active() && !nav.deep() && !nav.supportApproach() && nav.motionEpoch()>epoch,"tall support leaves flight/request state and invalidates prediction");
            check(nav.sourcePosition().equals(feet) && nav.landmarkId().equals(LANDMARK)
                    && nav.targetLandmarkId().equals("captured-destination"),"tall support keeps exact feet, owner and captured destination");
            tiles.remove(producer);
            check(!proof(tiles,feet,previous,frame,player),"known air without a cached supporting producer cannot acquire walking");
            tiles.put(producer,tile(shape));tiles.remove(new BlockPos(0,1,0));
            check(!proof(tiles,feet,previous,frame,player),"neighbor support does not hide unknown direct footprint samples");
        }
        Vec3d feet=new Vec3d(.5,1.49,.5);var shape=List.of(new Box(.375,0,.375,.625,1.5,.625));
        var tiles=known(feet);tiles.put(BlockPos.ORIGIN,tile(shape));var penetrating=walking(player,feet,shape);
        check(!MeshCollision.bodyClear(penetrating,body(player)),"actual over-height mesh bounds detect penetration outside the producer's unit box");
        check(!proof(tiles,feet,penetrating,penetrating,player),"over-height source body obstruction cannot be accepted as walking support");
        // Keep the displayed frame clear to independently exercise source body clearance for a
        // neighboring tall collider, rather than passing only because the projected mesh vetoed it.
        var clear=walking(player,feet,List.of(new Box(0,0,0,1,1,1)));
        check(MeshCollision.bodyClear(clear,body(player)),"independent source-obstruction fixture has a clear projected frame");
        check(!proof(tiles,feet,clear,clear,player),"neighbor-origin source collision still vetoes acquisition with a clear projected frame");
    }
    private static void changedMappingStaysDeep(){
        Vec3d feet=new Vec3d(.5,1.0624,.5);var nav=new SpiritNavigation();nav.enterDeep();nav.setSupportApproach(true);
        var support=new SpiritTerrainService.WalkSupport(LANDMARK,DIMENSION,feet,new Basis384f(),Vec384f.ZERO(),new Vec3d(0,1,0),1,1);
        check(!SpiritNavigationService.commitSupportMapping(nav,support,Optional.empty()),"no source mapping cannot commit walking");
        check(!SpiritNavigationService.commitSupportMapping(nav,support,Optional.of(new SpiritTerrainService.SourcePosition(DIMENSION,feet,"changed-owner"))),"changed landmark cannot commit walking");
        check(!SpiritNavigationService.commitSupportMapping(nav,support,Optional.of(new SpiritTerrainService.SourcePosition("minecraft:the_nether",feet,LANDMARK))),"changed dimension cannot commit walking");
        check(nav.deep() && nav.supportApproach(),"failed mapping validation does not falsely publish walking state");
    }
    public static void main(String[] args){
        alignedSupportBecomesWalking();footprintAndShapeEvidence();neighboringProducerBecomesWalking();changedMappingStaysDeep();
        System.out.println("Walk acquisition proof self-tests passed: source footprint through walking state ("+checks+" checks)");
    }
}
