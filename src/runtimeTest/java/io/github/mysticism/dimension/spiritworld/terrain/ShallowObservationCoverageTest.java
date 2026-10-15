package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import net.minecraft.util.math.*;
import java.util.*;

/** Production mask admission/subtraction with no world or model bootstrap. */
public final class ShallowObservationCoverageTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Set<BlockPos> cube(BlockPos p) {
        Set<BlockPos> cells=new HashSet<>();for(BlockPos at:BlockPos.iterate(p,p.add(15,15,15)))cells.add(at.toImmutable());return cells;
    }
    private static void fullAfterFragmentedExhaustion() {
        var coverage=new ShallowObservationCoverage();
        // All partial masks fit in the maximum native horizon, rather than an unrealistic line
        // thousands of chunks long. UNKNOWN cells are not implicitly marked observed.
        for(int i=0;i<NegativeCoverage.MAX_BUCKETS;i++) {
            BlockPos p=new BlockPos((i%32)*16-256,((i/32)%8)*16,(i/256)*16-256);
            check(coverage.record(Set.of(p)),"Partial admission stopped before the bound");
            check(coverage.bucketCount(p)==1 && !coverage.contains(p.add(1,0,0)),"Partial observation masked UNKNOWN cells");
        }
        BlockPos later=new BlockPos(-512,128,-512);
        check(!coverage.record(Set.of(later)),"Fragmented retention must remain bounded");
        check(!coverage.contains(later),"Refused patch was partly published into the mask");
        check(coverage.record(cube(later)),"Full acquisition cannot be stopped by fragmented-mask exhaustion");
        check(coverage.bucketCount(later)==4096 && coverage.contains(later.add(15,15,15)),"Full observation lost exact coverage");
        check(coverage.record(cube(new BlockPos(-256,0,-256))),"Promoting a partial mask to full failed");
        check(coverage.record(Set.of(new BlockPos(496,144,496))),"Full promotion did not free the partial-mask slot");
        check(coverage.fullGroups()<=ShallowObservationCoverage.MAX_FULL_GROUPS,"Compact full masks exceeded their bound");
    }
    private static void promotionAndRecenter() {
        var coverage=new ShallowObservationCoverage();BlockPos origin=new BlockPos(-16,-16,-16);
        Set<BlockPos> all=cube(origin),first=new HashSet<>(),second=new HashSet<>();
        for(BlockPos p:all)(p.getY()<-8?first:second).add(p);
        check(coverage.record(first) && coverage.bucketCount(origin)==2048,"Clipped patch changed its exact observation count");
        check(coverage.record(second) && coverage.bucketCount(origin)==4096,"Union of clipped patches did not promote to full");
        for(BlockPos p:all)check(coverage.contains(p),"Negative-coordinate compact mask changed coordinates");
        BlockPos other=new BlockPos(512,64,512);check(coverage.record(cube(other)),"Second compact group admission failed");
        coverage.retain(new Bounds(-32,-32,-32,16,16,16));
        check(coverage.bucketCount(other)==0 && !coverage.contains(other),"Recenter leaked out-of-range full observations");
        check(coverage.bucketCount(origin)==4096 && coverage.fullGroups()==1,"Recenter forgot in-range full observations");
        coverage.retain(new Bounds(2048,0,2048,2064,16,2064));
        check(coverage.fullGroups()==0 && !coverage.contains(origin),"Teleport retained stale compact masks");
    }
    private static void persistedSubtractionIsExact() {
        var coverage=new ShallowObservationCoverage();BlockPos origin=new BlockPos(-16,48,-16);
        coverage.record(cube(origin));BlockPos partial=new BlockPos(2,48,2);coverage.record(Set.of(partial));
        var tile=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:stone",Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0xf000f0,false,true);
        List<SourceMeshBuilder.Node> base=List.of(new SourceMeshBuilder.Node(origin,16,tile,"source"),
                new SourceMeshBuilder.Node(new BlockPos(0,48,0),16,tile,"source"));
        List<SourceMeshBuilder.Node> kept=SourceMeshBuilder.replaceObserved(base,List.of(),coverage::contains,coverage::bucketCount,Vec3d.ZERO);
        for(BlockPos p:BlockPos.iterate(-16,48,-16,15,63,15)) {
            boolean observed=coverage.contains(p);
            boolean solid=kept.stream().anyMatch(n->p.getX()>=n.position().getX() && p.getX()<n.position().getX()+n.side()
                    && p.getY()>=n.position().getY() && p.getY()<n.position().getY()+n.side()
                    && p.getZ()>=n.position().getZ() && p.getZ()<n.position().getZ()+n.side());
            // Other two quadrants were never supplied in base.
            boolean supplied=p.getX()<0 && p.getZ()<0 || p.getX()>=0 && p.getZ()>=0;
            check(solid==(supplied && !observed),"Mask subtraction resurrected observed geometry or erased UNKNOWN persisted cells at "+p);
        }
    }
    public static void main(String[] args) {
        fullAfterFragmentedExhaustion();promotionAndRecenter();persistedSubtractionIsExact();
        System.out.println("ShallowObservationCoverageTest: "+checks+" checks passed (compact full/fragmented masks, exact subtraction)");
    }
}
