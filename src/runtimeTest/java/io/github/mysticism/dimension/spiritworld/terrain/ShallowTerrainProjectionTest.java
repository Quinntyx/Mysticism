package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.client.spiritworld.terrain.TerrainModelTransform;
import io.github.mysticism.landmark.DimensionOwnershipChecks;
import io.github.mysticism.net.SpiritFramePayload;
import io.github.mysticism.net.SpiritTerrainPayload;
import io.github.mysticism.vector.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Real affine geometry, SAT, wire and CPU renderer regressions; no game/GL/model bootstrap. */
public final class ShallowTerrainProjectionTest {
    private static int checks;
    private static final Box UNIT = new Box(0,0,0,1,1,1);
    private static final Vec3d X = new Vec3d(1,0,0), Y = new Vec3d(0,1,0), Z = new Vec3d(0,0,1);
    private static final TerrainMeshFrame.Material MATERIAL = new TerrainMeshFrame.Material("minecraft:stone", Map.of());
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void close(Vec3d actual, Vec3d expected, double tolerance, String message) {
        check(actual.distanceTo(expected) < tolerance, message + ": " + actual + " != " + expected);
    }
    private static Vec384f vector(int axis, float amount) {
        float[] v = new float[EmbeddingSpace.DIMENSIONS]; v[axis] = amount; return new Vec384f(v);
    }
    private static TerrainMeshFrame.Cell cell(long key, Vec3d min, double side, List<Box> shapes) {
        return new TerrainMeshFrame.Cell(key,0,"actual-source-owner",new Vec3d(400,63,-300),min,
                new Vec3d(side,side,side),X.multiply(side),Y.multiply(side),Z.multiply(side),0xaabbcc,0xf000f0,1,shapes);
    }
    private static TerrainMeshFrame frame(long revision, TerrainMeshFrame.Cell c) {
        return new TerrainMeshFrame(revision,true,"minecraft:overworld",c.sourceMin(),Vec3d.ZERO,List.of(MATERIAL),List.of(c));
    }
    private static void independentNativeProjection() {
        check(EmbeddingSpace.DIMENSIONS == 768 && EmbeddingSpace.SCHEMA == 2,"Native v2 regression fixture");
        Vec384f q = vector(767, .5f), location = q.clone().add(vector(767,.02f));
        int[] before = location.toBits(), qBefore = q.toBits();
        Basis384f observerA = new Basis384f(vector(767,1),vector(1,1),vector(2,1));
        Basis384f observerB = new Basis384f(vector(0,1),vector(767,1),vector(2,1));
        Vec3d playerA = new Vec3d(100,80,-20), playerB = new Vec3d(-300,40,10);
        Vec3d a = ShallowTerrainProjection.displacement(location,q,observerA,playerA,playerA);
        Vec3d b = ShallowTerrainProjection.displacement(location,q,observerB,playerB,playerB);
        close(a,new Vec3d(1.92,0,0),1e-5,"All native coordinates affect observer A");
        close(b,new Vec3d(0,1.92,0),1e-5,"Observer B has independent basis/carrier");
        close(ShallowTerrainProjection.displacement(location,q,observerA,playerA,playerA),a,1e-9,"B cannot alter A");
        close(ShallowTerrainProjection.displacement(q,q,observerA,playerA,playerA),Vec3d.ZERO,1e-9,"Supporting root stays source aligned");
        close(ShallowTerrainProjection.displacement(location,q,observerA,playerA,playerA.add(1,0,0)),
                new Vec3d(.92,0,0),1e-5,"Projection is relative to this source/carrier binding");
        check(Math.abs(ShallowTerrainProjection.displacement(vector(767,4),q,observerA,playerA,playerA).length()-4)<1e-9,"Bound far displacement");
        check(Arrays.equals(before,location.toBits()) && Arrays.equals(qBefore,q.toBits()),"Do not mutate or normalize semantic locations");
        float[] legacy = new float[384];
        try { new Vec384f(legacy); throw new AssertionError("Legacy vector accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void dimensionScopedOwnerProjection() {
        String local = DimensionOwnershipChecks.LOCAL, foreign = DimensionOwnershipChecks.FOREIGN;
        var localOwner = DimensionOwnershipChecks.metadata(DimensionOwnershipChecks.landmark(local,0));
        var foreignOwner = DimensionOwnershipChecks.metadata(DimensionOwnershipChecks.landmark(foreign,0));
        // Identical source coordinates and embeddings must not disguise a foreign actual owner.
        Vec384f effective = vector(767,.03f), q = vector(767,0);
        int[] before = effective.toBits();
        Basis384f sourceBasis = new Basis384f(vector(767,1),vector(1,1),vector(2,1));
        Basis384f observer = new Basis384f(vector(0,1),vector(767,1),vector(2,1));
        Vec3d sourceOrigin = new Vec3d(1,0,0);
        int[] evaluations = {0};
        java.util.function.Function<io.github.mysticism.landmark.LandmarkMetadata,Vec384f> embedding = owner -> {
            evaluations[0]++;return effective; // Deliberately shared: production must not mutate the returned location.
        };
        Vec3d rejected = ShallowTerrainProjection.ownerDisplacement(local,foreignOwner,embedding,
                sourceOrigin,sourceBasis,q,observer,Vec3d.ZERO,Vec3d.ZERO);
        close(rejected,Vec3d.ZERO,1e-9,"Foreign owner cannot distort local source terrain");
        check(evaluations[0]==0,"Namespace rejection precedes effective embedding callback");
        Vec3d shift = ShallowTerrainProjection.ownerDisplacement(local,localOwner,embedding,
                sourceOrigin,sourceBasis,q,observer,Vec3d.ZERO,Vec3d.ZERO);
        close(shift,new Vec3d(0,3.88,0),1e-5,"Source offset uses source basis, location uses independent observer basis");
        check(evaluations[0]==1 && Arrays.equals(before,effective.toBits()),"Accepted owner evaluated once without mutating location");
        Vec3d targetShift = ShallowTerrainProjection.ownerDisplacement(foreign,foreignOwner,embedding,
                sourceOrigin,sourceBasis,q,observer,Vec3d.ZERO,Vec3d.ZERO);
        close(targetShift,shift,1e-9,"Explicit foreign target projects only in its own namespace");
        check(SpiritTerrainService.sourceNamespaceAllows(local,foreign,true)
                && !SpiritTerrainService.sourceNamespaceAllows(local,foreign,false),"Target exception does not admit automatic foreign discovery");
        var nativeCell = cell(100,new Vec3d(30,0,0),1,List.of(UNIT));
        var foreignCell = ShallowTerrainProjection.distort(nativeCell,rejected,Vec3d.ZERO,1);
        check(foreignCell==nativeCell,"Foreign metadata cannot move either render or collision geometry");
        var localCell = ShallowTerrainProjection.distort(nativeCell,shift,Vec3d.ZERO,1);
        Vec3d inside = localCell.point(.5,.5,.5);
        Box body = new Box(inside.x-.01,inside.y-.01,inside.z-.01,inside.x+.01,inside.y+.01,inside.z+.01);
        check(MeshCollision.bodyClear(frame(0,foreignCell),body)
                && !MeshCollision.bodyClear(frame(1,localCell),body),"SAT uses only accepted dimension-scoped distortion");
        Vector3f vertex = TerrainModelTransform.position(localCell,Vec3d.ZERO,null)
                .transformPosition(new Vector3f(.5f,.5f,.5f));
        close(new Vec3d(vertex.x,vertex.y,vertex.z),inside,1e-6,"Renderer and SAT share namespaced owner projection");
        Vec3d switched = ShallowTerrainProjection.ownerDisplacement(foreign,localOwner,embedding,
                sourceOrigin,sourceBasis,q,observer,Vec3d.ZERO,Vec3d.ZERO);
        close(switched,Vec3d.ZERO,1e-9,"Acquiring foreign dimension revokes old local owner projection");
        check(evaluations[0]==2,"Dimension switch does not evaluate stale owner");
    }
    private static void protectedSourcePatch() {
        Vec3d shift = new Vec3d(3,2,-1);
        for (Vec3d p : List.of(Vec3d.ZERO,new Vec3d(4.9,0,0),new Vec3d(-6,0,0),new Vec3d(0,-1,4.99))) {
            var near = cell(1,p,1,List.of(UNIT));
            check(ShallowTerrainProjection.distort(near,shift,Vec3d.ZERO,1) == near,"Five-block floor/border is unchanged");
        }
        var coarse = cell(2,new Vec3d(-32,-32,-32),64,List.of(UNIT));
        check(ShallowTerrainProjection.distort(coarse,shift,Vec3d.ZERO,1) == coarse,"Protect entire straddling octree node");
        var overhang = cell(3,new Vec3d(6,0,0),1,List.of(new Box(-2,0,0,1,1,1)));
        check(ShallowTerrainProjection.distort(overhang,shift,Vec3d.ZERO,1) == overhang,"Protect collision extending outside unit model");
        var far = cell(4,new Vec3d(12,0,0),1,List.of(UNIT));
        check(ShallowTerrainProjection.distort(far,shift,Vec3d.ZERO,0) == far,"Initial entry/acquisition is source identical");
        check(ShallowTerrainProjection.distort(far,Vec3d.ZERO,Vec3d.ZERO,1) == far,"Aligned owner requires no cosmetic distortion");
        // Entire-cell protection must converge smoothly, not snap its remote corners.
        for (var shapes : List.of(List.of(UNIT),List.of(new Box(-2,0,0,1,1,1)))) {
            var edge=cell(5,new Vec3d(shapes.getFirst().minX==0?6:8,0,0),1,shapes);
            var protectedCell=ShallowTerrainProjection.distort(edge,shift,new Vec3d(1,0,0),1);
            check(protectedCell==edge,"Exact walking-patch boundary stays native");
            var approaching=ShallowTerrainProjection.distort(edge,shift,new Vec3d(.999,0,0),1);
            for (int x=0;x<=1;x++) for (int y=0;y<=1;y++) for (int z=0;z<=1;z++)
                close(approaching.point(x,y,z),edge.point(x,y,z),1e-7,"Whole model converges at patch/collision-overhang boundary");
        }
        var distant=cell(6,new Vec3d(30,0,0),1,List.of(UNIT));
        var translated=ShallowTerrainProjection.distort(distant,shift,Vec3d.ZERO,1);
        close(translated.min(),distant.min().add(shift),1e-9,"Far field reaches real projected displacement");
        close(translated.axisX(),distant.axisX(),1e-9,"Fully faded far field remains affine");
        TerrainMeshFrame previous = frame(0,far);
        for (int tick=1;tick<=40;tick++) {
            var nextCell = ShallowTerrainProjection.distort(far,shift,Vec3d.ZERO,tick/40.0);
            check(nextCell.min().distanceTo(previous.cells().getFirst().min()) < .1,"Entry ramp has bounded frame motion");
            var next = frame(tick,nextCell);
            check(MeshCollision.transitionClear(previous,next,new Box(-.3,0,-.3,.3,1.8,.3)),"Far ramp cannot cross player body");
            previous = next;
        }
    }
    private static void modelAndShapeGeometry() {
        List<List<Box>> shapes = List.of(List.of(UNIT),List.of(new Box(0,0,0,1,.5,1)),
                List.of(new Box(0,0,0,1,.5,1),new Box(.5,.5,0,1,1,1)),
                List.of(new Box(.375,0,.375,.625,1.5,.625),new Box(0,.375,.4375,1,.9375,.5625)));
        long key=1;
        for (var local : shapes) {
            var nativeCell=cell(key++,new Vec3d(12,0,1),1,local);
            var distorted=ShallowTerrainProjection.distort(nativeCell,new Vec3d(0,3,1),Vec3d.ZERO,1);
            check(!distorted.min().equals(nativeCell.min()) && !distorted.axisX().equals(nativeCell.axisX()),"Actual shallow translation AND shear");
            check(distorted.key()==nativeCell.key() && distorted.sourceMin().equals(nativeCell.sourceMin())
                    && distorted.landmarkId().equals(nativeCell.landmarkId()) && distorted.collision().equals(local)
                    && distorted.material()==nativeCell.material(),"Projection leaves canonical source/provenance/local shapes untouched");
            Vec3d anchor=new Vec3d(10,0,-1);
            for (double x : new double[]{0,.25,1}) for (double y : new double[]{0,.5,1}) for (double z : new double[]{0,.75,1}) {
                Vector3f vertex=TerrainModelTransform.position(distorted,anchor,null).transformPosition(new Vector3f((float)x,(float)y,(float)z));
                close(new Vec3d(vertex.x,vertex.y,vertex.z).add(anchor),distorted.point(x,y,z),1e-6,"Renderer follows exact SAT affine columns");
            }
            for (Box primitive : local) {
                Vector3f vertex=TerrainModelTransform.position(distorted,anchor,primitive).transformPosition(new Vector3f(.2f,.6f,.7f));
                close(new Vec3d(vertex.x,vertex.y,vertex.z).add(anchor),
                        distorted.point(primitive.minX+primitive.getLengthX()*.2,primitive.minY+primitive.getLengthY()*.6,
                                primitive.minZ+primitive.getLengthZ()*.7),1e-6,"Non-model fallback renders the same local collision primitive");
                Vec3d inside=distorted.point((primitive.minX+primitive.maxX)/2,(primitive.minY+primitive.maxY)/2,
                        (primitive.minZ+primitive.maxZ)/2);
                check(!MeshCollision.bodyClear(frame(1,distorted),new Box(inside.x-.01,inside.y-.01,inside.z-.01,inside.x+.01,inside.y+.01,inside.z+.01)),"SAT uses transformed local shape, not original grid");
            }
            Vec3d sky=distorted.point(.5,2,.5), downward=new Vec3d(0,-3,0);
            var hit=new MeshCollision.Index(frame(1,distorted)).sweep(new Box(sky.x-.03,sky.y-.03,sky.z-.03,sky.x+.03,sky.y+.03,sky.z+.03),downward);
            check(hit.isPresent() && hit.get().normal().y>0,"Sweep hits deformed surface");
        }
        var slab=ShallowTerrainProjection.distort(cell(10,new Vec3d(12,0,0),1,List.of(new Box(0,0,0,1,.5,1))),new Vec3d(0,3,0),Vec3d.ZERO,1);
        Vec3d air=slab.point(.5,.8,.5);
        check(MeshCollision.bodyClear(frame(1,slab),new Box(air.x-.01,air.y-.01,air.z-.01,air.x+.01,air.y+.01,air.z+.01)),"Slab air is not block-like/AABB collision");
        Vec3d shearVoid=new Vec3d(slab.bounds().minX+.02,slab.bounds().maxY-.02,slab.bounds().maxZ-.02);
        check(MeshCollision.bodyClear(frame(1,slab),new Box(shearVoid.x-.005,shearVoid.y-.005,shearVoid.z-.005,shearVoid.x+.005,shearVoid.y+.005,shearVoid.z+.005)),"Sheared bounding-box void is not a phantom wall");
        var steep=ShallowTerrainProjection.distort(cell(11,new Vec3d(10,0,0),1,List.of(UNIT)),new Vec3d(0,4,0),Vec3d.ZERO,1);
        Vec3d point=steep.point(.5,1,.5);
        var contact=new MeshCollision.Index(frame(1,steep)).sweep(new Box(point.x-.001,point.y+1,point.z-.001,point.x+.001,point.y+1.01,point.z+.001),new Vec3d(0,-2,0));
        Vec3d expectedNormal=steep.axisZ().crossProduct(steep.axisX()).normalize();
        check(contact.isPresent() && contact.get().normal().dotProduct(expectedNormal)>.999999,"SAT slope normal agrees with visible model face");
        var airCell=cell(12,new Vec3d(12,0,0),1,List.of());
        var distortedAir=ShallowTerrainProjection.distort(airCell,new Vec3d(1,2,0),Vec3d.ZERO,1);
        check(distortedAir.collision().isEmpty() && new MeshCollision.Index(frame(1,distortedAir)).clearRay(new Vec3d(0,.5,.5),new Vec3d(20,.5,.5)),"No invented collision for empty shapes");
    }
    private static void exactWireAndDelta() {
        var nativeCell=cell(99,new Vec3d(12,0,0),1,List.of(new Box(0,0,0,1,.5,1)));
        var distorted=ShallowTerrainProjection.distort(nativeCell,new Vec3d(0,3,0),Vec3d.ZERO,1);
        var previous=frame(1,nativeCell);var next=frame(2,distorted);
        var session=new SpiritFramePayload.Session(new java.util.UUID(1,2),new java.util.UUID(3,4),"mysticism:spirit",1,2);
        var packets=SpiritTerrainPayload.batches(session,1,next,previous);
        check(packets.size()==1 && !packets.getFirst().full() && packets.getFirst().frame().cells().equals(List.of(distorted)),"Distortion participates in authoritative mesh delta");
        RegistryByteBuf bytes=new RegistryByteBuf(Unpooled.buffer(),DynamicRegistryManager.EMPTY);
        try {
            SpiritTerrainPayload.CODEC.encode(bytes,packets.getFirst());
            var decoded=SpiritTerrainPayload.CODEC.decode(bytes);
            check(decoded.equals(packets.getFirst()) && bytes.readableBytes()==0,"Wire preserves affine columns and exact local shapes");
            Vec3d ray=distorted.point(.5,1.5,.5);
            Box body=new Box(ray.x-.05,ray.y,ray.z-.05,ray.x+.05,ray.y+.1,ray.z+.05);
            var server=new MeshCollision.Index(next).sweep(body,new Vec3d(0,-3,0));
            var client=new MeshCollision.Index(decoded.frame()).sweep(body,new Vec3d(0,-3,0));
            check(server.isPresent() && server.equals(client),"Transported client collision matches server collision exactly");
        } finally { bytes.release(); }
        var unchanged=SpiritTerrainPayload.batches(session,2,next,next);
        check(unchanged.getFirst().frame().cells().isEmpty(),"Unchanged geometry sends no redundant cells");
    }
    private static void rectangularDiscoveryProjection() {
        var tile=new SourceMeshBuilder.Tile(MATERIAL,List.of(UNIT),0xffffff,0xf000f0,false,true);
        var source=new java.util.ArrayList<SourceMeshBuilder.Node>();
        for(int x=12;x<28;x++)for(int z=0;z<8;z++)
            source.add(new SourceMeshBuilder.Node(new net.minecraft.util.math.BlockPos(x,0,z),1,tile,"actual-source-owner"));
        var batches=SourceDiscovery.coalesce(source);
        check(batches.size()==1 && batches.getFirst().size().equals(new Vec3d(16,1,8)),"Real discovery batching produces non-cubic source extents");
        var candidate=SourceDiscovery.project(batches.getFirst(),Vec3d.ZERO,Vec3d.ZERO,X,Y,Z,Vec3d.ZERO);
        long key=SourceMeshBuilder.key("actual-source-owner",candidate.source(),candidate.node().size());
        var nativeCell=candidate.cell(0,key,1);
        var distorted=candidate.cell(0,key,1,new Vec3d(0,3,1),Vec3d.ZERO,1);
        check(distorted.size().equals(new Vec3d(16,1,8)) && distorted.key()==key
                && distorted.sourceMin().equals(nativeCell.sourceMin()),"Shallow emission preserves every rectangular extent/key/source coordinate");
        check(!distorted.axisX().equals(nativeCell.axisX()) && !distorted.min().equals(nativeCell.min()),"Discovery batch genuinely distorts rather than bypassing shallow projection");
        check(candidate.cell(0,key,1,new Vec3d(0,3,1),Vec3d.ZERO,0).equals(nativeCell),"Entry ramp starts discovery source identical");
        check(candidate.cell(0,key,1,Vec3d.ZERO,Vec3d.ZERO,1).equals(nativeCell),"Deep/unknown owner emission does not invent a shift");
        var second=candidate.cell(0,key,1,new Vec3d(2,0,0),Vec3d.ZERO,1);
        check(!second.min().equals(distorted.min()),"Independent observer displacement is not shared between batches");
        var next=frame(2,distorted);
        Vec3d inside=distorted.point(.8,.5,.8);
        check(!MeshCollision.bodyClear(next,new Box(inside.x-.01,inside.y-.01,inside.z-.01,inside.x+.01,inside.y+.01,inside.z+.01)),"SAT covers rectangular interior beyond legacy node.side cube");
        Vector3f vertex=TerrainModelTransform.position(distorted,Vec3d.ZERO,null).transformPosition(new Vector3f(.8f,.5f,.8f));
        close(new Vec3d(vertex.x,vertex.y,vertex.z),inside,1e-5,"Rendered batch interior uses the same distorted rectangular columns as SAT");
        var session=new SpiritFramePayload.Session(new java.util.UUID(1,2),new java.util.UUID(3,4),"mysticism:spirit",1,2);
        var packets=SpiritTerrainPayload.batches(session,1,next,frame(1,nativeCell));
        RegistryByteBuf bytes=new RegistryByteBuf(Unpooled.buffer(),DynamicRegistryManager.EMPTY);
        try {
            SpiritTerrainPayload.CODEC.encode(bytes,packets.getFirst());
            var decoded=SpiritTerrainPayload.CODEC.decode(bytes);
            check(decoded.frame().cells().equals(List.of(distorted)),"Wire delta preserves distortion AND all discovery extents");
            Vec3d sky=distorted.point(.8,2,.8);
            Box body=new Box(sky.x-.02,sky.y,sky.z-.02,sky.x+.02,sky.y+.1,sky.z+.02);
            var server=new MeshCollision.Index(next).sweep(body,new Vec3d(0,-4,0));
            check(server.isPresent() && server.equals(new MeshCollision.Index(decoded.frame()).sweep(body,new Vec3d(0,-4,0))),"Client/server collide identically with transported discovery batch");
        } finally {bytes.release();}
        var near=new SourceMeshBuilder.Node(new net.minecraft.util.math.BlockPos(-8,0,0),new Vec3d(16,1,8),tile,"actual-source-owner");
        var protectedCandidate=SourceDiscovery.project(near,Vec3d.ZERO,Vec3d.ZERO,X,Y,Z,Vec3d.ZERO);
        check(protectedCandidate.cell(0,1,1,new Vec3d(0,3,1),Vec3d.ZERO,1).equals(protectedCandidate.cell(0,1,1)),"Whole straddling discovery batch preserves protected walking patch");
    }
    public static void main(String[] args) {
        check(ShallowTerrainProjectionTest.class.desiredAssertionStatus(),"Main-based regression runs with assertions");
        independentNativeProjection();dimensionScopedOwnerProjection();protectedSourcePatch();modelAndShapeGeometry();exactWireAndDelta();rectangularDiscoveryProjection();
        System.out.println("ShallowTerrainProjectionTest: " + checks + " checks passed");
    }
}
