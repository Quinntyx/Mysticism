package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** CPU-only revision/resource failure regressions using the actual renderer gate and affine SAT. */
public final class TerrainRevisionCoherenceTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static TerrainMeshFrame frame(long revision,boolean shallow,double x) {
        // Skewed mesh, not an axis-aligned carrier block. Model and collision share these columns.
        var cell=new TerrainMeshFrame.Cell(1,0,"owner",Vec3d.ZERO,new Vec3d(x,0,0),new Vec3d(1,1,1),
                new Vec3d(1,0,0),new Vec3d(.4,1,0),new Vec3d(0,0,1),0xffffff,0,1,List.of(new Box(0,0,0,1,1,1)));
        return new TerrainMeshFrame(revision,shallow,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cell));
    }
    private static final class Buffer implements AutoCloseable {
        int closes;boolean fail;
        @Override public void close(){closes++;if(fail)throw new IllegalStateException("injected cleanup failure");}
    }
    public static void main(String[] args) {
        var gate=new TerrainRevisionGate();var old=frame(1,true,0);var next=frame(2,false,10);
        check(gate.accept(old),"initial pending");check(gate.committed()==null,"nothing solid before first upload");
        check(gate.commit(old),"first display commit");
        var rayFrom=new Vec3d(-1,.5,.5);var rayTo=new Vec3d(2,.5,.5);
        check(!new MeshCollision.Index(gate.committed()).clearRay(rayFrom,rayTo),"old displayed mesh is solid");
        check(gate.accept(next) && gate.shallowFlipPending(),"mode flip staged, not yet predicted");
        check(gate.committed()==old,"packet arrival cannot change collision/occlusion");
        check(!new MeshCollision.Index(gate.committed()).clearRay(rayFrom,rayTo),"pending projection cannot move committed collider");
        Buffer first=new Buffer(),second=new Buffer();
        try(var build=new TerrainBatchBuild<Buffer>()) {
            build.add(first);build.add(second);throw new IllegalStateException("injected second-batch upload failure");
        } catch(IllegalStateException expected) {
            check(expected.getMessage().contains("second-batch"),"original upload failure preserved");
        }
        check(first.closes==1 && second.closes==1,"partial upload closes every staged GPU resource exactly once");
        check(gate.committed()==old && gate.pending()==next,"failed upload retains old display/prediction and retry candidate");
        var newer=frame(3,false,20);check(gate.accept(newer),"newest source/projection update coalesces pending");
        check(!gate.commit(next) && gate.committed()==old,"stale build cannot commit a newer unbuilt revision");
        check(!gate.accept(old) && !gate.accept(next) && !gate.accept(newer),"stale/duplicate frames do not regress");
        Buffer complete=new Buffer();List<Buffer> displayed;
        try(var build=new TerrainBatchBuild<Buffer>()){build.add(complete);displayed=build.complete();}
        check(complete.closes==0 && displayed.equals(List.of(complete)),"successful build transfers resources, does not close them");
        check(gate.commit(newer) && gate.committed()==newer && gate.pending()==null,"display and prediction promote the exact uploaded frame");
        check(new MeshCollision.Index(gate.committed()).clearRay(rayFrom,rayTo),"committed collision moves with displayed mesh");
        check(gate.commit(newer),"resource reload may rebuild current revision");
        check(!gate.commit(old) && !gate.commit(null),"unrelated resource rebuild cannot change collision");
        complete.close();check(complete.closes==1,"display owner closes transferred resources on replacement");
        Buffer failing=new Buffer(),after=new Buffer();failing.fail=true;
        try(var build=new TerrainBatchBuild<Buffer>()) {
            build.add(failing);build.add(after);throw new IllegalArgumentException("original failure");
        } catch(IllegalArgumentException expected) {
            check(expected.getSuppressed().length==1,"cleanup failure is suppressed, not substituted for upload failure");
        }
        check(after.closes==1,"one bad cleanup does not leak later staged resources");
        gate.clear();check(gate.committed()==null && gate.pending()==null,"exit/session reset clears both revisions");
        check(gate.accept(old) && gate.commit(old),"fresh entry can restart revision counter");
        System.out.println("Terrain revision coherence: "+checks+" checks passed (no GPU/game launch)");
    }
}
