package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Current affine mesh hot-path checks; no source world, game bootstrap or embedding IO. */
public final class MeshSurfaceQualityTest {
    private static final Box UNIT=new Box(0,0,0,1,1,1);
    private static final Vec3d X=new Vec3d(1,.2,0),Y=new Vec3d(.3,1,.1),Z=new Vec3d(0,.1,1);
    private static int checks;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static TerrainMeshFrame.Cell cell(long key,String owner,Vec3d min,Vec3d x,Vec3d y,Vec3d z,float alpha,List<Box> shapes) {
        return new TerrainMeshFrame.Cell(key,0,owner,min,min,new Vec3d(1,1,1),x,y,z,0xffffff,0xf000f0,alpha,shapes);
    }
    private static TerrainMeshFrame frame(List<TerrainMeshFrame.Cell> cells) {
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),cells);
    }
    private static void seams() {
        Vec3d origin=new Vec3d(5,-2,10);
        var coarse=cell(1,"remote",origin,X.multiply(2),Y.multiply(2),Z.multiply(2),1,List.of(UNIT));
        List<TerrainMeshFrame.Cell> input=new ArrayList<>();input.add(coarse);
        for(int j=0;j<2;j++)for(int k=0;k<2;k++)
            input.add(cell(2+j*2+k,"remote",origin.add(X.multiply(2)).add(Y.multiply(j)).add(Z.multiply(k)),X,Y,Z,1,List.of(UNIT)));
        var output=MeshStitcher.deduplicate(input,new boolean[]{true},"local");
        check(output.equals(input),"distorted coarse/fine leaves must retain the shared source projection");
        for(int i=1;i<output.size();i++) {
            int j=(i-1)/2,k=(i-1)%2;var fine=output.get(i);
            for(int v=0;v<2;v++)for(int w=0;w<2;w++)
                check(coarse.point(1,(j+v)/2.0,(k+w)/2.0).distanceTo(fine.point(0,v,w))<1e-12,"LOD seam vertex");
        }
        // Shallow uses the public path and never resolves a registry or changes near support.
        check(MeshStitcher.stitch(input,List.of(),"local",true,new Vec3d(7,0,10)).equals(input),"shallow geometry untouched");
        var other=cell(50,"other",new Vec3d(6,-1.8,10),X,Y,Z,1,List.of(UNIT));
        check(MeshStitcher.deduplicate(List.of(coarse,other),new boolean[]{true},"local").size()==2,"real overlapping/cliff geometry is not welded or filled");
    }
    private static void duplicates() {
        var hidden=cell(1,"a",Vec3d.ZERO,X,Y,Z,0,List.of(UNIT));
        var support=cell(2,"b",Vec3d.ZERO,X,Y,Z,1,List.of(UNIT));
        for(var input:List.of(List.of(hidden,support),List.of(support,hidden))) {
            var cells=MeshStitcher.deduplicate(input,new boolean[]{true},"a");
            check(cells.equals(List.of(support)),"hidden duplicate cannot erase visible support/producer");
        }
        var first=cell(3,"a",Vec3d.ZERO,X,Y,Z,1,List.of(UNIT));
        check(MeshStitcher.deduplicate(List.of(first,support),new boolean[]{true},"local")
                .equals(MeshStitcher.deduplicate(List.of(support,first),new boolean[]{true},"local")),"duplicate ownership is deterministic");
        var decoration=cell(4,"b",Vec3d.ZERO,X,Y,Z,1,List.of());
        var decoration2=cell(5,"c",Vec3d.ZERO,X,Y,Z,1,List.of());
        check(MeshStitcher.deduplicate(List.of(decoration,decoration2),new boolean[]{false},"local").size()==2,"empty shapes do not identify visible models");
        var partial=cell(6,"c",Vec3d.ZERO,X,Y,Z,1,List.of(new Box(0,0,0,1,.5,1)));
        check(MeshStitcher.deduplicate(List.of(partial,partial),new boolean[]{true},"local").size()==2,"partial models are not cube skins");
    }
    private static void boundsAndOrientation() {
        Random random=new Random(498);
        for(int iteration=0;iteration<80;iteration++) {
            Vec3d x=new Vec3d(-1,.2,random.nextDouble()*.2),y=new Vec3d(.3,2,.1),z=new Vec3d(.1,.3,1);
            var c=cell(1,"source",new Vec3d(-33,60,82),x,y,z,1,List.of(UNIT));
            Box local=new Box(-.5,-.2,.1,1.3,2,1.5),bounds=c.bounds(local);
            for(int bits=0;bits<8;bits++) {
                Vec3d p=c.point((bits&1)==0?local.minX:local.maxX,(bits&2)==0?local.minY:local.maxY,(bits&4)==0?local.minZ:local.maxZ);
                check(p.x>=bounds.minX-1e-12 && p.x<=bounds.maxX+1e-12 && p.y>=bounds.minY-1e-12 && p.y<=bounds.maxY+1e-12 && p.z>=bounds.minZ-1e-12 && p.z<=bounds.maxZ+1e-12,"affine bounds contain reflected/sheared corners");
            }
            check(x.dotProduct(y.crossProduct(z))<0,"reflected orientation accepted");
        }
        try {cell(1,"collapsed",Vec3d.ZERO,X,X,Z,1,List.of(UNIT));throw new AssertionError("collapsed cell accepted");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void extendedSupport() {
        var c=cell(1,"fence",Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,List.of(new Box(0,0,0,1,1.5,1)));
        var f=frame(List.of(c));Box body=new Box(.2,1.1,.2,.8,1.4,.8);
        check(!c.bounds().intersects(body),"fixture lies outside unit bounds");
        check(!MeshCollision.bodyClear(f,body),"non-unit visible primitive must obstruct landing");
        check(!MeshCollision.transitionClear(frame(List.of()),f,body),"new non-unit primitive must obstruct frame publication");
        var tall=cell(10,"extended",new Vec3d(0,-80,0),c.axisX(),new Vec3d(0,64,0),c.axisZ(),1,c.collision());
        check(!tall.bounds().intersects(body.expand(5)),"transition fixture extends beyond the near-unit broadphase");
        check(!MeshCollision.transitionClear(frame(List.of()),frame(List.of(tall)),body),"extended shape cannot be omitted from transition broadphase");
        var hit=new MeshCollision.Index(f).sweep(new Box(.2,1.6,.2,.8,2.2,.8),new Vec3d(0,-.3,0));
        check(hit.isPresent() && hit.get().normal().y>.99,"rendered extended top is usable ground");
        check(Math.abs(hit.orElseThrow().time()-1.0/3)<1e-8,"support contact is actual top, not unit bound");
        var hidden=cell(2,"hidden",Vec3d.ZERO,c.axisX(),c.axisY(),c.axisZ(),0,c.collision());
        check(MeshCollision.bodyClear(frame(List.of(hidden)),body),"fully hidden primitive absent in collision too");
    }
    public static void main(String[] args) {
        seams();duplicates();boundsAndOrientation();extendedSupport();
        System.out.println("MeshSurfaceQualityTest: "+checks+" checks passed");
    }
}
