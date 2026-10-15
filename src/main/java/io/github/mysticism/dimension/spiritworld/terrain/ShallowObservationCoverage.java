package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Exact session-local suppression of persisted snapshots, not source ownership. Full generated
 * patches use one bit per 16-cube instead of spending a 512-byte fragmented mask on every cube.
 * At the maximum 512-block horizon even a height-unbounded world spans at most 66 source buckets
 * per axis, hence at most 6^3 groups of 16^3 bits (108 KiB plus bounded map overhead).
 * Fragmented/UNKNOWN patches retain the separate 8192-mask bound; refusing one must NOT stop
 * acquisition of later full patches. No unobserved cell is masked to make room. */
final class ShallowObservationCoverage {
    static final int MAX_FULL_GROUPS=6*6*6;
    private final Map<BlockPos,BitSet> full=new HashMap<>();
    private final NegativeCoverage partial=new NegativeCoverage();
    private static BlockPos group(BlockPos p){return new BlockPos(p.getX()>>8,p.getY()>>8,p.getZ()>>8);}
    private static int index(BlockPos p){return ((p.getX()>>4)&15)|(((p.getY()>>4)&15)<<4)|(((p.getZ()>>4)&15)<<8);}
    boolean contains(BlockPos p) {
        BitSet bits=full.get(group(p));return bits!=null && bits.get(index(p)) || partial.contains(p);
    }
    int bucketCount(BlockPos p) {
        BitSet bits=full.get(group(p));return bits!=null && bits.get(index(p))?4096:partial.bucketCount(p);
    }
    /** Atomic admission of one proven generated patch (possibly clipped or containing UNKNOWN).
     * Call only after bounded ingestion has collected all its distinct, in-query observations. */
    boolean record(Set<BlockPos> patch) {
        if(patch.isEmpty())return true;
        BlockPos first=patch.iterator().next();long bucket=SourceMeshBuilder.bucket(first);
        if(patch.size()>4096 || patch.stream().anyMatch(p->SourceMeshBuilder.bucket(p)!=bucket))
            throw new IllegalArgumentException("observation patch must fit one source bucket");
        if(bucketCount(first)==4096)return true;
        if(patch.size()==4096)return recordFull(first);
        if(!partial.canRecord(first))return false;
        for(BlockPos p:patch)partial.add(p);
        if(partial.bucketCount(first)==4096)recordFull(first);
        return true;
    }
    private boolean recordFull(BlockPos p) {
        BlockPos key=group(p);
        if(!full.containsKey(key) && full.size()==MAX_FULL_GROUPS)return false;
        full.computeIfAbsent(key,ignored->new BitSet(4096)).set(index(p));
        // A clipped/fragmented bucket may have become fully known on a later sweep. Reclaim it.
        if(partial.bucketCount(p)>0) {
            BlockPos min=new BlockPos((p.getX()>>4)<<4,(p.getY()>>4)<<4,(p.getZ()>>4)<<4);
            for(BlockPos cell:BlockPos.iterate(min,min.add(15,15,15)))partial.remove(cell);
        }
        return true;
    }
    void retain(Bounds range) {
        partial.retain(range);
        var groups=full.entrySet().iterator();
        while(groups.hasNext()) {
            var entry=groups.next();BlockPos g=entry.getKey();BitSet bits=entry.getValue();
            for(int i=bits.nextSetBit(0);i>=0;i=bits.nextSetBit(i+1)) {
                long x=((long)g.getX()<<8)+((i&15)<<4),y=((long)g.getY()<<8)+(((i>>4)&15)<<4),z=((long)g.getZ()<<8)+((i>>8)<<4);
                if(!new Bounds(x,y,z,x+16,y+16,z+16).intersects(range))bits.clear(i);
            }
            if(bits.isEmpty())groups.remove();
        }
    }
    int fullGroups(){return full.size();}
}
