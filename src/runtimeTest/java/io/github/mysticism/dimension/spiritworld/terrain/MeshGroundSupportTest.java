package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Player grounding must follow the actual distorted custom mesh, never a vanilla block-shaped
 * stand-in. Every discriminator here fails if collision degrades to the cell's world AABB, the
 * undeformed unit cube, or a client-claimed vanilla flag: sheared/rotated cells must ground the
 * body only over their true affine footprint, with the true tilted surface normal and height. */
public final class MeshGroundSupportTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value)throw new AssertionError(why); }
    private static void close(double a,double b,double tolerance,String why) {
        checks++; if(Math.abs(a-b)>tolerance)throw new AssertionError(why+" (expected ~"+b+", got "+a+")");
    }

    private static final TerrainMeshFrame.Material STONE = new TerrainMeshFrame.Material("minecraft:stone", Map.of());

    private static TerrainMeshFrame.Cell cell(long key,Vec3d min,Vec3d axisX,Vec3d axisY,Vec3d axisZ,float opacity) {
        return new TerrainMeshFrame.Cell(key,0,"landmark-a",new Vec3d(0,0,0),min,new Vec3d(1,1,1),
                axisX,axisY,axisZ,0xFF88CC00,0,opacity,List.of(new Box(0,0,0,1,1,1)));
    }
    private static MeshCollision.Index index(TerrainMeshFrame.Cell... cells) {
        return new MeshCollision.Index(new TerrainMeshFrame(1,false,"minecraft:overworld",
                Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),List.of(cells)));
    }
    private static final Vec3d UNIT=new Vec3d(1,0,0),UP=new Vec3d(0,1,0),DEPTH=new Vec3d(0,0,1);

    /** The exact probe the support/walk-acquisition path performs, with the observed contact surface. */
    private static MeshCollision.Hit contact(MeshCollision.Index i,Box body) {
        return MeshCollision.ground(i,body).orElse(null);
    }
    private static double contactBottom(MeshCollision.Hit hit,Box body) {
        // The probe starts 0.025 above the body bottom and sweeps 0.15 down over hit.time.
        return body.minY+.025-.15*hit.time();
    }

    private static void flatFloor() {
        var i=index(cell(1,new Vec3d(0,64,0),UNIT,UP,DEPTH,1f));
        Box body=new Box(.2,65.1,.2,.8,66.9,.8);
        var hit=contact(i,body);
        check(hit!=null,"a body above a visible flat mesh floor measures support");
        close(hit.normal().y,1,1e-6,"flat floor contact carries the up normal");
        close(contactBottom(hit,body),65,1e-9,"flat floor grounds the feet at the actual mesh surface");
        check(hit.cell().key()==1L,"the hit reports the producing mesh cell");
        check(MeshCollision.supported(i,body),"supported() matches the ground verdict for movement gates");
        check(!MeshCollision.supported(i,new Box(.2,65.4,.2,.8,67.2,.8)),
                "a body whose swept probe cannot reach the floor is not grounded");
    }

    private static void shearedFootprintIsNotTheBoundingBox() {
        // axisY sheared toward -x: the horizontal top face sits at y=65 but spans x in [-.3,.7],
        // while the cell's world AABB spans x in [-.3,1]. Grounding over x in [.8,1] is the
        // block-shaped stand-in behavior; the real mesh has only a leaning wall there.
        var i=index(cell(1,new Vec3d(0,64,0),UNIT,new Vec3d(-.3,1,0),DEPTH,1f));
        Box overhang=new Box(.8,64.8,.2,1.4,66.6,.8);
        check(contact(i,overhang)==null,
                "no support over the sheared cell's AABB-only overhang (a block stand-in would ground there)");
        check(!MeshCollision.supported(i,overhang),"supported() refuses the AABB-only overhang too");
        Box overTop=new Box(0,65.1,.2,.6,66.9,.8);
        var hit=contact(i,overTop);
        check(hit!=null,"the true top-face footprint grounds the body");
        close(hit.normal().y,1,1e-6,"x-sheared cell keeps a horizontal top-face normal");
        close(contactBottom(hit,overTop),65,1e-9,"sheared cell grounds at the true affine surface height");
    }

    private static void tiltedTopCarriesTrueNormalAndHeight() {
        // axisX tilted upward: the top face rises with x (y = 65 + .4x) and its world normal is
        // (-.371,.928,0) — neither the undeformed cube (flat y=65, normal up) nor the world AABB
        // (flat y=65.4) is acceptable grounding.
        var i=index(cell(1,new Vec3d(0,64,0),new Vec3d(1,.4,0),UP,DEPTH,1f));
        Box body=new Box(0,65.1,.2,.2,66.9,.8);
        var hit=contact(i,body);
        check(hit!=null,"a body above a tilted mesh top measures support");
        check(hit.normal().y>.3,"the tilted surface still counts as generic ground contact");
        check(hit.normal().y<.99,"a 21.8-degree tilted surface refuses walk-acquisition-grade support");
        close(hit.normal().y,.928477,.002,"the contact normal is the true tilted top-face normal");
        close(contactBottom(hit,body),65.08,.004,"the swept body first touches the tilted top at its highest footprint point");
    }

    private static void rotatedFootprintFollowsTheDiamondNotTheBox() {
        // Unit cube yawed 45 degrees: world AABB spans [-.707,.707]^2 but the solid footprint is the
        // diamond z>=x, z<=1.414-x. Block-shaped stand-ins ground the AABB corners; the mesh must not.
        double r=Math.sqrt(.5);
        var i=index(cell(1,new Vec3d(0,64,0),new Vec3d(r,0,r),UP,new Vec3d(-r,0,r),1f));
        Box aabbCorner=new Box(.4,64.9,.05,.7,66.7,.35);
        check(contact(i,aabbCorner)==null,
                "no support over the rotated cell's AABB corner outside the true diamond footprint");
        Box diamond=new Box(.05,65.1,.4,.25,66.9,.6);
        var hit=contact(i,diamond);
        check(hit!=null,"the true diamond footprint grounds the body");
        close(hit.normal().y,1,1e-6,"yaw rotation keeps an up-facing top normal");
        close(contactBottom(hit,diamond),65,1e-9,"rotated cell grounds at its actual surface height");
    }

    private static void fadedTerrainIsNotSupport() {
        var i=index(cell(1,new Vec3d(0,64,0),UNIT,UP,DEPTH,0f));
        check(contact(i,new Box(.2,65.1,.2,.8,66.9,.8))==null,
                "a fully faded region has neither rendering nor collision support");
    }

    private static void steepWallIsNotSupport() {
        var i=index(cell(1,new Vec3d(0,64,0),UNIT,UP,DEPTH,1f));
        // Body pressed against the cell's +x side face: the swept probe can only contact that vertical
        // wall, whose normal carries no up component.
        Box beside=new Box(1.01,64.9,.2,1.61,66.7,.8);
        check(contact(i,beside)==null,"a vertical wall contact is not ground support");
        check(!MeshCollision.supported(i,beside),"supported() refuses wall contact for movement gates");
    }

    /** What a vanilla block-shaped stand-in (sweep against the cell's conservative world AABB) would report. */
    private static boolean blockShapedStandInSupport(TerrainMeshFrame.Cell cell,Box body) {
        Box swept=body.offset(0,.025,0).stretch(new Vec3d(0,-.15,0)).expand(1e-5);
        return cell.bounds().intersects(swept);
    }

    /** Pins WHY these are mesh discriminators: the block-shaped stand-in verdict differs on each case. */
    private static void blockShapedStandInsWouldDiffer() {
        var sheared=index(cell(1,new Vec3d(0,64,0),UNIT,new Vec3d(-.3,1,0),DEPTH,1f)).frame.cells().getFirst();
        Box overhang=new Box(.8,64.8,.2,1.4,66.6,.8);
        check(blockShapedStandInSupport(sheared,overhang) && contact(index(sheared),overhang)==null,
                "discriminator: an AABB stand-in grounds the sheared overhang, the real mesh does not");

        var tilted=index(cell(2,new Vec3d(0,64,0),new Vec3d(1,.4,0),UP,DEPTH,1f)).frame.cells().getFirst();
        Box tiltBody=new Box(0,65.1,.2,.2,66.9,.8);
        check(blockShapedStandInSupport(tilted,tiltBody),
                "discriminator: an AABB stand-in reports embedded contact on the tilted cell");
        var tiltHit=contact(index(tilted),tiltBody);
        check(tiltHit!=null && tiltHit.time()>0.1,
                "the real mesh grounds the tilted top by swept contact, not time-0 AABB embedding");

        double r=Math.sqrt(.5);
        var rotated=index(cell(3,new Vec3d(0,64,0),new Vec3d(r,0,r),UP,new Vec3d(-r,0,r),1f)).frame.cells().getFirst();
        Box corner=new Box(.4,64.9,.05,.7,66.7,.35);
        check(blockShapedStandInSupport(rotated,corner) && contact(index(rotated),corner)==null,
                "discriminator: an AABB stand-in grounds the rotated AABB corner, the real mesh does not");

        var flat=index(cell(4,new Vec3d(0,64,0),UNIT,UP,DEPTH,1f)).frame.cells().getFirst();
        Box flatBody=new Box(.2,65.1,.2,.8,66.9,.8);
        check(blockShapedStandInSupport(flat,flatBody) && contact(index(flat),flatBody)!=null,
                "aligned flat cells agree between mesh and block stand-in (no false discrimination)");
    }

    private static MeshCollision.MotionState state(Box body,boolean vanillaOnGround,boolean flying,boolean sneaking) {
        return new MeshCollision.MotionState(body,.6,flying,sneaking,vanillaOnGround);
    }
    private static void sameMovement(Vec3d actual,Vec3d expected,String why) {
        close(actual.x,expected.x,1e-9,why+" (x)");
        close(actual.y,expected.y,1e-9,why+" (y)");
        close(actual.z,expected.z,1e-9,why+" (z)");
    }
    private static TerrainMeshFrame.Cell lowObstacle() {
        return cell(20,new Vec3d(1,1,0),UNIT,new Vec3d(0,.4,0),DEPTH,1f);
    }
    private static TerrainMeshFrame.Cell wideFloor() {
        return cell(21,new Vec3d(-1,0,-1),new Vec3d(4,0,0),UP,new Vec3d(0,0,3),1f);
    }

    private static void unsupportedStepIgnoresTrueVanillaFlag() {
        var i=index(lowObstacle());
        Box body=new Box(0,1,0,.6,2.8,.6);
        var claimedGround=state(body,true,false,false);
        check(claimedGround.vanillaOnGround(),"adversarial step fixture carries a TRUE vanilla ground flag");
        check(!MeshCollision.supported(i,body),"adversarial step fixture has a NEGATIVE mesh support verdict");
        Vec3d wanted=new Vec3d(1,0,0);
        Vec3d moved=MeshCollision.move(i,claimedGround,wanted);
        check(moved.x>.39 && moved.x<.41,"unsupported player hits the obstacle instead of stepping onto it");
        close(moved.y,0,1e-9,"unsupported player is not lifted onto a low obstacle");
        sameMovement(moved,MeshCollision.move(i,state(body,false,false,false),wanted),
                "changing only the vanilla flag cannot enable unsupported step-up");
    }

    private static void supportedStepAndSweptLandingStillWork() {
        var i=index(wideFloor(),lowObstacle());
        Vec3d wanted=new Vec3d(1,0,0);
        Box grounded=new Box(0,1,0,.6,2.8,.6);
        check(MeshCollision.supported(i,grounded),"grounded step fixture has real mesh support");
        Vec3d stepped=MeshCollision.move(i,state(grounded,false,false,false),wanted);
        close(stepped.x,1,1e-6,"actual mesh support allows step-up even with a FALSE vanilla flag");
        close(grounded.minY+stepped.y,1.4,2e-5,"step lands on the true mesh obstacle top");
        Vec3d flying=MeshCollision.move(i,state(grounded,true,true,false),wanted);
        check(flying.x<.41,"flying players slide, not step, even with mesh support and a TRUE vanilla flag");

        Box falling=new Box(0,1.3,0,.6,3.1,.6);
        check(!MeshCollision.supported(i,falling),"falling step fixture is beyond the short support probe");
        Vec3d fall=new Vec3d(1,-.5,0);
        check(i.sweep(falling,fall).isPresent(),"falling fixture has a swept collision");
        Vec3d landed=MeshCollision.move(i,state(falling,false,false,false),fall);
        close(landed.x,1,1e-6,"a genuine swept floor landing also allows stepping");
        close(falling.minY+landed.y,1.4,2e-5,"swept landing step finishes on the obstacle top");
    }

    private static void upwardWallSlideIsNotALanding() {
        // This leaning side's normal has y ~= .196: horizontal collision deflects motion upward,
        // but it is NOT an up-facing ground contact. The former result.y>wanted.y test allowed a step.
        var i=index(cell(30,new Vec3d(.8,.6,0),UNIT,new Vec3d(.2,1,0),DEPTH,1f));
        Box body=new Box(0,1.2,0,.6,3,.6);
        Vec3d wanted=new Vec3d(1,-.1,0);
        check(!MeshCollision.supported(i,body),"leaning-wall fixture has no mesh support");
        var hit=i.sweep(body,wanted).orElseThrow();
        check(hit.normal().y>0 && hit.normal().y<=.3,"leaning-wall contact is upward but not ground");
        Vec3d moved=MeshCollision.move(i,state(body,true,false,false),wanted);
        check(moved.y>wanted.y+1e-5,"wall slide really clips the downward delta (old landing shortcut discriminator)");
        check(moved.x<.5,"upward wall sliding does not authorize an unsupported step");
    }

    private static void unsupportedSneakIgnoresTrueVanillaFlag() {
        var i=index();
        Box body=new Box(0,1,0,.6,2.8,.6);
        var claimedGround=state(body,true,false,true);
        check(claimedGround.vanillaOnGround(),"adversarial sneak fixture carries a TRUE vanilla ground flag");
        check(!MeshCollision.supported(i,body),"adversarial sneak fixture has a NEGATIVE mesh support verdict");
        Vec3d wanted=new Vec3d(.8,-.1,.5);
        for(MovementType type:List.of(MovementType.SELF,MovementType.PLAYER)) {
            sameMovement(MeshCollision.sneak(i,claimedGround,wanted,type),wanted,
                    "airborne sneaking cannot clip movement based on a vanilla claim ("+type+")");
            sameMovement(MeshCollision.sneak(i,state(body,false,false,true),wanted,type),wanted,
                    "airborne sneaking is independent of the vanilla flag ("+type+")");
        }
    }

    private static void supportedSneakStillProtectsMeshLedges() {
        var i=index(cell(40,Vec3d.ZERO,UNIT,UP,DEPTH,1f));
        Box body=new Box(0,1,0,.6,2.8,.6);
        Vec3d wanted=new Vec3d(1.5,0,0);
        check(MeshCollision.supported(i,body),"sneak ledge fixture has real mesh support");
        Vec3d clipped=MeshCollision.sneak(i,state(body,false,false,true),wanted,MovementType.SELF);
        check(clipped.x>0 && clipped.x<1.01,"actual mesh support protects a ledge even with a FALSE vanilla flag");
        sameMovement(MeshCollision.sneak(i,state(body,true,false,true),wanted,MovementType.SELF),clipped,
                "supported sneak clipping does not depend on the vanilla flag");
        sameMovement(MeshCollision.sneak(i,state(body,true,true,true),wanted,MovementType.SELF),wanted,
                "flying bypasses sneak ledge clipping");
        sameMovement(MeshCollision.sneak(i,state(body,true,false,false),wanted,MovementType.SELF),wanted,
                "not sneaking bypasses ledge clipping");
        sameMovement(MeshCollision.sneak(i,state(body,true,false,true),wanted,MovementType.PISTON),wanted,
                "external movement bypasses sneak ledge clipping");
        sameMovement(MeshCollision.move(null,state(body,true,false,false),wanted),wanted,
                "no mesh frame does not fabricate movement support");
        sameMovement(MeshCollision.sneak(null,state(body,true,false,true),wanted,MovementType.SELF),wanted,
                "no mesh frame does not fabricate sneak support");
    }

    public static void main(String[] args) {
        flatFloor();
        shearedFootprintIsNotTheBoundingBox();
        tiltedTopCarriesTrueNormalAndHeight();
        rotatedFootprintFollowsTheDiamondNotTheBox();
        fadedTerrainIsNotSupport();
        steepWallIsNotSupport();
        blockShapedStandInsWouldDiffer();
        unsupportedStepIgnoresTrueVanillaFlag();
        supportedStepAndSweptLandingStillWork();
        upwardWallSlideIsNotALanding();
        unsupportedSneakIgnoresTrueVanillaFlag();
        supportedSneakStillProtectsMeshLedges();
        System.out.println("MeshGroundSupportTest: "+checks+" checks passed");
    }
}
