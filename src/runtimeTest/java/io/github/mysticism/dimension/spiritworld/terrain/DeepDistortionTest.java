package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.net.SpiritFramePayload;
import io.github.mysticism.net.SpiritTerrainPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** Numerical regressions of the actual production distortion/frame/transport/SAT contracts.
 * Does not bootstrap Minecraft or claim a live GPU/gameplay test. */
public final class DeepDistortionTest {
    private static int checks;
    private static final Vec3d EYE=Vec3d.ZERO;
    private static final Box UNIT=new Box(0,0,0,1,1,1);
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void close(Vec3d a,Vec3d b,String why){check(a.distanceTo(b)<1e-9,why);}
    private static TerrainMeshFrame.Cell cell(Vec3d min,double side,long key) {
        return new TerrainMeshFrame.Cell(key,0,"landmark",new Vec3d(100,64,100),min,new Vec3d(side,side,side),
                new Vec3d(side,0,0),new Vec3d(0,side,0),new Vec3d(0,0,side),0xFFFFFFFF,15,1f,List.of(UNIT));
    }
    private static TerrainMeshFrame.Cell withAxes(TerrainMeshFrame.Cell c,Vec3d x,Vec3d y,Vec3d z,List<Box> shapes) {
        return new TerrainMeshFrame.Cell(c.key(),c.material(),c.landmarkId(),c.sourceMin(),c.min(),c.size(),
                x,y,z,c.color(),c.light(),c.opacity(),shapes);
    }
    private static TerrainMeshFrame.Cell warp(TerrainMeshFrame.Cell c,long tick,long seed) {
        return DeepDistortion.apply(List.of(c),false,EYE,tick,seed).getFirst();
    }
    private static Vec3d corner(TerrainMeshFrame.Cell c,Box b,int i) {
        return c.point((i&1)==0?b.minX:b.maxX,(i&2)==0?b.minY:b.maxY,(i&4)==0?b.minZ:b.maxZ);
    }
    private static double determinant(TerrainMeshFrame.Cell c){return c.axisX().dotProduct(c.axisY().crossProduct(c.axisZ()));}
    private static TerrainMeshFrame frame(long revision,boolean shallow,List<TerrainMeshFrame.Cell> cells) {
        return new TerrainMeshFrame(revision,shallow,"minecraft:overworld",EYE,EYE,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),cells);
    }
    private static void rampAndMode() {
        check(DeepDistortion.strength(0)==0 && DeepDistortion.strength(DeepDistortion.NEAR)==0,"exact near identity");
        check(DeepDistortion.strength(DeepDistortion.FULL)==1 && DeepDistortion.strength(1e6)==1,"full strength inside the visible 64-block horizon");
        double previous=-1;
        for(double d=0;d<=50;d+=.5){double s=DeepDistortion.strength(d);check(s>=previous && s>=0 && s<=1,"bounded monotonic ramp");previous=s;}
        var near=cell(new Vec3d(2,-1,2),1,1);
        check(warp(near,1000,7)==near,"near mesh is returned unmodified");
        var big=cell(new Vec3d(-30,-1,-30),60,2);
        check(warp(big,1000,7)==big,"large octree node containing the observer is protected");
        // Model is outside NEAR, but a non-unit collision shape reaches into it.
        var overhang=cell(new Vec3d(10,0,0),4,3);
        overhang=withAxes(overhang,overhang.axisX(),overhang.axisY(),overhang.axisZ(),List.of(new Box(-1,0,0,1,1,1)));
        check(warp(overhang,1000,7)==overhang,"extended collision shapes participate in near protection");
        var far=cell(new Vec3d(30,10,30),8,4);
        List<TerrainMeshFrame.Cell> input=List.of(far);
        check(DeepDistortion.apply(input,Map.of(),true,EYE,1000,7)==input,"shallow production hook bypasses distortion/provenance lookup exactly");
        check(DeepDistortion.apply(List.of(),false,EYE,1000,7).isEmpty(),"empty patch remains empty");
    }
    private static void deformsVisiblyAndPhysically() {
        var far=cell(new Vec3d(30,10,30),8,5);
        var deformed=warp(far,1000,7);
        check(deformed.point(.5,.5,.5).distanceTo(far.point(.5,.5,.5))>1,"far geometry visibly moves more than one block, within render horizon");
        check(!deformed.axisX().equals(far.axisX()) && !deformed.axisZ().equals(far.axisZ()),"real affine shear, not only translated blocks");
        check(!deformed.bounds(UNIT).equals(far.bounds(UNIT)),"physical shape bounds move with rendered columns");
        check(deformed.key()==far.key() && deformed.material()==far.material() && deformed.landmarkId().equals(far.landmarkId())
                && deformed.sourceMin().equals(far.sourceMin()) && deformed.size().equals(far.size())
                && deformed.color()==far.color() && deformed.light()==far.light() && deformed.opacity()==far.opacity()
                && deformed.collision().equals(far.collision()),"source/provenance/material/local shape data are unchanged");
        check(warp(far,1000,7).equals(deformed),"deterministic observer/time/seed");
        check(!warp(far,1000,8).equals(deformed),"independent observers have independent deformation");
        // The baked-model affine matrix used by SpiritTerrainClient must locate a fractional
        // model vertex at the same place as Cell.point, used by the SAT collision shapes.
        Vec3d x=deformed.axisX(),y=deformed.axisY(),z=deformed.axisZ(),m=deformed.min();
        var matrix=new Matrix4f().m00((float)x.x).m01((float)x.y).m02((float)x.z)
                .m10((float)y.x).m11((float)y.y).m12((float)y.z).m20((float)z.x).m21((float)z.y).m22((float)z.z)
                .m30((float)m.x).m31((float)m.y).m32((float)m.z);
        var vertex=new Vector3f(.25f,.5f,.75f).mulPosition(matrix);
        check(new Vec3d(vertex.x,vertex.y,vertex.z).distanceTo(deformed.point(.25,.5,.75))<1e-5,"render-matrix and collision vertices agree");
        // Invert the same columns as runtime support mapping, preserving fractional source pose.
        Vec3d v=deformed.point(.25,.5,.75).subtract(deformed.min());double det=determinant(deformed);
        Vec3d source=deformed.sourceMin().add(v.dotProduct(y.crossProduct(z))/det*deformed.size().x,
                v.dotProduct(z.crossProduct(x))/det*deformed.size().y,v.dotProduct(x.crossProduct(y))/det*deformed.size().z);
        close(source,far.sourceMin().add(2,4,6),"distorted physical contact still maps to exact source coordinates");
    }
    private static void sharedFacesAndProducers() {
        for(double offset:new double[]{9,25,45}) {
            var left=cell(new Vec3d(offset,0,0),4,6);
            var right=cell(new Vec3d(offset+4,0,0),4,7);
            var out=DeepDistortion.apply(List.of(left,right),false,EYE,1200,Long.MIN_VALUE);
            for(int corner=0;corner<4;corner++)close(out.get(0).point(1,corner&1,(corner>>1)&1),
                    out.get(1).point(0,corner&1,(corner>>1)&1),"all shared face corners remain joined across the radial ramp");
        }
        var coarse=cell(new Vec3d(40,0,0),8,8);
        var fine=cell(new Vec3d(48,2,4),1,9);
        var out=DeepDistortion.apply(List.of(coarse,fine),false,EYE,600,7);
        for(int i=0;i<4;i++)close(out.get(0).point(1,(2+(i&1))/8.0,(4+((i>>1)&1))/8.0),
                out.get(1).point(0,i&1,(i>>1)&1),"coarse/fine octree shared face remains joined");
        var near=cell(new Vec3d(0,-1,0),1,10);
        var far=cell(new Vec3d(30,10,30),8,11); // deliberately same ownership label
        var input=List.of(near,far);Object nearWindow=new Object(),farWindow=new Object();
        var producers=Map.of(10L,nearWindow,11L,farWindow);
        var split=DeepDistortion.apply(input,producers,false,EYE,1000,7);
        check(split.get(0)==near && !split.get(1).equals(far),"one near producer does not suppress another far producer with the same owner label");
        check(producers.get(11L)==farWindow && input.get(1)==far,"build previews do not mutate accepted geometry/provenance");
        var connected=DeepDistortion.apply(input,Map.of(10L,nearWindow,11L,nearWindow),false,EYE,1000,7);
        check(connected.equals(input),"whole connected window remains protected rather than tearing its near/far boundary");
        var across=List.of(cell(new Vec3d(-50,-2,-2),4,17),cell(new Vec3d(46,-2,-2),4,18));
        var cavern=DeepDistortion.apply(across,false,EYE,1000,7);
        check(!cavern.equals(across),"a window envelope surrounding the observer does not confuse empty cave air with nearby terrain");
    }
    private static void boundsOrientationAndContinuity() {
        var random=new Random(20260107);
        for(int trial=0;trial<200;trial++) {
            double side=1+random.nextInt(64);
            var c=cell(new Vec3d(45+random.nextInt(40),random.nextInt(64)-32,45+random.nextInt(40)),side,100+trial);
            Vec3d x=c.axisX().add(0,side*.2,0),y=c.axisY().add(0,0,side*.25),z=c.axisZ().add(side*.1,0,0);
            if(trial%2==0)x=x.negate(); // determinant sign of source projection must be preserved
            var shapes=List.of(new Box(-.5,-.2,0,.25,.5,1.25),new Box(.3,.5,.2,.9,1,.8));
            c=withAxes(c,x,y,z,shapes);
            var deformed=warp(c,random.nextInt(100000),random.nextLong());
            double ratio=determinant(deformed)/determinant(c);
            check(ratio>=.82-1e-12 && ratio<=1.18+1e-12,"no collapse/inversion even for reflected, skewed, large nodes");
            var extents=new ArrayList<>(shapes);extents.add(UNIT);
            for(Box extent:extents)for(int i=0;i<8;i++) {
                var p=corner(deformed,extent,i);
                check(Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z),"all deformed corners finite");
                check(p.distanceTo(corner(c,extent,i))<=DeepDistortion.MAX_SHIFT+1e-9,"EVERY model/shape corner obeys total displacement bound");
            }
        }
        var far=cell(new Vec3d(30,10,30),8,12);
        for(long seed:new long[]{7,Long.MIN_VALUE,Long.MAX_VALUE,0x9e3779b97f4a7c15L}) {
            var before=warp(far,10000,seed);var after=warp(far,10002,seed);
            double moved=0;for(int i=0;i<8;i++)moved=Math.max(moved,corner(before,UNIT,i).distanceTo(corner(after,UNIT,i)));
            check(moved>1e-4 && moved<.05,"two ticks animate smoothly even with full-width UUID seed");
            var hours=warp(far,250000,seed);
            for(int i=0;i<8;i++)check(corner(before,UNIT,i).distanceTo(corner(hours,UNIT,i))<=2*DeepDistortion.MAX_SHIFT+1e-9,"long-term motion remains bounded");
        }
        check(warp(far,Long.MAX_VALUE-2,Long.MAX_VALUE).equals(warp(far,Long.MAX_VALUE-2,Long.MAX_VALUE)),"large tick is reduced before floating conversion");
    }
    private static void physicalHitsAndTransitions() {
        var c=warp(cell(new Vec3d(30,10,30),8,13),1000,7);
        var index=new MeshCollision.Index(frame(1,false,List.of(c)));
        Vec3d top=c.point(.5,1,.5),normal=c.axisZ().crossProduct(c.axisX()).normalize();
        Vec3d from=top.add(normal.multiply(2)),to=top.subtract(normal.multiply(2));
        var body=new Box(from.x-1e-4,from.y-1e-4,from.z-1e-4,from.x+1e-4,from.y+1e-4,from.z+1e-4);
        var hit=index.sweep(body,to.subtract(from));
        check(hit.isPresent() && hit.get().cell()==c,"actual SAT sweep contacts the deformed surface");
        check(Math.abs(hit.orElseThrow().time()-.5)<1e-3 && hit.get().normal().dotProduct(normal)>.99999,"physical time/normal match rendered tilted face");
        check(!index.clearRay(from,to),"occlusion follows the deformed surface");
        var rigid=cell(new Vec3d(30,10,30),8,13);
        var rigidHit=new MeshCollision.Index(frame(1,false,List.of(rigid))).sweep(body,to.subtract(from));
        check(rigidHit.isEmpty() || Math.abs(rigidHit.get().time()-hit.get().time())>.02,"collision is not still using undisplaced vanilla cube geometry");
        var floor=cell(new Vec3d(0,-1,0),1,14);var distant=cell(new Vec3d(30,10,30),8,15);
        var playerBody=new Box(.2,0,.2,.8,1.8,.8);
        var before=frame(1,true,List.of(floor,distant));
        var after=frame(2,false,List.of(floor,warp(distant,5000,7)));
        check(MeshCollision.transitionClear(before,after,playerBody),"distorted remote geometry does not hold an unchanged nearby floor");
        check(MeshCollision.bodyClear(after,playerBody),"protected nearby floor does not clip the player");
        var intruding=frame(3,false,List.of(cell(new Vec3d(0,.5,0),1,14),distant));
        check(!MeshCollision.transitionClear(before,intruding,playerBody),"real sweeping-into-body transitions still fail closed");
    }
    private static void wireAgreement() {
        var rigid=cell(new Vec3d(30,10,30),8,16);var deep=warp(rigid,1000,Long.MAX_VALUE);
        var session=new SpiritFramePayload.Session(new UUID(1,2),new UUID(3,4),"mysticism:spirit",1,0);
        var previous=frame(1,false,List.of(rigid));var current=frame(2,false,List.of(deep));
        for(TerrainMeshFrame base:new TerrainMeshFrame[]{null,previous}) {
            var packets=SpiritTerrainPayload.batches(session,2,current,base);
            check(packets.size()==1 && packets.getFirst().frame().cells().equals(List.of(deep)),"full/delta transport includes actual changed affine columns");
            var buf=new RegistryByteBuf(Unpooled.buffer(),DynamicRegistryManager.EMPTY);
            try {
                SpiritTerrainPayload.CODEC.encode(buf,packets.getFirst());
                var decoded=SpiritTerrainPayload.CODEC.decode(buf);
                check(decoded.frame().equals(current) && buf.readableBytes()==0,"client receives EXACT server geometry, provenance and collision shapes");
                var clientIndex=new MeshCollision.Index(decoded.frame());
                var serverIndex=new MeshCollision.Index(current);
                Vec3d from=deep.point(.5,1,.5).add(0,2,0),to=from.add(0,-4,0);
                check(clientIndex.clearRay(from,to)==serverIndex.clearRay(from,to),"client/server physical occlusion agrees after wire roundtrip");
            } finally {buf.release();}
        }
    }
    public static void main(String[] args) {
        rampAndMode();deformsVisiblyAndPhysically();sharedFacesAndProducers();boundsOrientationAndContinuity();physicalHitsAndTransitions();wireAgreement();
        System.out.println("DeepDistortionTest: "+checks+" checks passed");
    }
}
