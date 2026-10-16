package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Production SAT/contact/diagnostic tests; no proxy blocks, model service or game boot. */
public final class MeshSupportEvidenceTest {
    private static final Box UNIT=new Box(0,0,0,1,1,1);
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static void near(double actual,double expected,String reason){check(Math.abs(actual-expected)<1e-6,reason+": "+actual);}
    private static TerrainMeshFrame.Cell cell(long key,Vec3d min,Vec3d x,Vec3d y,Vec3d z,float opacity,Box... shapes) {
        return new TerrainMeshFrame.Cell(key,0,"mesh-label",new Vec3d(10,20,30),min,new Vec3d(1,1,1),x,y,z,
                0xffffff,0xf000f0,opacity,List.of(shapes));
    }
    private static TerrainMeshFrame.Cell cube(long key,Vec3d min,float opacity) {
        return cell(key,min,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),opacity,UNIT);
    }
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(7,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cells));
    }
    private static Box body(double x,double y,double z){return new Box(x-.3,y,z-.3,x+.3,y+1.8,z+.3);}
    private static void nativeShapes() {
        var cube=cube(1,Vec3d.ZERO,1);var f=frame(cube);
        var ground=new MeshCollision.Index(f).groundContact(body(.5,1,.5)).orElseThrow();
        near(ground.hit().time(),1.0/6,"raised floor probe TOI");near(ground.point().y,1,"full cube surface");
        near(ground.hit().normal().y,1,"upward normal");
        var slab=cell(2,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,new Box(0,0,0,1,.5,1));
        near(new MeshCollision.Index(frame(slab)).groundContact(body(.5,.5,.5)).orElseThrow().point().y,.5,"slab top, not block maximum");
        var stair=cell(3,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,
                new Box(0,0,0,.5,.5,1),new Box(.5,0,0,1,1,1));
        var step=new MeshCollision.Index(frame(stair)).groundContact(new Box(.05,.5,.1,.45,2.3,.7)).orElseThrow();
        near(step.point().y,.5,"lower stair primitive is actual support");
        check(new MeshCollision.Index(f).groundContact(body(.5,1.2,.5)).isEmpty(),"hover is not support");
        check(new MeshCollision.Index(f).groundContact(body(2,1,.5)).isEmpty(),"distant source is not support");
        // A leaning wall and the floor touch simultaneously; the generic sweep can choose the wall first.
        var wall=cell(4,new Vec3d(.7,0,0),new Vec3d(.1,0,0),new Vec3d(.2,2,0),new Vec3d(0,0,1),1,UNIT);
        var index=new MeshCollision.Index(frame(wall,cube));
        check(index.sweep(body(.5,1,.5).offset(0,.025,0),new Vec3d(0,-.15,0)).orElseThrow().normal().y<.3,
                "fixture reproduces wall-first sweep that formerly hid the floor");
        check(index.groundContact(body(.5,1,.5)).orElseThrow().hit().cell().key()==1,"wall contact cannot mask upward support");
    }
    private static void affineWitness() {
        // Foot center is outside the supporting cell, but part of the actual footprint overlaps.
        var edge=cube(5,new Vec3d(.7,0,0),1);Box feet=body(.5,1,.5);
        var g=new MeshCollision.Index(frame(edge)).groundContact(feet).orElseThrow();
        check(g.point().x>=.7 && g.point().x<=.8,"contact witness belongs to actual footprint intersection");
        Vec3d source=MeshContact.source(edge,g.interiorPoint());
        check(source.x>10 && source.x<10.1,"source contact is fractional intersection, not clamped center x=10");
        var skew=cell(6,Vec3d.ZERO,new Vec3d(1,.5,0),new Vec3d(.4,1,0),new Vec3d(0,0,1),1,UNIT);
        var contact=MeshContact.point(skew,UNIT,new Box(1.0,1.4,.2,1.4,1.8,.8)).orElseThrow();
        Vec3d local=MeshContact.local(skew,contact);
        check(local.x>=-1e-6 && local.x<=1+1e-6 && local.y>=-1e-6 && local.y<=1+1e-6,"skew witness inside true parallelepiped");
        near(skew.point(local.x,local.y,local.z).distanceTo(contact),0,"affine inverse round trip");
        // This query overlaps the conservative world AABB, but not the skew solid.
        check(MeshContact.point(skew,UNIT,new Box(0,1.3,.2,.1,1.4,.8)).isEmpty(),"AABB overlap cannot fabricate contact");
        var slope=cell(7,Vec3d.ZERO,new Vec3d(1,.5,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,UNIT);
        var tilted=new MeshCollision.Index(frame(slope)).groundContact(body(.5,1.4,.5)).orElseThrow();
        check(tilted.hit().normal().x<-.4 && tilted.hit().normal().y>.8,"actual tilted surface normal");
        near(MeshContact.local(slope,tilted.point()).y,1,"slope witness on actual affine top");
        var mirrored=cell(8,new Vec3d(1,0,0),new Vec3d(-1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,UNIT);
        check(new MeshCollision.Index(frame(mirrored)).groundContact(body(.5,1,.5)).isPresent(),"negative determinant retains valid contact");
        var overhang=cell(9,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,new Box(0,0,0,1,1.5,1));
        var tall=new MeshCollision.Index(frame(overhang)).groundContact(body(.5,1.5,.5)).orElseThrow();
        near(MeshContact.source(overhang,tall.point()).y,21.5,"overhang source coordinate is not clamped to producing voxel");
        var thin=cell(15,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,.0001,0),new Vec3d(0,0,1),1,UNIT);
        var narrow=new MeshCollision.Index(frame(thin)).groundContact(body(.5,.0001,.5)).orElseThrow();
        Vec3d sample=MeshContact.local(thin,narrow.interiorPoint());
        check(sample.y>0 && sample.y<1,"ownership witness stays inside thin distorted primitive, not fixed world inset");
    }
    private static void evidenceAndVisibility() {
        var visible=cube(10,Vec3d.ZERO,.01f);var hidden=cube(11,Vec3d.ZERO,0);
        var visualOnly=cell(12,new Vec3d(5,0,0),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1);
        var report=TerrainEvidence.inspect(frame(visible,hidden,visualOnly),body(.5,1,.5));
        check(report.revision()==7 && report.visibleCells()==2 && report.collisionShapes()==1,"evidence counts accepted nonzero-opacity geometry only");
        check(report.contact().orElseThrow().key()==10,"evidence names actual supporting cell");
        check(report.contact().orElseThrow().meshOwner().equals("mesh-label"),"mesh label is evidence, not an exact ownership proof");
        check(TerrainEvidence.inspect(frame(visualOnly),body(5.5,1,.5)).contact().isEmpty(),"render-only models have no invented floor");
        check(TerrainEvidence.inspect(frame(hidden),body(.5,1,.5)).contact().isEmpty(),"invisible mesh has no support");
        check(MeshCollision.bodyClear(frame(hidden),body(.5,.2,.5)),"hidden geometry cannot block body clearance");
        check(!MeshCollision.bodyClear(frame(visible),body(.5,.2,.5)),"fading visible geometry still blocks clearance");
        check(MeshCollision.transitionClear(frame(),frame(hidden),body(.5,.2,.5)),"hidden additions cannot block transitions");
        check(!MeshCollision.transitionClear(frame(hidden),frame(cube(11,Vec3d.ZERO,1)),body(.5,.2,.5)),
                "hidden-to-visible same affine pose is a new solid and must pass body guard");
        check(MeshCollision.transitionClear(frame(visible),frame(hidden),body(.5,.2,.5)),"completed fade removes rendered and collision geometry together");
        var overhang=cell(13,Vec3d.ZERO,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,new Box(0,0,0,1,1.5,1));
        check(!MeshCollision.bodyClear(frame(overhang),body(.5,1.2,.5)),"overhanging collision primitive cannot escape unit-model broadphase");
        check(!MeshCollision.transitionClear(frame(),frame(overhang),body(.5,1.2,.5)),"overhanging new solid participates in transition guard");
        check(!MeshCollision.transitionClear(frame(cube(14,new Vec3d(-10,0,0),1)),frame(cube(14,new Vec3d(10,0,0),1)),body(0,.2,.5)),
                "distant endpoint bounds cannot hide a surface sweeping through the body");
    }
    public static void main(String[] args) {
        nativeShapes();affineWitness();evidenceAndVisibility();
        System.out.println("MeshSupportEvidenceTest: "+checks+" checks passed");
    }
}
