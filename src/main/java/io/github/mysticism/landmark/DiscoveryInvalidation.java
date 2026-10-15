package io.github.mysticism.landmark;

import java.util.*;
import java.util.function.Predicate;

/** Server-thread refresh bookkeeping. Queue overflow is handed back for a conservative,
 * resumable area scan; it must never silently acknowledge an edit or generation event. */
final class DiscoveryInvalidation {
    private DiscoveryInvalidation() {}
    static final String REFRESH_FRONTIER="observed-refresh";
    interface Retry {
        String dimension();
        Bounds bounds();
        boolean started();
        /** Must restart an already-started scan after extending its range. */
        void extend(Retry additional);
        default void cancel() {}
    }
    /** Normally <=capacity entries. Overflow unions disjoint areas of the same dimension,
     * restarting if necessary, rather than losing old work. With >capacity loaded dimensions
     * one entry per dimension is retained: memory is bounded by capacity + loaded worlds. */
    static final class RetryQueue<E extends Retry> {
        private final int capacity;
        private final ArrayDeque<E> retries=new ArrayDeque<>();
        RetryQueue(int capacity){if(capacity<1)throw new IllegalArgumentException("retry queue capacity");this.capacity=capacity;}
        void offer(E retry) {
            Objects.requireNonNull(retry);
            for(E existing:retries)if(!existing.started()&&existing.dimension().equals(retry.dimension())&&existing.bounds().intersects(retry.bounds())){existing.extend(retry);return;}
            if(retries.size()>=capacity)for(E existing:retries)if(existing.dimension().equals(retry.dimension())){existing.extend(retry);return;}
            retries.addLast(retry);
        }
        /** Rotation is not new work: do not union it back into another started scan or reset
         * its captured immutable page cursor. The polled slot is still reserved. */
        void requeue(E retry){retries.addLast(Objects.requireNonNull(retry));}
        /** One bounded step, then rotate WITHOUT treating cursor progress as new work.
         * Keep the failed entry at the head so the caller can cancel/retry it explicitly. */
        void advance(java.util.function.Predicate<E> step){
            E retry=poll();if(retry==null)return;
            try{if(!step.test(retry))requeue(retry);}
            catch(RuntimeException failure){retries.addFirst(retry);throw failure;}
        }
        E peek(){return retries.peekFirst();}
        E poll(){return retries.pollFirst();}
        void removeIf(Predicate<E> filter){retries.removeIf(r->{if(!filter.test(r))return false;r.cancel();return true;});}
        void clear(){retries.forEach(Retry::cancel);retries.clear();}
        int size(){return retries.size();}
    }

    /** Cover an overflow area with 25-cube observations (24-block stride). State is constant
     * size even for distant bursts. Conservative union can add latency, never skip an area. */
    static final class Sweep {
        private final Bounds bounds;
        private long x,y,z;
        private boolean end;
        Sweep(Bounds bounds){this.bounds=bounds;x=center(bounds.minX(),bounds.maxX());y=center(bounds.minY(),bounds.maxY());z=center(bounds.minZ(),bounds.maxZ());}
        private static long center(long min,long max){return Math.min(min+12,max-1);}
        BlockPoint poll(){
            if(end)return null;
            var point=new BlockPoint(x,y,z);
            if(x+12<bounds.maxX()-1)x+=24;
            else {x=center(bounds.minX(),bounds.maxX());if(z+12<bounds.maxZ()-1)z+=24;
                else {z=center(bounds.minZ(),bounds.maxZ());if(y+12<bounds.maxY()-1)y+=24;else end=true;}}
            return point;
        }
    }

    /** A face is retired only after its missing area is actually owned. Merely reading it
     * (possibly under another biome/seed) is not proof that discovery absorbed the frontier. */
    static List<FrontierFace> retainFrontiers(List<FrontierFace> prior,Map<BlockPoint,SourceLandmarks.Cell> observed,
                                             Map<BlockPoint,?> owned,long revision) {
        List<FrontierFace> result=new ArrayList<>();
        for(var face:prior){
            Bounds b=face.missingBounds();
            long volume=Math.multiplyExact(Math.multiplyExact(b.maxX()-b.minX(),b.maxY()-b.minY()),b.maxZ()-b.minZ());
            boolean absorbed=volume<=32768;
            boolean seen=false;
            if(absorbed)for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++){
                var p=new BlockPoint(x,y,z);absorbed&=owned.containsKey(p);seen|=observed.containsKey(p);
            }
            if(!absorbed)result.add(new FrontierFace(face.dimension(),b,face.direction(),revision,seen?REFRESH_FRONTIER:face.cursor()));
        }
        return List.copyOf(result);
    }
    static void requireCurrentParents(LandmarkStore store,long ownershipRevision,List<Landmark> parents){
        if(ownershipRevision!=store.ownershipRevision())throw new IllegalStateException("stale source catalog");
        // Activity-only CAS leaves source masks unchanged. It is safe to reuse the prepared
        // source geometry only with identical page references; the publisher MUST rebase its
        // activity, ownership, base vector and CAS revision from current metadata under pause.
        for(var parent:parents){
            var live=store.metadata(parent.id()).orElseThrow(()->new IllegalStateException("stale landmark revision"));
            var keys=parent.geometry().pages().stream().map(p->"mysticism.landmark.geometry."+p.id()+"."+p.revision()).toList();
            if(!live.id().equals(parent.id())||!new HashSet<>(live.geometryKeys()).equals(new HashSet<>(keys)))throw new IllegalStateException("stale source geometry");
        }
    }
    static boolean transientStaleness(Throwable failure) {
        while(failure instanceof java.util.concurrent.CompletionException&&failure.getCause()!=null)failure=failure.getCause();
        return failure instanceof IllegalStateException&&Set.of("stale landmark revision","stale source geometry","stale source catalog","stale transfer geometry").contains(failure.getMessage());
    }
}
