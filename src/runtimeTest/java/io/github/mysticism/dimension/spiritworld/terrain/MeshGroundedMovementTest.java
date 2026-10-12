package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.List;
import java.util.Map;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Exercises the production movement kernels without bootstrapping Minecraft or embedding inference. */
public final class MeshGroundedMovementTest {
    private static int checks;
    private static final Box UNIT = new Box(0,0,0,1,1,1);
    private static void check(boolean value,String message) {
        checks++;
        if(!value)throw new AssertionError(message);
    }
    private static void close(double actual,double expected,double tolerance,String message) {
        check(Math.abs(actual-expected)<=tolerance,message+": "+actual+" != "+expected);
    }
    private static TerrainMeshFrame.Cell cell(long key,Vec3d origin,Vec3d x,Vec3d y,Vec3d z,
                                               float opacity,List<Box> shapes) {
        return new TerrainMeshFrame.Cell(key,0,"floor-"+key,Vec3d.ZERO,origin,new Vec3d(1,1,1),
                x,y,z,0xffffff,0,opacity,shapes);
    }
    private static TerrainMeshFrame.Cell block(long key,double x,double y,double z) {
        return cell(key,new Vec3d(x,y,z),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,List.of(UNIT));
    }
    private static MeshCollision.Index index(TerrainMeshFrame.Cell... cells) {
        return new MeshCollision.Index(new TerrainMeshFrame(1,true,"minecraft:overworld",Vec3d.ZERO,
                Vec3d.ZERO,List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cells)));
    }
    private static Box body(double x,double y,double z) {
        return new Box(x-.3,y,z-.3,x+.3,y+1.8,z+.3);
    }
    private static void supportIsGeometry() {
        Box actor=body(.5,1,.5);
        var floor=index(block(1,0,0,0));
        check(floor.ground(actor).isPresent(),"Touching real floor is support, independent of carrier AIR/onGround");
        check(floor.ground(actor.offset(0,.13,0)).isEmpty(),"A distant floor is not grounded");
        check(floor.ground(actor.offset(0,-.2,0)).isEmpty(),"Embedded body is not a fabricated floor contact");
        check(index().ground(actor).isEmpty(),"Empty frame has no support");
        check(index(block(2,1,1,0)).ground(actor).isEmpty(),"Wall is not support");
        check(index(block(1,0,0,0),block(2,.75,1,0)).ground(actor).isPresent(),
                "Wall overlap cannot mask an independent real upward floor contact");
        check(index(block(3,0,3,0)).ground(actor).isEmpty(),"Ceiling is not support");
        check(index(cell(4,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),
                0,List.of(UNIT))).ground(actor).isEmpty(),"Fully hidden mesh has no collision floor");
        check(index(cell(5,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),
                1,List.of())).ground(actor).isEmpty(),"Visible model without collision has no floor");
        var diamond=index(cell(6,Vec3d.ZERO,new Vec3d(1,0,1),new Vec3d(0,1,0),new Vec3d(-1,0,1),1,List.of(UNIT)));
        check(diamond.ground(new Box(.7,1,.1,.9,2.8,.3)).isEmpty(),"Affine AABB corner is not a block-shaped floor");
        var contact=diamond.ground(body(0,1,1)).orElseThrow();
        check(contact.cell().key()==6 && contact.normal().y>.99,"Actual affine interior owns support");
        var slab=index(cell(7,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),
                1,List.of(new Box(0,0,0,1,.5,1))));
        check(slab.ground(body(.5,.5,.5)).isPresent(),"Local collision shape, not full cell, supports feet");
        check(slab.ground(actor).isEmpty(),"Full rendered model bounds do not invent a slab floor");
    }
    private static void crouchAtEdges() {
        var floor=index(block(1,0,0,0));
        Box actor=body(.5,1,.5);
        Vec3d wanted=new Vec3d(1,-.08,0);
        Vec3d clipped=floor.sneak(actor,wanted,.6);
        check(clipped.x>0 && clipped.x<1,"Crouch backs off a real mesh ledge");
        close(clipped.y,wanted.y,0,"Crouch does not cancel gravity");
        check(floor.support(actor.offset(clipped.x,0,clipped.z),.6).isPresent(),"Clipped footprint retains real support");
        check(floor.support(actor.offset(clipped.x+.051,0,0),.6).isEmpty(),"Backoff stops at edge, not a false wall");
        Vec3d diagonal=floor.sneak(actor,new Vec3d(1,-.08,1),.6);
        check(diagonal.x>0 && diagonal.z>0 && floor.support(actor.offset(diagonal.x,0,diagonal.z),.6).isPresent(),
                "Diagonal ledge clipping retains supported footprint");
        Vec3d negative=floor.sneak(actor,new Vec3d(-1,-.08,-1),.6);
        close(negative.x,-diagonal.x,1e-12,"Negative-X edge is symmetric");
        close(negative.z,-diagonal.z,1e-12,"Negative-Z edge is symmetric");
        Vec3d jump=new Vec3d(1,.42,0);
        check(floor.sneak(actor,jump,.6).equals(jump),"Upward jump is not ledge-clipped");
        check(floor.sneak(actor.offset(0,2,0),wanted,.6).equals(wanted),"Stale onGround cannot clip an airborne actor");
        check(index().sneak(actor,wanted,.6).equals(wanted),"Missing geometry cannot create crouch support");
        var raised=index(block(1,0,0,0),block(2,1,-.4,0));
        check(raised.sneak(actor,wanted,.6).equals(wanted),"Real lower step within step height permits crouch travel");
        check(raised.sneak(actor,wanted,0).x<1,"Zero step height cannot borrow a fabricated .6-block reach");
        var tooLow=index(block(1,0,0,0),block(2,1,-.64,0));
        check(tooLow.sneak(actor,wanted,.6).x<1,"Gravity cannot extend crouch's safe-drop distance");
        // Repeated edge pressure must settle, not alternate between trimming and falling.
        for(int tick=0;tick<100;tick++) {
            Vec3d request=floor.sneak(actor,new Vec3d(.1,-.08,0),.6);
            Vec3d movement=floor.move(actor,request,false,.6);
            actor=actor.offset(movement);
            check(floor.ground(actor).isPresent(),"Crouch edge pressure lost support at tick "+tick);
            close(actor.minY,1,2e-5,"Crouch edge pressure changed resting height");
        }
    }
    private static void slopedRestAndWalking() {
        var slope=index(cell(1,Vec3d.ZERO,new Vec3d(4,2,0),new Vec3d(0,1,0),new Vec3d(0,0,4),1,List.of(UNIT)));
        Box actor=body(.5,1.4,2);
        var contact=slope.ground(actor).orElseThrow();
        check(contact.normal().x<-.4 && contact.normal().y>.89,"Slope contact is the affine normal, not block up");
        Box resting=actor;
        for(int tick=0;tick<400;tick++) {
            Vec3d movement=slope.move(resting,new Vec3d(0,-.08,0),false,.6);
            close(movement.horizontalLengthSquared(),0,1e-16,"Gravity cannot cause grounded lateral jitter");
            resting=resting.offset(movement);
            check(slope.ground(resting).isPresent(),"Resting slope contact flickered");
        }
        close(resting.minY,actor.minY,1e-5,"Grounded actor drifted vertically");
        Vec3d uphill=new Vec3d(.1,-.08,0),downhill=new Vec3d(-.1,-.08,0);
        check(slope.sneak(actor,uphill,.6).equals(uphill),"Crouch can walk up a real slope, not just flat blocks");
        check(slope.sneak(actor,downhill,.6).equals(downhill),"Crouch can walk down a real slope");
        Box walking=actor;
        for(int tick=0;tick<24;tick++) {
            Vec3d request=slope.sneak(walking,uphill,.6);
            close(request.x,uphill.x,1e-8,"Uphill crouch must not stutter/back off on interior slope");
            Vec3d moved=slope.move(walking,request,false,.6);
            close(moved.x,uphill.x,1e-8,"Uphill crouch must preserve horizontal movement");
            walking=walking.offset(moved);
            check(slope.ground(walking).isPresent(),"Uphill crouch must stay supported");
        }
        // At the sloped ledge, gravity must not create X/Z after the sneak kernel has trimmed it.
        for(int tick=0;tick<80;tick++) {
            Vec3d request=slope.sneak(walking,uphill,.6);
            Vec3d moved=slope.move(walking,request,false,.6);
            close(moved.x,request.x,1e-8,"Gravity undid crouch's sloped edge clipping");
            walking=walking.offset(moved);
            check(slope.ground(walking).isPresent(),"Sloped crouch ledge lost actual support");
        }
        Vec3d up=slope.move(actor,uphill,false,.6);
        close(up.x,.1,1e-6,"Uphill walking preserves requested horizontal intent");
        close(up.y,.05,2e-5,"Uphill walking follows the real slope");
        check(slope.ground(actor.offset(up)).isPresent(),"Uphill endpoint remains on mesh");
        Vec3d down=slope.move(actor,new Vec3d(-.1,-.08,0),false,.6);
        close(down.x,-.1,1e-6,"Downhill walking does not gain gravity-induced X movement");
        close(down.y,-.05,2e-5,"Downhill walking follows the real slope");
        check(slope.ground(actor.offset(down)).isPresent(),"Downhill endpoint remains on mesh");
        Vec3d jump=new Vec3d(0,.42,0);
        check(slope.move(actor,jump,false,.6).equals(jump),"Ground support does not stick a jump to a slope");
        Vec3d flight=slope.move(actor,new Vec3d(0,-.08,0),true,.6);
        check(flight.horizontalLengthSquared()>1e-5,"Free-flight collision keeps its tangential slide");
        Vec3d falling=new Vec3d(.2,-.08,0);
        check(slope.move(actor.offset(0,4,0),falling,false,.6).equals(falling),"Airborne walking has no invented floor constraint");
    }
    private static void independentFramesAndOrdinaryCollision() {
        var left=index(block(1,0,0,0));
        var right=index(block(1,8,0,0));
        Box actor=body(.5,1,.5);
        check(left.ground(actor).isPresent() && right.ground(actor).isEmpty(),"Observers never share projected support");
        var wall=index(block(1,0,0,0),block(2,1,1,0),block(3,1,2,0));
        Vec3d stopped=wall.move(actor,new Vec3d(1,-.08,0),false,.6);
        check(stopped.x<.201 && stopped.x>.19,"Ground constraint must still collide with real walls");
        var step=index(block(1,0,0,0),cell(2,new Vec3d(1,0,0),new Vec3d(1,0,0),new Vec3d(0,1,0),
                new Vec3d(0,0,1),1,List.of(new Box(0,0,0,1,1.5,1))));
        Vec3d stepped=step.move(actor,new Vec3d(.8,-.08,0),false,.6);
        close(stepped.x,.8,2e-5,"Real half-block step remains traversable");
        close(stepped.y,.5,2e-5,"Step lands on actual shape");
        check(step.ground(actor.offset(stepped)).isPresent(),"Step endpoint has real support");
        Box embedded=body(.75,1,.5);
        Vec3d recovered=step.move(embedded,new Vec3d(.8,-.08,0),false,.6);
        close(recovered.x,.8-.05001,2e-5,"Step choice cannot discard the initial depenetration correction");
        close(recovered.y,.5,2e-5,"Depenetration and step share the same start body");
        check(step.ground(embedded.offset(recovered)).isPresent(),"Recovered step must land on real shape");
        check(step.sneak(actor,new Vec3d(.8,-.08,0),.6).x==.8,"Crouch permits a reachable real step");
        Vec3d unstepped=step.move(actor,new Vec3d(.8,-.08,0),false,0);
        check(unstepped.x<.201,"Disabled step height must not climb the shape");
        Vec3d falling=new Vec3d(.2,-.08,.1);
        check(index().move(actor,falling,false,.6).equals(falling),"Empty frame never behaves like a carrier floor");
    }
    public static void main(String[] args) {
        supportIsGeometry();
        crouchAtEdges();
        slopedRestAndWalking();
        independentFramesAndOrdinaryCollision();
        System.out.println("MeshGroundedMovementTest passed: "+checks+" checks");
    }
}
