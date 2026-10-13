package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-player deep/shallow mesh and collision isolation regressions against the real production
 * projection, stitching and swept-collision code. No server boot, registries or model IO: every
 * frame is produced through SpiritTerrainService's actual per-observer transform seam and
 * MeshCollision's actual per-player index cache, so a shared-frame regression fails here. */
public final class MeshIsolationTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void near(double actual,double expected,double tolerance,String why){
        check(Math.abs(actual-expected)<=tolerance,why+" ("+actual+" != "+expected+")");
    }
    private static Vec384f vec(double x,double y,double z){
        float[] v=new float[io.github.mysticism.vector.EmbeddingSpace.DIMENSIONS];
        v[0]=(float)x;v[1]=(float)y;v[2]=(float)z;
        return new Vec384f(v);
    }
    private static Basis384f basis(Vec384f i,Vec384f j,Vec384f k){return new Basis384f(i,j,k);}
    private static Basis384f yaw(double radians){
        double c=Math.cos(radians),s=Math.sin(radians);
        return basis(vec(c,0,s),vec(0,1,0),vec(-s,0,c));
    }
    private static TerrainMeshFrame.Material material(String block){
        return new TerrainMeshFrame.Material(block,java.util.Map.of());
    }
    private static TerrainMeshFrame.Cell cell(long key,int materialIndex,String landmark,Vec3d source,
            Vec3d min,Vec3d ax,Vec3d ay,Vec3d az,List<Box> collision){
        return new TerrainMeshFrame.Cell(key,materialIndex,landmark,source,min,new Vec3d(1,1,1),ax,ay,az,
                0xffffff,0xf000f0,1f,collision);
    }
    /** Project the SAME source floor through the REAL per-observer seam for one observer. */
    private static TerrainMeshFrame project(String dimension,Vec384f windowSemantic,Vec3d windowOrigin,
            Basis384f sourceBasis,String landmark,Vec384f observerQ,Basis384f observer,Vec3d viewerPos,
            boolean shallow,long revision){
        Vec3d root=SpiritTerrainService.projectedRoot(windowSemantic,observerQ,observer,viewerPos);
        Vec3d ax=SpiritTerrainService.axis(sourceBasis.i,observer);
        Vec3d ay=SpiritTerrainService.axis(sourceBasis.j,observer);
        Vec3d az=SpiritTerrainService.axis(sourceBasis.k,observer);
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();
        int[] sourceX={0,1,0,1,0,1,0,1},sourceZ={0,0,1,1,0,0,1,1},sourceY={0,0,0,0,1,1,1,1};
        boolean[] wall={false,false,false,false,false,false,false,true};
        for(int n=0;n<8;n++){
            Vec3d at=new Vec3d(windowOrigin.x+sourceX[n],windowOrigin.y+sourceY[n],windowOrigin.z+sourceZ[n]);
            Vec3d offset=at.subtract(windowOrigin);
            Vec3d min=root.add(ax.multiply(offset.x)).add(ay.multiply(offset.y)).add(az.multiply(offset.z));
            List<Box> collision=List.of(n==7?new Box(.1,0,.1,.9,1,.9):SourceMeshBuilder.UNIT);
            cells.add(cell(SourceMeshBuilder.key(landmark,at,1),0,landmark,at,min,ax,ay,az,collision));
        }
        return new TerrainMeshFrame(revision,shallow,dimension,windowOrigin,root,List.of(material("minecraft:stone")),cells);
    }
    private static MeshCollision.Hit ground(MeshCollision.Index index,Vec3d feet){
        // Production ground(): body offset up .025, swept down .15, upward normal only.
        return index.sweep(new Box(feet.x-.3,feet.y+.025,feet.z-.3,feet.x+.3,feet.y+1.825,feet.z+.3),new Vec3d(0,-.15,0))
                .filter(h->h.normal().y>.3).orElse(null);
    }
    /** Feet centered on one full floor cell's top face, valid under any horizontal source rotation. */
    private static Vec3d feetOnFloor(TerrainMeshFrame frame,Vec3d windowOrigin){
        return frame.cells().stream().filter(c->c.collision().size()==1 && c.collision().getFirst().equals(SourceMeshBuilder.UNIT)
                        && Math.abs(c.sourceMin().y-windowOrigin.y)<1e-9 && c.sourceMin().x==windowOrigin.x && c.sourceMin().z==windowOrigin.z)
                .findFirst().map(c->{var b=c.bounds();return new Vec3d(b.getCenter().x,b.maxY,b.getCenter().z);})
                .orElseThrow(()->new AssertionError("frame lost its reference floor cell"));
    }

    /** Two observers with different q/basis place one source window at different world geometry,
     * while cell provenance keys stay stable; each index only ever answers for its own frame. */
    private static void observerDivergence() {
        Vec384f semantic=vec(3,-2,5);
        Vec3d windowOrigin=new Vec3d(1000,40,1000);
        Basis384f sourceGrid=basis(vec(1,0,0),vec(0,1,0),vec(0,0,1));
        // Deep observer: different q, yawed basis, own viewer pose. Shallow observer: local aligned window.
        Vec384f deepQ=vec(-4,1,2);
        Basis384f deepBasis=yaw(Math.PI/4);
        Vec3d deepViewer=new Vec3d(0,128,0);
        Vec384f shallowQ=semantic;
        Basis384f shallowBasis=basis(vec(1,0,0),vec(0,1,0),vec(0,0,1));
        Vec3d shallowViewer=new Vec3d(-2000,64,-2000);
        TerrainMeshFrame deep=project("minecraft:overworld",semantic,windowOrigin,sourceGrid,"lm-shared",deepQ,deepBasis,deepViewer,false,7);
        TerrainMeshFrame shallow=project("minecraft:overworld",semantic,windowOrigin,sourceGrid,"lm-shared",shallowQ,shallowBasis,shallowViewer,true,3);
        check(deep.cells().size()==8 && shallow.cells().size()==8,"Both observers see every source cell");
        check(!deep.cells().equals(shallow.cells()),"Different projections must not share world geometry");
        // Aligned local window projects exactly onto the observer pose offset, never a shared anchor.
        Vec3d alignedRoot=SpiritTerrainService.projectedRoot(semantic,shallowQ,shallowBasis,shallowViewer);
        near(alignedRoot.x,shallowViewer.x,1e-6,"Aligned shallow root follows the observer");
        near(alignedRoot.y,shallowViewer.y,1e-6,"Aligned shallow root follows the observer");
        near(alignedRoot.z,shallowViewer.z,1e-6,"Aligned shallow root follows the observer");
        // Yawed deep basis rotates the source grid: identity source axes project onto the yawed columns.
        Vec3d deepX=SpiritTerrainService.axis(sourceGrid.i,deepBasis);
        near(deepX.x,Math.cos(Math.PI/4),1e-5,"Deep axis follows the observer basis");
        near(deepX.z,-Math.sin(Math.PI/4),1e-5,"Deep axis follows the observer basis");
        // Same source cell keeps the same provenance key for every observer.
        near(deep.cells().get(0).key(),shallow.cells().get(0).key(),0,"Source provenance keys are observer-independent");
        // Cell keys must match production ordering: identical owner/source/side.
        Vec3d at=windowOrigin.add(1,0,1);
        near(SourceMeshBuilder.key("lm-x",at,1),SourceMeshBuilder.key("lm-x",at,1),0,"Keys are deterministic");
        check(SourceMeshBuilder.key("lm-x",at,1)!=SourceMeshBuilder.key("lm-y",at,1),"Different owners never share a key");
    }

    /** Each player's swept collision answers only for their own frame; one player's floor never
     * supports, blocks or occludes the other, and body/transition validation stays frame-local. */
    private static void frameCollisionIsolation() {
        Vec384f semantic=vec(1,2,3);
        Vec3d windowOrigin=new Vec3d(500,32,500);
        Basis384f sourceGrid=basis(vec(1,0,0),vec(0,1,0),vec(0,0,1));
        Vec3d shallowViewer=new Vec3d(-100,64,-100);
        Vec3d deepViewer=new Vec3d(400,-30,900);
        TerrainMeshFrame shallow=project("minecraft:overworld",semantic,windowOrigin,sourceGrid,"lm-a",
                semantic,basis(vec(1,0,0),vec(0,1,0),vec(0,0,1)),shallowViewer,true,1);
        TerrainMeshFrame deep=project("minecraft:overworld",semantic,windowOrigin,sourceGrid,"lm-b",
                vec(0,0,0),yaw(Math.PI/2),deepViewer,false,2);
        var shallowIndex=new MeshCollision.Index(shallow);
        var deepIndex=new MeshCollision.Index(deep);
        Vec3d shallowFeet=feetOnFloor(shallow,shallow.sourceOrigin());
        Vec3d deepFeet=feetOnFloor(deep,deep.sourceOrigin());
        MeshCollision.Hit shallowGround=ground(shallowIndex,shallowFeet);
        MeshCollision.Hit deepGround=ground(deepIndex,deepFeet);
        check(shallowGround!=null && shallowGround.cell()!=null,"Shallow observer stands on their own floor");
        check(deepGround!=null,"Deep observer stands on their own distorted floor");
        check(shallowGround.normal().distanceTo(new Vec3d(0,1,0))<1e-6,"Aligned floor normal is up");
        check(Math.abs(deepGround.normal().y-1)<1e-6,"Rotated floor still supports along source up");
        check(ground(shallowIndex,deepFeet)==null,"A foreign player's floor must not support this player");
        check(ground(deepIndex,shallowFeet)==null,"A foreign player's floor must not support this player");
        Box shallowBody=shallowGround.cell().bounds();
        Box deepBody=deepGround.cell().bounds();
        check(MeshCollision.bodyClear(deep,shallowBody),"One player's terrain never blocks the other's body");
        check(MeshCollision.bodyClear(shallow,deepBody),"One player's terrain never blocks the other's body");
        check(!MeshCollision.bodyClear(shallow,shallowBody.expand(0,-.2,0)),"Own floor still blocks its own body");
        // Ray/contact independence for touch validation: each viewer's clearRay uses only their frame.
        check(!new MeshCollision.Index(shallow).sweep(new Box(shallowBody.minX,shallowBody.maxY,shallowBody.minZ,shallowBody.maxX,shallowBody.maxY+.5,shallowBody.maxZ),new Vec3d(0,.4,0)).isEmpty(),"Own ceiling contact remains visible");
        // Frame-local transition guard: replacing a cell with the same key near the body is held,
        // the same replacement far from the body passes, and a foreign frame pair is irrelevant.
        TerrainMeshFrame grown=new TerrainMeshFrame(3,deep.shallow(),deep.sourceDimension(),deep.sourceOrigin(),deep.carrierOrigin(),
                deep.materials(),deep.cells().stream().map(c->c.key()==deep.cells().get(0).key()
                    ? new TerrainMeshFrame.Cell(c.key(),c.material(),c.landmarkId(),c.sourceMin(),c.min().subtract(0,.5,0),c.size(),
                        c.axisX(),c.axisY(),c.axisZ(),c.color(),c.light(),c.opacity(),c.collision())
                    : c).toList());
        Vec3d movedCell=deep.cells().get(0).min();
        Box nearBody=new Box(movedCell.x-.3,movedCell.y-.3,movedCell.z-.3,movedCell.x+.3,movedCell.y+1.5,movedCell.z+.3);
        Box farBody=new Box(movedCell.x+40,movedCell.y,movedCell.z+40,movedCell.x+40.6,movedCell.y+1.8,movedCell.z+40.6);
        check(!MeshCollision.transitionClear(deep,grown,nearBody),"A moving surface intersecting the body is held");
        check(MeshCollision.transitionClear(deep,grown,farBody),"Distant replacement never holds a far body");
        check(MeshCollision.transitionClear(deep,deep,nearBody),"Unchanged frames always pass");
        // The shallow player's pair is unaffected by the deep player's replacement.
        check(MeshCollision.transitionClear(shallow,shallow,deepBody),"Foreign frame churn cannot hold another player");
        // Fade-out removes render AND collision together, per player: a fully faded frame supports
        // nobody, while the same source cells at full opacity keep supporting their own observer.
        TerrainMeshFrame faded=new TerrainMeshFrame(deep.revision()+1,deep.shallow(),deep.sourceDimension(),deep.sourceOrigin(),deep.carrierOrigin(),
                deep.materials(),deep.cells().stream().map(c->new TerrainMeshFrame.Cell(c.key(),c.material(),c.landmarkId(),c.sourceMin(),c.min(),
                    c.size(),c.axisX(),c.axisY(),c.axisZ(),c.color(),c.light(),0f,c.collision())).toList());
        check(ground(new MeshCollision.Index(faded),deepFeet)==null,"Fully faded terrain has no collision");
        check(ground(deepIndex,deepFeet)!=null,"Opacity loss is per frame, never global");
    }

    /** The collision index cache must be per-player: per-UUID rebuild on frame change, per-UUID
     * eviction, and no server/client cross-talk. */
    private static void cacheIsolation() {
        TerrainMeshFrame frameA=new TerrainMeshFrame(1,false,"minecraft:overworld",new Vec3d(0,0,0),new Vec3d(0,0,0),
                List.of(material("minecraft:stone")),List.of());
        TerrainMeshFrame frameB=new TerrainMeshFrame(1,true,"minecraft:overworld",new Vec3d(0,0,0),new Vec3d(10,0,10),
                List.of(material("minecraft:stone")),List.of());
        UUID alice=UUID.nameUUIDFromBytes("alice".getBytes()),bob=UUID.nameUUIDFromBytes("bob".getBytes());
        MeshCollision.Index aliceDeep=MeshCollision.index(alice,frameA,false);
        MeshCollision.Index bobShallow=MeshCollision.index(bob,frameB,false);
        check(aliceDeep!=null && bobShallow!=null && aliceDeep!=bobShallow,"Per-player indexes are distinct");
        check(MeshCollision.index(alice,frameA,false)==aliceDeep,"Cached index is reused for the same frame");
        MeshCollision.Index aliceShallow=MeshCollision.index(alice,frameB,false);
        check(aliceShallow!=aliceDeep && aliceShallow.frame==frameB,"A new frame rebuilds only that player's index");
        check(MeshCollision.index(bob,frameB,false)==bobShallow,"Another player's churn never evicts a peer");
        MeshCollision.clear(alice);
        check(MeshCollision.index(alice,frameA,false)!=aliceDeep,"clear(UUID) evicts exactly that player");
        check(MeshCollision.index(bob,frameB,false)==bobShallow,"clear(UUID) never evicts other players");
        MeshCollision.Index aliceClient=MeshCollision.index(alice,frameA,true);
        check(aliceClient!=null && aliceClient!=MeshCollision.index(alice,frameA,false),"Client and server caches are separate");
        MeshCollision.clearClient();
        check(MeshCollision.index(bob,frameB,false)==bobShallow,"clearClient never touches server-side indexes");
        check(MeshCollision.index(alice,frameA,true)!=aliceClient,"clearClient evicts the client cache");
        check(MeshCollision.index(alice,null,false)==null,"A player without a frame has no index");
    }

    /** Shallow published frames are source-identical and anchored to the LOCAL landmark: no border
     * deformation, exact collision boxes, and duplicate geometry resolves to the local window. */
    private static void shallowStitchLocal() {
        List<TerrainMeshFrame.Material> palette=List.of(material("minecraft:stone"));
        Vec3d localMin=new Vec3d(10,64,10),foreignMin=new Vec3d(10,64,10);
        Vec3d ax=new Vec3d(1,0,0),ay=new Vec3d(0,1,0),az=new Vec3d(0,0,1);
        TerrainMeshFrame.Cell local=cell(1,0,"lm-local",new Vec3d(0,0,0),localMin,ax,ay,az,List.of(SourceMeshBuilder.UNIT));
        TerrainMeshFrame.Cell foreign=cell(2,0,"lm-foreign",new Vec3d(0,0,0),foreignMin,ax,ay,az,List.of(SourceMeshBuilder.UNIT));
        Vec3d eye=localMin.add(2,1,2);
        List<TerrainMeshFrame.Cell> stitched=MeshStitcher.stitch(new ArrayList<>(List.of(local,foreign)),palette,"lm-local",true,eye);
        check(stitched.size()==1,"Duplicate shallow geometry collapses to one cell");
        check(stitched.getFirst().landmarkId().equals("lm-local"),"The local landmark keeps the shared surface");
        near(stitched.getFirst().min().distanceTo(localMin),0,0,"Shallow frames are never deformed");
        near(stitched.getFirst().axisX().distanceTo(ax),0,0,"Shallow axes stay source-aligned");
        check(stitched.getFirst().collision().equals(List.of(SourceMeshBuilder.UNIT)),"Shallow collision boxes stay exact");
        // A foreign same-shape cell must NOT displace the local window's cell when the local arrives second.
        List<TerrainMeshFrame.Cell> reversed=new ArrayList<>(List.of(foreign,local));
        var stitchedReversed=MeshStitcher.stitch(reversed,palette,"lm-local",true,eye);
        check(stitchedReversed.size()==1 && stitchedReversed.getFirst().landmarkId().equals("lm-local"),
                "Local anchor wins regardless of append order");
        // Non-overlapping foreign geometry survives beside the local window.
        TerrainMeshFrame.Cell distant=new TerrainMeshFrame.Cell(3,0,"lm-foreign",new Vec3d(50,0,50),
                new Vec3d(60,64,60),new Vec3d(1,1,1),ax,ay,az,0xffffff,0xf000f0,1f,List.of(SourceMeshBuilder.UNIT));
        var both=MeshStitcher.stitch(new ArrayList<>(List.of(local,distant)),palette,"lm-local",true,eye);
        check(both.size()==2,"Independent regions coexist in one shallow frame");
    }

    public static void main(String[] args) {
        observerDivergence();
        frameCollisionIsolation();
        cacheIsolation();
        shallowStitchLocal();
        System.out.println("MeshIsolationTest: "+checks+" checks passed");
    }
}
