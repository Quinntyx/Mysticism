package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Walking-correction jitter regressions: the void-world server verdict versus real mesh walking.
 * Vanilla 1.21.1 accepts a claimed position only when server re-simulation agrees within 0.25 blocks;
 * in a void world isPlayerNotCollidingWithBlocks is constant false, so every larger disagreement on
 * swept affine terrain teleports the player back (the reported rubber banding). These tests reproduce
 * that divergence with the shared production sweep math and pin the mesh verdict that removes it
 * while retaining server authority over penetrating claims. Headless; no server or model boot. */
public final class MeshMovementVerdictTest {
    private static int checks;
    private static void check(boolean condition,String why) { checks++; if(!condition)throw new AssertionError(why); }
    private static final double STEP_HEIGHT=0.6,GRAVITY=0.08,DRAG=0.98,JUMP=0.42,WALK=0.25;
    private static TerrainMeshFrame.Material stone(){return new TerrainMeshFrame.Material("minecraft:stone",Map.of());}
    private static Box body(double x,double footY,double z){return new Box(x-.3,footY,z-.3,x+.3,footY+1.8,z+.3);}
    private static Vec3d foot(Box b){return new Vec3d(b.getCenter().x,b.minY,b.getCenter().z);}
    private static TerrainMeshFrame.Cell cell(long key,Vec3d min,Vec3d ax,Vec3d ay,Vec3d az,float opacity) {
        return new TerrainMeshFrame.Cell(key,0,"mesh-movement-test",min,min,new Vec3d(1,1,1),ax,ay,az,0xffffff,0xf000f0,opacity,List.of(new Box(0,0,0,1,1,1)));
    }
    /** Flat floor (top y=0) plus a 1.0 ledge in front of it: higher than step height, jump required.
     * Production cells are unit voxels, so both platforms are tiled unit cells. */
    private static TerrainMeshFrame ledgeFrame() {
        var axes=new Vec3d[]{new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1)};
        var cells=new java.util.ArrayList<TerrainMeshFrame.Cell>();long key=1;
        for(int x=-2;x<2;x++)for(int z=0;z<2;z++)cells.add(cell(key++,new Vec3d(x,-1,z),axes[0],axes[1],axes[2],1f));
        for(int x=2;x<4;x++)for(int y=-1;y<1;y++)for(int z=0;z<2;z++)cells.add(cell(key++,new Vec3d(x,y,z),axes[0],axes[1],axes[2],1f));
        return new TerrainMeshFrame(1,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(stone()),cells);
    }
    /** Client tick identical in shape to vanilla grounded walking, using the production sweep. */
    private static final class Walker {
        final TerrainMeshFrame frame;Box body;Vec3d velocity=Vec3d.ZERO;boolean onGround=true;int cooldown;
        Walker(TerrainMeshFrame frame,double x,double z){this.frame=frame;body=body(x,0,z);}
        Vec3d tick(boolean jump) {
            if(onGround && cooldown==0 && jump){velocity=new Vec3d(velocity.x,JUMP,velocity.z);cooldown=10;}
            if(cooldown>0)cooldown--;
            Vec3d wanted=new Vec3d(WALK,velocity.y,0);
            Vec3d moved=MeshCollision.simulate(frame,body,wanted,false,onGround,STEP_HEIGHT);
            body=body.offset(moved);
            onGround=wanted.y<0&&moved.y>wanted.y+1e-9;
            velocity=onGround?Vec3d.ZERO:new Vec3d(velocity.x,velocity.y*DRAG-GRAVITY,0);
            return foot(body);
        }
    }
    private static void ledgeJumpReproducer() {
        var frame=ledgeFrame();
        var walker=new Walker(frame,1,1);
        Vec3d claim=foot(walker.body);boolean climbed=false;
        for(int tick=0;tick<40 && !(climbed&&walker.onGround);tick++) {
            claim=walker.tick(true);
            if(claim.y>0.9&&claim.x>2.3)climbed=true;
        }
        check(climbed,"client sweep math actually climbs the 1.0 ledge (valid grounded movement retained)");
        check(walker.onGround&&Math.abs(claim.y-1)<0.05,"client lands and stands on the ledge after the climb");
        // Server re-simulation of the landing claim: the straight delta cuts the ledge face, which is
        // higher than step height, so the one-shot swept move cannot reproduce the climbed endpoint.
        Box previous=body(1,0,1);
        Box destination=body(claim.x,claim.y,claim.z);
        Vec3d delta=claim.subtract(foot(previous));
        Vec3d serverEndpoint=foot(previous.offset(MeshCollision.simulate(frame,previous,delta,false,true,STEP_HEIGHT)));
        double divergence=serverEndpoint.distanceTo(claim);
        check(divergence>0.25,"reproduction: straight re-simulation of the ledge climb diverges beyond vanilla's 0.0625 squared 'moved wrongly' limit (divergence "+divergence+")");
        // Vanilla void-world consequence: the block verdict is constant false there, so bl4 always
        // requestTeleports. The mesh verdict accepts the collision-clear climbed endpoint instead.
        check(!MeshCollision.movementRejected(frame,previous,destination),"collision-clear climbed endpoint is accepted, not teleported back");
        check(!MeshCollision.movementRejected(frame,previous,body(claim.x,claim.y+2,claim.z)),"airborne claim above the ledge is accepted (jump arc)");
        // Server authority retained over genuinely penetrating claims from a clear previous body.
        check(MeshCollision.movementRejected(frame,previous,body(2.5,0.4,1)),"claim embedded in the ledge wall is rejected back to the server pose");
        check(MeshCollision.movementRejected(frame,previous,body(1.5,-0.5,1)),"claim under the floor is rejected");
        // Sub-tick overlap the client's own next collision tick resolves stays accepted.
        check(!MeshCollision.movementRejected(frame,previous,body(2.5,0.97,1)),"shallow 0.03 overlap within the 1/16 settle tolerance is accepted");
        // An already-overlapping body may keep claiming nearby positions (recovery, no teleport oscillation).
        check(!MeshCollision.movementRejected(frame,body(2.5,0.4,1),body(2.5,0.45,1)),"recovery claims from an already-overlapping body are accepted");
        check(!MeshCollision.movementRejected((TerrainMeshFrame)null,previous,destination),"unpublished geometry never fabricates a correction (entry window stays seamless)");
    }
    /** Ordinary grounded walking along distorted (sheared) terrain: every endpoint accepted, real progress. */
    private static void slopeWalkingSequence() {
        double theta=Math.toRadians(15),s=Math.sin(theta),c=Math.cos(theta);
        // Sheared along the walking direction: surface height rises 0.268 blocks per walked block.
        var cells=new java.util.ArrayList<TerrainMeshFrame.Cell>();long key=100;
        for(int i=0;i<16;i++)for(int k=0;k<8;k++)cells.add(cell(key++,new Vec3d(i*c,-1.4+i*s,k),new Vec3d(c,s,0),new Vec3d(-s,c,0),new Vec3d(0,0,1),1f));
        var frame=new TerrainMeshFrame(7,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(stone()),cells);
        var walker=new Walker(frame,0.5,4);
        Vec3d last=foot(walker.body);
        for(int tick=0;tick<30;tick++) {
            Vec3d before=last;
            last=walker.tick(false);
            check(!MeshCollision.movementRejected(frame,body(before.x,before.y,before.z),body(last.x,last.y,last.z)),
                    "ordinary slope-walking endpoint "+last+" is never rubber-banded (tick "+tick+")");
        }
        double surfaceY=-1.4+(last.x+s)/c*s+c,midY=-1.4+(4+s)/c*s+c;
        check(last.x>5,"walking actually progresses along the sheared slope (reached "+last.x+")");
        check(Math.abs(last.y-surfaceY)<0.35,"client stays on the tilted surface while walking (foot "+last.y+" vs surface "+surfaceY+")");
        check(MeshCollision.movementRejected(frame,body(0.5,0,4),body(4,midY-2,4)),"tunnelling under the sheared surface is still rejected");
    }
    /** Faded cells have no collision anywhere; the verdict must match the movement broadphase. */
    private static void fadedCellConsistency() {
        var axes=new Vec3d[]{new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1)};
        var frame=new TerrainMeshFrame(2,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(stone()),
                List.of(new TerrainMeshFrame.Cell(4,0,"mesh-movement-test",new Vec3d(9,-1,0),new Vec3d(9,-1,0),new Vec3d(1,1,1),
                        axes[0],axes[1],axes[2],0xffffff,0xf000f0,0f,List.of(new Box(0,0,0,2,1,2)))));
        Box previous=body(8,0,1);
        Box inside=body(10,-0.03,1);
        check(!MeshCollision.movementRejected(frame,previous,inside),"fully faded geometry does not reject claims (verdict matches collision)");
        Vec3d through=foot(previous.offset(MeshCollision.simulate(frame,previous,new Vec3d(2.5,-0.5,0),true,false,0)));
        check(through.y<0.4,"faded cells do not block movement either (shared no-collision rule)");
        check(!MeshCollision.movementRejected(frame,previous,body(10,0.5,1)),"clear endpoint near faded geometry is accepted");
    }
    private static void mixinContract() throws Exception {
        var verdict=ServerPlayNetworkHandler.class.getDeclaredMethod("isPlayerNotCollidingWithBlocks",
                WorldView.class,Box.class,double.class,double.class,double.class);
        check(verdict.getReturnType()==boolean.class,"yarn movement-verdict target descriptor unchanged");
        try (var reader=new InputStreamReader(MeshMovementVerdictTest.class.getClassLoader().getResourceAsStream("mysticism.mixins.json"),StandardCharsets.UTF_8)) {
            var config=JsonParser.parseReader(reader).getAsJsonObject();
            boolean registered=false;
            for(var entry:config.getAsJsonArray("mixins"))
                if(entry.getAsString().equals("SpiritMeshMovementMixin"))registered=true;
            check(registered,"mesh movement verdict hook must be registered in mysticism.mixins.json");
        }
        Class.forName("io.github.mysticism.mixin.SpiritMeshMovementMixin");
        check(MeshCollision.class.getDeclaredMethod("movementRejected",TerrainMeshFrame.class,Box.class,Box.class).getReturnType()==boolean.class
                &&MeshCollision.class.getDeclaredMethod("movementRejected",net.minecraft.entity.player.PlayerEntity.class,Box.class,Box.class).getReturnType()==boolean.class,
                "verdict entry points exist for the server handler hook");
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        ledgeJumpReproducer();
        slopeWalkingSequence();
        fadedCellConsistency();
        mixinContract();
        System.out.println("MeshMovementVerdictTest: "+checks+" checks passed");
    }
}
