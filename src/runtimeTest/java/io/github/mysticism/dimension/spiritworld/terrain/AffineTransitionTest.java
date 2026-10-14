package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Continuous (not endpoint-only) affine safety, with collision SAT as an independent sampled oracle. */
public final class AffineTransitionTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final Box UNIT=new Box(0,0,0,1,1,1),BODY=new Box(.2,1,.2,.8,2.8,.8);
    private static final Vec3d X=new Vec3d(1,0,0),Y=new Vec3d(0,1,0),Z=new Vec3d(0,0,1);
    private static final TerrainMeshFrame.Material STONE=new TerrainMeshFrame.Material("minecraft:stone",Map.of());
    private static TerrainMeshFrame.Cell cell(Vec3d min,Vec3d x,Vec3d y,Vec3d z) {
        return new TerrainMeshFrame.Cell(1,0,"owner",Vec3d.ZERO,min,new Vec3d(1,1,1),x,y,z,
                0xffffff,0,1,List.of(UNIT));
    }
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell c) {
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),List.of(c));
    }
    private static TerrainMeshFrame.Cell interpolate(TerrainMeshFrame.Cell a,TerrainMeshFrame.Cell b,double t) {
        return cell(a.min().lerp(b.min(),t),a.axisX().lerp(b.axisX(),t),
                a.axisY().lerp(b.axisY(),t),a.axisZ().lerp(b.axisZ(),t));
    }
    private static void tiltedRestingFloor() {
        double slope=Math.tan(Math.toRadians(10));
        var from=cell(new Vec3d(-.1,-.9*slope,0),new Vec3d(1,slope,0),Y,Z);
        var to=cell(new Vec3d(-.1,0,0),X,Y,Z);
        check(from.bounds().intersects(BODY.expand(-1e-5)),"review fixture has conservative AABB overlap");
        check(MeshCollision.bodyClear(frame(from),BODY),"review fixture has no actual affine penetration");
        check(MeshCollision.transitionClear(frame(from),frame(to),BODY),
                "safe tilted resting contact must pass the entire guarded leveling transition");
        for(int i=0;i<=100;i++)
            check(MeshCollision.bodyClear(frame(interpolate(from,to,i/100.0)),BODY),
                    "independent SAT oracle confirms safe interpolated floor "+i);
        check(MeshCollision.transitionClear(frame(to),frame(from),BODY),"safe tilt proof also works in reverse");
    }
    private static void obstructionsStayBlocked() {
        var low=cell(new Vec3d(0,-2,0),X,Y,Z);
        var high=cell(new Vec3d(0,4,0),X,Y,Z);
        check(MeshCollision.bodyClear(frame(low),BODY) && MeshCollision.bodyClear(frame(high),BODY),
                "moving obstruction has clear endpoints");
        check(!MeshCollision.bodyClear(frame(interpolate(low,high,.5)),BODY),"obstruction crosses body between endpoints");
        check(!MeshCollision.transitionClear(frame(low),frame(high),BODY),"clear endpoints cannot permit swept collision");
        check(!MeshCollision.transitionClear(frame(high),frame(low),BODY),"opposite crossing remains blocked");
        var farLow=cell(new Vec3d(0,-20,0),X,Y,Z);
        var farHigh=cell(new Vec3d(0,20,0),X,Y,Z);
        check(!MeshCollision.transitionClear(frame(farLow),frame(farHigh),BODY),
                "far endpoint bounds cannot cull a moving surface sweeping through the local body");
        var penetrating=cell(new Vec3d(0,.2,0),X,Y,Z);
        check(!MeshCollision.transitionClear(frame(low),frame(penetrating),BODY),"penetrating arrival blocked");
        check(!MeshCollision.transitionClear(frame(penetrating),frame(low),BODY),"penetrating departure is not certified");
        // Neither endpoint normal points consistently away from the body across this reflection.
        var flip=cell(new Vec3d(0,4,0),X,Y.multiply(-1),Z);
        check(!MeshCollision.transitionClear(frame(low),frame(flip),BODY),"degenerate/flipping normals never grant clearance");
        var empty=new TerrainMeshFrame(0,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,List.of(STONE),List.of());
        check(!MeshCollision.transitionClear(empty,frame(penetrating),BODY),"newly appearing obstruction stays blocked");
    }
    private static TerrainMeshFrame.Cell randomCell(Random random) {
        // Rotation plus shear, translation and scale: endpoints need not be orthogonal.
        double angle=random.nextDouble()*Math.PI*2,c=Math.cos(angle),s=Math.sin(angle);
        return cell(new Vec3d(random.nextDouble()*5-2,random.nextDouble()*5-2,random.nextDouble()*3-1),
                new Vec3d(c,s,0).multiply(.2+random.nextDouble()*2),
                new Vec3d(-s,c,.2*(random.nextDouble()-.5)).multiply(.2+random.nextDouble()*2),
                new Vec3d(.3*(random.nextDouble()-.5),.3*(random.nextDouble()-.5),.2+random.nextDouble()*2));
    }
    private static void certificatesAgainstIndependentOracle() {
        Random random=new Random(629041);int certified=0,ambiguous=0;
        for(int trial=0;trial<300;trial++) {
            var from=randomCell(random);var to=randomCell(random);
            if(!MeshCollision.transitionClear(frame(from),frame(to),BODY))continue;
            certified++;
            if(from.bounds().union(to.bounds()).intersects(BODY.expand(-1e-5)))ambiguous++;
            for(int sample=0;sample<=32;sample++)
                check(MeshCollision.bodyClear(frame(interpolate(from,to,sample/32.0)),BODY),
                        "continuous certificate never admits sampled penetration: "+trial+"/"+sample);
        }
        check(certified>0 && ambiguous>0,"oracle exercises both broad-phase and actual affine certificates");
    }
    public static void main(String[] args) {
        tiltedRestingFloor();obstructionsStayBlocked();certificatesAgainstIndependentOracle();
        System.out.println("AffineTransitionTest: "+checks+" checks passed");
    }
}
