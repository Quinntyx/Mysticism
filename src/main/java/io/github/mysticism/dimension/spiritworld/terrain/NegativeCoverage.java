package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Session-local negative source observations. Evicting a Tile must not forget that an old
 * persisted SOLID was observed as AIR. Packed masks survive stream restarts and do not assign
 * ownership. Neither masks nor tile admission can grow without bound. At mask exhaustion AIR
 * stays in the exact tile cache instead of silently resurrecting old geometry. */
final class NegativeCoverage extends AbstractSet<BlockPos> {
    static final int MAX_BUCKETS=8192; // <=4 MiB of bits/window, plus bounded map overhead
    private final Map<BlockPos,BitSet> buckets=new HashMap<>();
    private int size;
    private static BlockPos bucket(BlockPos p){return new BlockPos(p.getX()>>4,p.getY()>>4,p.getZ()>>4);}
    private static int index(BlockPos p){return (p.getX()&15)|((p.getY()&15)<<4)|((p.getZ()&15)<<8);}
    boolean canRecord(BlockPos p){return buckets.containsKey(bucket(p)) || buckets.size()<MAX_BUCKETS;}
    @Override public boolean add(BlockPos p) {
        if(!canRecord(p))return false;
        BitSet bits=buckets.computeIfAbsent(bucket(p),ignored->new BitSet(4096));int i=index(p);
        if(bits.get(i))return false;bits.set(i);size++;return true;
    }
    @Override public boolean contains(Object value) {
        if(!(value instanceof BlockPos p))return false;
        BitSet bits=buckets.get(bucket(p));return bits!=null && bits.get(index(p));
    }
    @Override public boolean remove(Object value) {
        if(!(value instanceof BlockPos p))return false;
        BlockPos key=bucket(p);BitSet bits=buckets.get(key);int i=index(p);
        if(bits==null || !bits.get(i))return false;
        bits.clear(i);size--;if(bits.isEmpty())buckets.remove(key);return true;
    }
    @Override public int size(){return size;}
    @Override public Iterator<BlockPos> iterator() {
        Iterator<Map.Entry<BlockPos,BitSet>> entries=buckets.entrySet().iterator();
        return new Iterator<>() {
            Map.Entry<BlockPos,BitSet> entry;int bit=-1;
            public boolean hasNext() {
                while(bit<0 && entries.hasNext()){entry=entries.next();bit=entry.getValue().nextSetBit(0);}
                return bit>=0;
            }
            public BlockPos next() {
                if(!hasNext())throw new NoSuchElementException();
                BlockPos p=entry.getKey();int i=bit;bit=entry.getValue().nextSetBit(i+1);
                return new BlockPos((p.getX()<<4)+(i&15),(p.getY()<<4)+((i>>4)&15),(p.getZ()<<4)+(i>>8));
            }
        };
    }
    void copyFrom(NegativeCoverage other) {
        buckets.clear();other.buckets.forEach((p,bits)->buckets.put(p,(BitSet)bits.clone()));size=other.size;
    }
    Map<Long,Integer> bucketCounts() {
        Map<Long,Integer> counts=new HashMap<>();
        buckets.forEach((p,bits)->counts.put(SourceMeshBuilder.bucket(new BlockPos(p.getX()<<4,p.getY()<<4,p.getZ()<<4)),bits.cardinality()));
        return counts;
    }
    /** Union view without duplicating potentially millions of negative cells into a HashSet. */
    Set<BlockPos> covering(Set<BlockPos> sampled) {
        NegativeCoverage self=this;
        return new AbstractSet<>() {
            public boolean contains(Object p){return sampled.contains(p)||self.contains(p);}
            public int size(){int count=self.size();for(BlockPos p:sampled)if(!self.contains(p))count++;return count;}
            public Iterator<BlockPos> iterator() {
                Iterator<BlockPos> a=sampled.iterator(),b=self.iterator();
                return new Iterator<>() {
                    BlockPos next;
                    public boolean hasNext(){if(next!=null)return true;if(a.hasNext()){next=a.next();return true;}
                        while(b.hasNext()){BlockPos p=b.next();if(!sampled.contains(p)){next=p;return true;}}return false;}
                    public BlockPos next(){if(!hasNext())throw new NoSuchElementException();BlockPos p=next;next=null;return p;}
                };
            }
        };
    }
}
