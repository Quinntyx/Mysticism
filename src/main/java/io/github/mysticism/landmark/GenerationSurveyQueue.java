package io.github.mysticism.landmark;

import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Lossless generation work, bounded by source chunk residency: at most MAX_PROBES
 * positions per still-loaded chunk, no snapshots/world/chunk references. Unlike recency
 * hints, unsatisfied components are never evicted by other chunks or delayed readiness.
 * One probe is dispatched per idle source operation; failed probes rotate fairly and
 * remain pending until exact source ownership has actually been published. Server thread. */
final class GenerationSurveyQueue {
    record Chunk(String dimension,int x,int z) {}
    private static final class Pending {
        final ArrayDeque<BlockPos> probes;int cursor;
        Pending(List<BlockPos> probes){this.probes=new ArrayDeque<>(probes);}
    }
    record Attempt(Chunk chunk,BlockPos position,Pending pending,int cursor) {}
    private final LinkedHashMap<Chunk,Pending> chunks=new LinkedHashMap<>();
    private int pendingProbes;

    void loaded(String dimension,int x,int z,List<BlockPos> probes){
        Objects.requireNonNull(dimension);Objects.requireNonNull(probes);
        if(probes.size()>GenerationSurvey.MAX_PROBES)throw new IllegalArgumentException("generation probe budget");
        List<BlockPos> positions=probes.stream().map(BlockPos::toImmutable).toList();
        Chunk key=new Chunk(dimension,x,z);
        for(var p:positions)if((p.getX()>>4)!=x||(p.getZ()>>4)!=z)throw new IllegalArgumentException("probe outside source chunk");
        remove(key);
        if(!positions.isEmpty()){chunks.put(key,new Pending(positions));pendingProbes+=positions.size();}
    }
    Optional<Attempt> next(boolean ready){
        if(!ready||chunks.isEmpty())return Optional.empty();
        var first=chunks.entrySet().iterator();var entry=first.next();
        Chunk key=entry.getKey();Pending work=entry.getValue();first.remove();
        chunks.put(key,work); // rotate even on failure: one unavailable patch cannot starve others
        return Optional.of(new Attempt(key,work.probes.getFirst(),work,work.cursor));
    }
    void completed(Attempt attempt,boolean ownsProbe){
        Pending work=chunks.get(attempt.chunk());
        // A completion from before unload/reload must never acknowledge new generation work.
        if(work!=attempt.pending()||work.cursor!=attempt.cursor())return;
        work.cursor++;
        BlockPos position=work.probes.removeFirst();
        if(ownsProbe)pendingProbes--;else work.probes.addLast(position);
        if(work.probes.isEmpty())chunks.remove(attempt.chunk());
    }
    void unloaded(String dimension,int x,int z){remove(new Chunk(dimension,x,z));}
    void unloaded(String dimension){
        var entries=chunks.entrySet().iterator();
        while(entries.hasNext()){var e=entries.next();if(e.getKey().dimension().equals(dimension)){pendingProbes-=e.getValue().probes.size();entries.remove();}}
    }
    private void remove(Chunk key){Pending old=chunks.remove(key);if(old!=null)pendingProbes-=old.probes.size();}
    int pending(){return pendingProbes;}
    int chunks(){return chunks.size();}
    void clear(){chunks.clear();pendingProbes=0;}
}
