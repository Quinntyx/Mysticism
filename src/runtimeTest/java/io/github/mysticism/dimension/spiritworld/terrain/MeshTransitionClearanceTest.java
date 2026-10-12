package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Production continuity-guard regressions; no game/server or sampled-pose acceptance stub. */
public final class MeshTransitionClearanceTest {
    private static final Box BODY=new Box(-.3,0,-.3,.3,1.8,.3);
    private static final Box FULL=new Box(0,0,0,1,1,1);
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++;if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        tiltedSupportCanFlatten();
        separatingAffineWallsCanMove();
        interiorCollisionIsNotSampledThrough();
        endpointAndNewCollisionAreHeld();
        acceptedSweepsHaveClearIntermediatePoses();
        System.out.println("Mesh transition clearance self-tests passed ("+checks+" checks)");
    }
    public static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cells));
    }
    private static TerrainMeshFrame.Cell cell(Vec3d min,Vec3d x,Vec3d y,Vec3d z,Box collision) {
        return new TerrainMeshFrame.Cell(1,0,"floor",Vec3d.ZERO,min,new Vec3d(1,1,1),x,y,z,
                -1,0,1,List.of(collision));
    }
    private static TerrainMeshFrame.Cell boxAt(double x,double y,double z) {
        return cell(new Vec3d(x,y,z),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),FULL);
    }
    /** The review's failure: a 5-degree supporting floor has clear SAT contact but its AABB
     * rises ~.0175 above the feet. Re-anchored flattening lowers the real floor safely. */
    private static void tiltedSupportCanFlatten() {
        for(double degrees:new double[]{-5,5})for(double top:new double[]{.5,1}) {
            double angle=Math.toRadians(degrees),s=Math.sin(angle),c=Math.cos(angle);
            Vec3d x=new Vec3d(c,s,0),y=new Vec3d(-s,c,0),z=new Vec3d(0,0,1);
            Vec3d sourceFeet=new Vec3d(.5,top+.3*Math.abs(s),.5);
            var tilted=cell(x.multiply(-sourceFeet.x).add(y.multiply(-sourceFeet.y)).add(z.multiply(-sourceFeet.z)),x,y,z,new Box(0,0,0,1,top,1));
            var flat=cell(sourceFeet.multiply(-1),new Vec3d(1,0,0),new Vec3d(0,1,0),z,new Box(0,0,0,1,top,1));
            check(tilted.bounds(tilted.collision().getFirst()).maxY>.017,"fixture reproduces endpoint AABB overlap");
            check(tilted.bounds().union(flat.bounds()).intersects(BODY.expand(-1e-5)),"old broad-phase-only guard rejects the fixture");
            check(MeshCollision.bodyClear(frame(tilted),BODY),"actual old affine floor clears strict body");
            check(MeshCollision.bodyClear(frame(flat),BODY),"flat floor clears strict body");
            // Even the full endpoint convex hull intrudes into the body: a single separating
            // plane cannot certify this entire rotation. The production guard must subdivide.
            Vec3d high=tilted.point(degrees>0?1:0,top,.5),low=flat.point(degrees>0?0:1,top,.5);
            double weight=((degrees>0?.2:-.2)-low.x)/(high.x-low.x);
            check(BODY.expand(-1e-5).contains(lerp(low,high,weight)),"clear rotation needs subinterval hull proofs, not one endpoint hull");
            check(new MeshCollision.Index(frame(tilted)).sweep(BODY.offset(0,.025,0),new Vec3d(0,-.15,0))
                    .filter(hit->hit.normal().y>.99).isPresent(),"fixture has real SAT ground contact");
            check(MeshCollision.transitionClear(frame(tilted),frame(flat),BODY),"supported affine floor can continuously flatten");
            for(int i=0;i<=128;i++)check(MeshCollision.bodyClear(frame(interpolate(tilted,flat,i/128.0)),BODY),"flattening stays clear");
            // Reversing the same clear path also requires an interval proof, not a contact exception.
            check(MeshCollision.transitionClear(frame(flat),frame(tilted),BODY),"clear reverse tilt is accepted");
        }
    }
    private static void separatingAffineWallsCanMove() {
        // A diagonal wall's AABB intersects the player, while the affine wall itself does not.
        var old=cell(new Vec3d(.1,0,1),new Vec3d(2,0,-2),new Vec3d(0,2,0),new Vec3d(.1,0,.1),FULL);
        var next=cell(new Vec3d(.2,0,1),old.axisX(),old.axisY(),old.axisZ(),FULL);
        check(old.bounds().intersects(BODY),"wall fixture has broad-phase overlap");
        check(MeshCollision.bodyClear(frame(old),BODY) && MeshCollision.bodyClear(frame(next),BODY),"affine wall endpoints clear");
        check(MeshCollision.transitionClear(frame(old),frame(next),BODY),"separating affine side wall may move");
    }
    private static void interiorCollisionIsNotSampledThrough() {
        // Both endpoints are outside the former five-block locality gate. Even the midpoint is
        // clear; at t=.25 this wall crosses the body. Endpoint/midpoint checks are insufficient.
        var old=boxAt(-8,.2,-.5);var next=boxAt(24,.2,-.5);
        check(MeshCollision.bodyClear(frame(old),BODY) && MeshCollision.bodyClear(frame(next),BODY),"crossing endpoints clear");
        check(MeshCollision.bodyClear(frame(interpolate(old,next,.5)),BODY),"crossing midpoint also clear");
        check(!MeshCollision.bodyClear(frame(interpolate(old,next,.25)),BODY),"interior crossing really penetrates");
        check(!MeshCollision.transitionClear(frame(old),frame(next),BODY),"continuous crossing is rejected, including distant endpoints");
        // Affine deformation, not just translation: a shearing edge crosses the player.
        var a=cell(new Vec3d(-3,.2,-.5),new Vec3d(.2,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),FULL);
        var b=cell(new Vec3d(3,.2,-.5),new Vec3d(.2,.3,0),new Vec3d(-.4,1,0),new Vec3d(0,0,1),FULL);
        check(MeshCollision.bodyClear(frame(a),BODY) && MeshCollision.bodyClear(frame(b),BODY),"sheared endpoints clear");
        check(!MeshCollision.bodyClear(frame(interpolate(a,b,.5)),BODY),"sheared intermediate solid penetrates");
        check(!MeshCollision.transitionClear(frame(a),frame(b),BODY),"sheared intermediate collision is held");
    }
    private static void endpointAndNewCollisionAreHeld() {
        var floor=boxAt(-.5,-1,-.5);var raised=boxAt(-.5,-.9,-.5);
        check(!MeshCollision.transitionClear(frame(floor),frame(raised),BODY),"raising support into feet is still rejected");
        check(!MeshCollision.transitionClear(frame(),frame(raised),BODY),"new collision penetrating the feet is rejected");
        check(MeshCollision.transitionClear(frame(),frame(floor),BODY),"new exact supporting contact is accepted");
        check(MeshCollision.transitionClear(frame(floor),frame(),BODY),"removal does not invent collision or floor");
        var thin=cell(new Vec3d(-.5,-1,-.5),floor.axisX(),floor.axisY(),floor.axisZ(),new Box(0,0,0,1,.5,1));
        var grown=cell(thin.min(),thin.axisX(),thin.axisY(),thin.axisZ(),new Box(0,0,0,1,1.1,1));
        check(!MeshCollision.transitionClear(frame(thin),frame(grown),BODY),"changed local shape cannot grow into body");
    }
    private static void acceptedSweepsHaveClearIntermediatePoses() {
        Random random=new Random(0x5aff1e);int accepted=0,rejected=0;
        for(int trial=0;trial<200;trial++) {
            var a=randomCell(random);var b=randomCell(random);
            boolean clear=MeshCollision.transitionClear(frame(a),frame(b),BODY);
            if(clear) {
                accepted++;
                // Sampling is only an independent rejection oracle in this test. Production
                // acceptance requires whole-interval separating-plane certificates.
                for(int step=0;step<=128;step++)check(MeshCollision.bodyClear(frame(interpolate(a,b,step/128.0)),BODY),"accepted affine sweep has no sampled penetration");
            } else rejected++;
        }
        check(accepted>20 && rejected>20,"property fixtures exercise both acceptance and refusal ("+accepted+"/"+rejected+")");
    }
    private static TerrainMeshFrame.Cell randomCell(Random r) {
        return cell(new Vec3d(r.nextDouble()*6-3,r.nextDouble()*4-2,r.nextDouble()*6-3),
                new Vec3d(.2+r.nextDouble(),r.nextDouble()-.5,r.nextDouble()-.5),
                new Vec3d(r.nextDouble()-.5,.2+r.nextDouble(),r.nextDouble()-.5),
                new Vec3d(r.nextDouble()-.5,r.nextDouble()-.5,.2+r.nextDouble()),FULL);
    }
    private static TerrainMeshFrame.Cell interpolate(TerrainMeshFrame.Cell a,TerrainMeshFrame.Cell b,double t) {
        return cell(lerp(a.min(),b.min(),t),lerp(a.axisX(),b.axisX(),t),lerp(a.axisY(),b.axisY(),t),lerp(a.axisZ(),b.axisZ(),t),a.collision().getFirst());
    }
    private static Vec3d lerp(Vec3d a,Vec3d b,double t) {return a.multiply(1-t).add(b.multiply(t));}
}
