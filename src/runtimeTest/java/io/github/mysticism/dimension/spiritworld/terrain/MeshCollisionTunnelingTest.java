package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Continuous-collision regressions for player/semantic motion through displayed affine terrain meshes.
 * Uses the real entity-free movement cores against real distorted cells: no placeholders, no stubs. */
public final class MeshCollisionTunnelingTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value)throw new AssertionError(why); }
    private static final TerrainMeshFrame.Material STONE=new TerrainMeshFrame.Material("minecraft:stone",Map.of());
    private static final Box BODY=new Box(-.3,0,-.3,.3,1.8,.3);
    private static TerrainMeshFrame.Cell cell(long key,Vec3d min,Vec3d ax,Vec3d ay,Vec3d az,Vec3d size,Box... collision) {
        return new TerrainMeshFrame.Cell(key,0,"test",new Vec3d(0,0,0),min,size,ax,ay,az,0xffffff,0,1f,List.of(collision));
    }
    private static TerrainMeshFrame.Cell faded(TerrainMeshFrame.Cell c){return new TerrainMeshFrame.Cell(c.key(),c.material(),c.landmarkId(),c.sourceMin(),c.min(),c.size(),c.axisX(),c.axisY(),c.axisZ(),c.color(),c.light(),0f,c.collision());}
    private static final Box UNIT=new Box(0,0,0,1,1,1);
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),List.of(cells));
    }
    private static Vec3d move(MeshCollision.Index i,Vec3d wanted) {
        return MeshCollision.move(i,BODY,wanted,true,0,false); // airborne flight: no stepping, pure sweep/slide
    }
    /** Thin distorted wall: sheared axisX tilts it, local collision stays a model-coordinate slab.
     * 32 blocks deep in z so fast diagonal motion must slide on its face, never round its edge. */
    private static TerrainMeshFrame.Cell shearedWall(long key,double x) {
        return cell(key,new Vec3d(x,0,-8),new Vec3d(1,.4,0),new Vec3d(0,3,0),new Vec3d(0,0,32),new Vec3d(1,3,32),new Box(.45,0,0,.55,1,1));
    }
    private static void highSpeedThroughDistortedWalls() {
        var frame=frame(shearedWall(1,10),shearedWall(2,20),shearedWall(3,30));
        var index=new MeshCollision.Index(frame);
        // 40 blocks in one call: piece-split swept SAT must stop at the FIRST displayed surface.
        Vec3d result=move(index,new Vec3d(40,0,0));
        check(result.x>9.8 && result.x<10.45,"fast flight stops before the first sheared wall, never tunnels through it: "+result);
        check(result.y>-.001 && result.y<.001,"shear does not inject vertical drift: "+result);
        check(MeshCollision.bodyClear(frame,BODY.offset(result)),"resting position is not embedded in the displayed mesh");
        // Repeated identical motion is idempotent: no slow leak through the wall over time.
        Vec3d again=MeshCollision.move(index,BODY.offset(result),new Vec3d(40,0,0),true,0,false);
        check(again.lengthSquared()<1e-4,"continued fast flight cannot leak past the held contact: "+again);
        check(result.x+again.x<10.45,"absolute endpoint stays on the near side of the wall");
        // Diagonal fast motion into the wall slides along it instead of corner-tunnelling.
        var diagonal=new MeshCollision.Index(frame(shearedWall(4,10)));
        Vec3d slid=move(diagonal,new Vec3d(20,0,12));
        check(slid.x<10.45 && slid.z>11.9,"blocked horizontal motion keeps sliding along the distorted surface: "+slid);
        check(MeshCollision.bodyClear(frame(shearedWall(4,10)),BODY.offset(slid)),"slid endpoint stays clear");
    }
    private static void longRayThroughDisplayedTerrain() {
        // Diagonal 128^3 ray: its swept query previously overflowed the broadphase budget and silently
        // reported "clear" straight through displayed terrain. It must now be blocked.
        var frame=frame(cell(5,new Vec3d(64,64,64),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),new Vec3d(1,1,1),UNIT));
        var index=new MeshCollision.Index(frame);
        check(!index.clearRay(new Vec3d(0,0,0),new Vec3d(128,128,128)),"long semantic/interaction ray cannot report clear through displayed terrain");
        check(index.clearRay(new Vec3d(0,0,0),new Vec3d(40,40,40)),"the same long ray stays clear where no geometry is displayed");
        check(!index.clearRay(new Vec3d(0,0,0),new Vec3d(66,66,66)),"short ray through the same wall is blocked");
        // Vertical long fall ray through a mid-air sheet is blocked too.
        var sheet=frame(cell(6,new Vec3d(-8,50,-8),new Vec3d(16,0,0),new Vec3d(0,1,0),new Vec3d(0,0,16),new Vec3d(16,1,16),UNIT));
        check(!new MeshCollision.Index(sheet).clearRay(new Vec3d(0,0,0),new Vec3d(0,128,0)),"vertical ray through a displayed sheet is blocked");
        // Fully faded cells are invisible; render/collision parity keeps their region ray-clear.
        var faded=frame(faded(cell(7,new Vec3d(-8,50,-8),new Vec3d(16,0,0),new Vec3d(0,1,0),new Vec3d(0,0,16),new Vec3d(16,1,16),UNIT)));
        check(new MeshCollision.Index(faded).clearRay(new Vec3d(0,0,0),new Vec3d(0,128,0)),"faded-out cells are not collision and do not block rays");
    }
    private static void fallOntoDistortedFloor() {
        // Flat merged slab (16x16 unit collision under long axes, like compacted production nodes).
        var flat=cell(8,new Vec3d(-8,-1,-8),new Vec3d(16,0,0),new Vec3d(0,1,0),new Vec3d(0,0,16),new Vec3d(16,1,16),UNIT);
        var flatFrame=frame(flat);var flatIndex=new MeshCollision.Index(flatFrame);
        var body=new Box(-4,2,-4,-3.7,3.8,-3.7);
        Vec3d landed=MeshCollision.move(flatIndex,body,new Vec3d(0,-12,0),false,.6,false);
        double bottom=body.minY+landed.y;
        check(bottom>-.001 && bottom<.05,"terminal-velocity fall lands on the displayed slab, never tunnels: bottom="+bottom);
        Vec3d settled=landed;
        for(int tick=0;tick<40;tick++) {
            settled=MeshCollision.move(flatIndex,body.offset(settled),new Vec3d(0,-3,0),false,.6,true).add(settled);
            double b=body.minY+settled.y;
            check(b>-.001 && b<.05,"repeated gravity ticks never sink through the displayed floor: bottom="+b);
            check(MeshCollision.ground(flatIndex,body.offset(settled)).isPresent(),"settled body keeps real ground contact");
        }
        // Tilted distorted slab: landing follows the sheared surface and never embeds.
        var tilted=cell(10,new Vec3d(-8,-1,-8),new Vec3d(8,.5,0),new Vec3d(0,1,0),new Vec3d(0,0,16),new Vec3d(8,1,16),UNIT);
        var tiltFrame=frame(tilted);var tiltIndex=new MeshCollision.Index(tiltFrame);
        Vec3d tiltLand=MeshCollision.move(tiltIndex,body,new Vec3d(0,-12,0),false,.6,false);
        double tiltBottom=body.minY+tiltLand.y;
        check(tiltBottom>.1 && tiltBottom<.4,"fall lands on the tilted distorted surface, never through it: bottom="+tiltBottom);
        check(MeshCollision.ground(tiltIndex,body.offset(tiltLand)).isPresent(),"tilted floor still reads as ground with an upward normal");
        Vec3d slide=tiltLand;
        for(int tick=0;tick<10;tick++) {
            slide=MeshCollision.move(tiltIndex,body.offset(slide),new Vec3d(0,-3,0),false,.6,true).add(slide);
            check(MeshCollision.bodyClear(tiltFrame,body.offset(slide)),"sliding down the tilt never embeds in the displayed mesh");
        }
    }
    private static void sneakEdgeOnDistortedFloor() {
        var floor=cell(9,new Vec3d(-8,-1,-8),new Vec3d(16,0,0),new Vec3d(0,1,0),new Vec3d(0,0,16),new Vec3d(16,1,16),UNIT);
        var frame=frame(floor);var index=new MeshCollision.Index(frame);
        var body=new Box(7.4,0,-.3,8,1.8,.3); // standing exactly at the mesh edge
        check(MeshCollision.ground(index,body).isPresent(),"edge body starts supported by the displayed floor");
        Vec3d kept=MeshCollision.sneak(index,body,new Vec3d(2,0,0),.6);
        // The body may hang until its inner corner leaves the mesh: 8 - 7.4 - 0.05 trim quantum = 0.55.
        check(kept.x>=.5 && kept.x<=.56,"sneaking trims motion to the real mesh edge instead of walking off: "+kept);
        check(kept.x<2,"sneak never keeps motion that leaves the displayed floor entirely");
        Vec3d inward=MeshCollision.sneak(index,body,new Vec3d(-2,0,0),.6);
        check(inward.x<-1.9,"sneaking toward mesh interior keeps full motion: "+inward);
        Vec3d z=MeshCollision.sneak(index,body,new Vec3d(0,0,3),.6);
        check(z.x<=.06 && z.z>2.9,"perpendicular sneak across the edge still guards the off-edge axis: "+z);
    }
    public static void main(String[] args) {
        highSpeedThroughDistortedWalls();longRayThroughDisplayedTerrain();fallOntoDistortedFloor();sneakEdgeOnDistortedFloor();
        System.out.println("MeshCollisionTunnelingTest: "+checks+" checks passed");
    }
}
