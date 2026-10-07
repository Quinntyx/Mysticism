package io.github.mysticism.activity;

import io.github.mysticism.landmark.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Activity half of extractor-proven topology changes. Never infers connectivity from vectors.
 * Prepare before core staging; commit immediately after that SAME serialized mutation publishes.
 * No repository geometry hydration, model work, source-world scans or catalogue snapshots.
 */
public final class LandmarkActivityTopology {
    static final int MAX_PARENTS=8, MAX_CHILDREN=8, MAX_PAGES=64, MAX_LEAVES=2048;
    static final int MAX_PAIR_WORK=65536, MAX_WRITES=512, MAX_OUTPUT_CELLS=512, MAX_LINEAGE=512;
    private LandmarkActivityTopology(){}
    /** Successful commit is idempotent. Cancel is idempotent and never rolls back a commit.
     * Commit after cancellation fails. All operations, including terminal calls, are thread confined. */
    public interface Plan { void commit(); void cancel(); }
    public static Plan prepareSplit(MinecraftServer server,LandmarkRepository.RevisionRef parent,List<Landmark> children){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        return prepareSplit(LandmarkStore.get(server),LandmarkActivityState.get(server),parent,children,server.getOverworld().getTime());
    }
    public static Plan prepareRemove(MinecraftServer server,List<LandmarkRepository.RevisionRef> parents){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        return prepareRemove(LandmarkStore.get(server),LandmarkActivityState.get(server),parents);
    }
    /** Optional extractor bridge: correct ONLY non-spatial child Ownership from the actual
     * occupancy-partitioned activity claims. Call under the same pause, then pass this
     * immutable result to prepareSplit AND the core split. Geometry/IDs/revisions stay exact. */
    public static List<Landmark> partitionSplitOwnership(MinecraftServer server,LandmarkRepository.RevisionRef parent,List<Landmark> children){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        return partitionSplitOwnership(LandmarkStore.get(server),LandmarkActivityState.get(server),parent,children,server.getOverworld().getTime());
    }
    static List<Landmark> partitionSplitOwnership(LandmarkStore store,LandmarkActivityState state,LandmarkRepository.RevisionRef ref,List<Landmark> supplied,long now){
        var input=splitInput(store,state,ref,supplied,now);var h=input.parent.header();
        var distributed=distribute(h,state.entries.get(h.id()),input.children,now,false);
        Map<String,Ownership.Claim> owners=new TreeMap<>();h.ownership().claims().forEach(c->owners.put(c.player().toString(),c));
        return input.children.stream().map(c->new Landmark(c.id(),c.dimension(),c.algorithmVersion(),c.kind(),c.biome(),c.anchor(),c.bounds(),c.baseEmbedding(),c.baseImportance(),c.activity(),
                new Ownership(distributed.get(c.id()).owners.stream().map(owners::get).toList()),c.geometry(),c.revision(),c.provenance())).toList();
    }
    private static LandmarkMetadata parent(LandmarkStore store,LandmarkRepository.RevisionRef ref){
        var value=store.metadata(ref.id()).orElseThrow(()->new IllegalStateException("missing topology parent"));
        if(!value.id().equals(ref.id())||value.revision()!=ref.revision())throw new IllegalStateException("stale/aliased topology parent");
        Math.incrementExact(ref.revision());
        if(store.tombstoneRevision(ref.id()).isPresent())throw new IllegalStateException("live parent has retirement history");
        return value;
    }
    private record SplitInput(LandmarkMetadata parent,List<Landmark> children){}
    private static SplitInput splitInput(LandmarkStore store,LandmarkActivityState state,LandmarkRepository.RevisionRef ref,List<Landmark> supplied,long now){
        var source=parent(store,ref);var h=source.header();
        if(now<0||supplied.size()<2||supplied.size()>MAX_CHILDREN)throw new IllegalArgumentException("split child/time/overlay budget");
        List<Landmark> children=supplied.stream().sorted(Comparator.comparing(Landmark::id)).toList();
        Set<String> ids=new HashSet<>();int pages=0,frontiers=0;
        for(var child:children){
            h.baseEmbedding().profile().requireCompatible(child.baseEmbedding().profile());
            if(!ids.add(child.id())||!h.dimension().equals(child.dimension())||!h.algorithmVersion().equals(child.algorithmVersion())
                    ||h.kind()!=child.kind()||!h.biome().equals(child.biome())||!h.bounds().contains(child.bounds())||child.revision()<=h.revision())
                throw new IllegalArgumentException("split source domain/revision");
            if(!child.id().equals(h.id())&&(store.tombstoneRevision(child.id()).isPresent()||!store.resolve(child.id()).equals(child.id())||store.metadata(child.id()).isPresent()||state.entries.containsKey(child.id())))
                throw new IllegalStateException("occupied split child identity/history");
            pages=Math.addExact(pages,child.geometry().pages().size());frontiers=Math.addExact(frontiers,child.geometry().frontiers().size());
        }
        if(pages>MAX_PAGES||frontiers>256)throw new IllegalArgumentException("split page/frontier budget");
        // Legacy aliases with separate histories cannot be safely summed (they may duplicate
        // the canonical history). Refuse rather than guessing or silently leaking their quotas.
        for(String id:state.entries.keySet())if(!id.equals(h.id())&&store.resolve(id).equals(h.id()))
            throw new IllegalStateException("aliased split history requires migration");
        return new SplitInput(source,children);
    }
    static Plan prepareSplit(LandmarkStore store,LandmarkActivityState state,LandmarkRepository.RevisionRef ref,List<Landmark> supplied,long now){
        var input=splitInput(store,state,ref,supplied,now);var h=input.parent.header();var children=input.children;
        var history=state.entries.get(h.id());
        var outputs=distribute(h,history,children,now);
        TreeMap<String,Snapshot> captured=new TreeMap<>();captured.put(h.id(),Snapshot.of(history));
        for(var child:children)captured.putIfAbsent(child.id(),Snapshot.of(state.entries.get(child.id())));
        validateBudget(state,captured,outputs);
        return new Prepared(store,state,List.of(ref),captured,outputs,children);
    }
    static Plan prepareRemove(LandmarkStore store,LandmarkActivityState state,List<LandmarkRepository.RevisionRef> supplied){
        if(supplied.isEmpty()||supplied.size()>MAX_PARENTS)throw new IllegalArgumentException("remove budget");
        List<LandmarkRepository.RevisionRef> refs=supplied.stream().sorted(Comparator.comparing(LandmarkRepository.RevisionRef::id)).toList();
        Set<String> ids=new HashSet<>();for(var ref:refs){parent(store,ref);if(!ids.add(ref.id()))throw new IllegalArgumentException("duplicate remove parent");}
        TreeMap<String,Snapshot> captured=new TreeMap<>();for(String id:ids)captured.put(id,Snapshot.of(state.entries.get(id)));
        // Only the capped activity map is enumerated; resolve does not hydrate geometry.
        for(var entry:state.entries.entrySet())if(ids.contains(store.resolve(entry.getKey())))captured.put(entry.getKey(),Snapshot.of(entry.getValue()));
        return new Prepared(store,state,refs,captured,new TreeMap<>(),List.of());
    }
    private record Snapshot(LandmarkActivityState.Influence original,int[] bits,double level,double base,long tick,
                            SparseOctree<String> claims,Set<String> owners){
        static Snapshot of(LandmarkActivityState.Influence value){
            if(value!=null&&value.owners.size()>16)throw new IllegalArgumentException("captured owner budget");
            return value==null?new Snapshot(null,null,0,0,0,null,Set.of()):new Snapshot(value,value.vector.toBits(),value.level,value.mergedBase,value.tick,value.claims,Set.copyOf(value.owners));
        }
        boolean current(LandmarkActivityState.Influence value){
            return value==original&&(value==null||(Arrays.equals(bits,value.vector.toBits())&&Double.doubleToLongBits(level)==Double.doubleToLongBits(value.level)
                    &&Double.doubleToLongBits(base)==Double.doubleToLongBits(value.mergedBase)&&tick==value.tick&&Objects.equals(claims,value.claims)&&owners.equals(value.owners)));
        }
    }
    private static final class Prepared implements Plan {
        final LandmarkStore store;final LandmarkActivityState state;final List<LandmarkRepository.RevisionRef> parents;
        final Map<String,Snapshot> captured;final NavigableMap<String,LandmarkActivityState.Influence> outputs;final List<Landmark> children;
        final Map<String,List<String>> previousLineage=new TreeMap<>();
        final Thread owner=Thread.currentThread();boolean committed,cancelled;
        Prepared(LandmarkStore store,LandmarkActivityState state,List<LandmarkRepository.RevisionRef> parents,Map<String,Snapshot> captured,
                 NavigableMap<String,LandmarkActivityState.Influence> outputs,List<Landmark> children){
            this.store=store;this.state=state;this.parents=List.copyOf(parents);this.captured=captured;this.outputs=outputs;this.children=children;
            for(var ref:parents)previousLineage.put(ref.id(),store.lineageChildren(ref.id(),MAX_LINEAGE));
        }
        private void thread(){if(owner!=Thread.currentThread())throw new IllegalStateException("activity topology thread");}
        @Override public void cancel(){thread();if(!committed)cancelled=true;}
        @Override public void commit(){
            thread();if(committed)return;if(cancelled)throw new IllegalStateException("cancelled activity topology");
            for(var entry:captured.entrySet())if(!entry.getValue().current(state.entries.get(entry.getKey())))throw new IllegalStateException("stale topology history");
            Set<String> childIds=new HashSet<>();for(var child:children){
                childIds.add(child.id());var actual=store.metadata(child.id()).orElseThrow(()->new IllegalStateException("core split not published"));
                if(!store.resolve(child.id()).equals(child.id())||!actual.header().equals(header(child))||!actual.geometryKeys().equals(geometryKeys(child)))
                    throw new IllegalStateException("core split result differs/current revision changed");
            }
            for(var ref:parents){
                if(!store.resolve(ref.id()).equals(ref.id())||(!childIds.contains(ref.id())&&store.metadata(ref.id()).isPresent()))
                    throw new IllegalStateException("core parent not retired or alias redirected");
                var retired=store.tombstoneRevision(ref.id());
                if(childIds.contains(ref.id())?retired.isPresent():retired.isEmpty()||retired.getAsLong()!=Math.incrementExact(ref.revision()))
                    throw new IllegalStateException("wrong/missing parent retirement revision");
                List<String> expected=children.isEmpty()?previousLineage.get(ref.id()):children.stream().map(Landmark::id).sorted().toList();
                if(!store.lineageChildren(ref.id(),MAX_LINEAGE).equals(expected))throw new IllegalStateException("core split/delete lineage differs");
            }
            Set<String> parentIds=new HashSet<>();parents.forEach(ref->parentIds.add(ref.id()));
            for(String id:captured.keySet())if(!childIds.contains(id)&&!parentIds.contains(id)&&!parentIds.contains(store.resolve(id)))
                throw new IllegalStateException("captured alias changed");
            validateBudget(state,captured,outputs);
            // All guards precede all dirty/count changes. No callbacks, IO or model work below.
            captured.keySet().forEach(state::remove);outputs.forEach(state::publish);committed=true;
        }
    }
    private static Landmark header(Landmark value){
        return new Landmark(value.id(),value.dimension(),value.algorithmVersion(),value.kind(),value.biome(),value.anchor(),value.bounds(),value.baseEmbedding(),value.baseImportance(),value.activity(),value.ownership(),
                new SourceGeometry(List.of(),value.geometry().frontiers()),value.revision(),value.provenance());
    }
    private static List<String> geometryKeys(Landmark value){
        // Immutable schema-1 page key format documented in landmark/CORE_API.md. Compare
        // exact versions, never interpret the keys as masks or perform a geometry read.
        return value.geometry().pages().stream().map(p->"mysticism.landmark.geometry."+p.id()+"."+p.revision()).toList();
    }
    private static void validateBudget(LandmarkActivityState state,Map<String,Snapshot> captured,Map<String,LandmarkActivityState.Influence> outputs){
        long removed=captured.values().stream().filter(v->v.original!=null).count();
        // No global overlay/catalog cardinality cutoff; children and claims per operation remain bounded.
        Map<String,Integer> delta=new TreeMap<>();captured.values().forEach(s->s.owners.forEach(o->delta.merge(o,-1,Integer::sum)));
        for(var next:outputs.values()){
            if(next.owners.size()>16)throw new IllegalArgumentException("child owner capacity");
            next.owners.forEach(o->delta.merge(o,1,Integer::sum));
            if(next.claims!=null)next.claims.cells(LandmarkActivityState.CELL_LIMIT);
        }
        for(var entry:delta.entrySet()){int count=state.claimedBy(UUID.fromString(entry.getKey()))+entry.getValue();if(count<0||count>8)throw new IllegalArgumentException("topology player quota");}
    }
    private record Observed(int child,Bounds bounds){}
    static NavigableMap<String,LandmarkActivityState.Influence> distribute(Landmark parent,LandmarkActivityState.Influence history,List<Landmark> children,long now){
        return distribute(parent,history,children,now,true);
    }
    private static NavigableMap<String,LandmarkActivityState.Influence> distribute(Landmark parent,LandmarkActivityState.Influence history,List<Landmark> children,long now,boolean checkOwnership){
        if(children.size()<2||children.size()>MAX_CHILDREN||now<0||(history!=null&&now<history.tick))throw new IllegalArgumentException("split budget/time reversal");
        List<Observed> known=new ArrayList<>();long[] volumes=new long[children.size()];int pages=0;
        for(int i=0;i<children.size();i++)for(var page:children.get(i).geometry().pages()){
            if(++pages>MAX_PAGES)throw new IllegalArgumentException("split page budget");
            for(var cell:page.cells().cells(MAX_LEAVES-known.size())){
                known.add(new Observed(i,cell.bounds()));volumes[i]=Math.addExact(volumes[i],volume(cell.bounds()));
            }
        }
        if((long)known.size()*pages>MAX_PAIR_WORK)throw new IllegalArgumentException("split geometry work budget");
        for(var cell:known)for(int i=0;i<children.size();i++)if(i!=cell.child)for(var page:children.get(i).geometry().pages())
            if(page.bounds().intersects(cell.bounds)&&!page.cells().query(cell.bounds,1).isEmpty())throw new IllegalArgumentException("overlapping child source occupancy");
        long total=0;for(long v:volumes){if(v==0)throw new IllegalArgumentException("child source occupancy unknown/empty");total=Math.addExact(total,v);}
        if(history!=null&&(history.tick<0||!Double.isFinite(history.mergedBase)||(history.mergedBase<0&&history.mergedBase!=-1)
                ||!Double.isFinite(history.level)||history.level<0||history.level>LandmarkActivityState.MAX_IMPORTANCE_MASS))throw new IllegalArgumentException("invalid captured history");
        var vector=history==null?parent.baseEmbedding().vector():history.vector;
        // Persisted activity space is pinned by the existing common embedding profile, not
        // by any extractor import or guessed dimension-compatible replacement model.
        var p=io.github.mysticism.embedding.EmbeddingProfile.current();
        parent.baseEmbedding().profile().requireCompatible(new EmbeddingProfile(p.model(),p.revision(),"model-manifest:"+p.revision(),p.semantics(),p.dimensions(),EmbeddingProfile.Normalization.UNIT,p.fingerprint()));
        new LandmarkEmbedding(parent.baseEmbedding().profile(),vector);
        double base=history==null?parent.baseImportance():history.base(parent.baseImportance());double level=history==null?0:history.level(now);
        if(!Double.isFinite(base)||!Double.isFinite(level)||base<0||level<0||base+level>LandmarkActivityState.MAX_IMPORTANCE_MASS)throw new IllegalArgumentException("split history mass");
        TreeMap<String,LandmarkActivityState.Influence> outputs=new TreeMap<>();List<LandmarkActivityState.Influence> next=new ArrayList<>();double bases=base,levels=level;
        for(int i=0;i<children.size();i++){
            double b=i==children.size()-1?bases:Math.min(bases,base*((double)volumes[i]/total));
            double l=i==children.size()-1?levels:Math.min(levels,level*((double)volumes[i]/total));bases-=b;levels-=l;
            var value=new LandmarkActivityState.Influence(vector,l,now);value.mergedBase=b;next.add(value);outputs.put(children.get(i).id(),value);
        }
        var claims=history==null||history.claims==null?List.<SparseOctree.Cell<String>>of():history.claims.cells(LandmarkActivityState.CELL_LIMIT);
        if((long)claims.size()*known.size()>MAX_PAIR_WORK)throw new IllegalArgumentException("split claim work budget");
        int writes=0;
        for(var claim:claims){long covered=0;
            for(var cell:known)if(claim.bounds().intersects(cell.bounds)){
                if(++writes>MAX_WRITES)throw new IllegalArgumentException("split claim write budget");
                Bounds part=intersection(claim.bounds(),cell.bounds);covered=Math.addExact(covered,volume(part));var value=next.get(cell.child);
                if(value.claims==null)value.claims=SparseOctree.empty(history.claims.rootBounds(),1,512);
                value.claims=value.claims.with(part,claim.value(),256);value.owners.add(claim.value());
                value.claims.cells(LandmarkActivityState.CELL_LIMIT);
            }
            if(covered!=volume(claim.bounds()))throw new IllegalArgumentException("claim outside known child occupancy; cannot discard history");
        }
        int outputCells=0;for(var value:next)if(value.claims!=null)outputCells=Math.addExact(outputCells,value.claims.cells(LandmarkActivityState.CELL_LIMIT).size());
        if(outputCells>MAX_OUTPUT_CELLS)throw new IllegalArgumentException("split total claim capacity");
        Map<String,Ownership.Claim> owners=new TreeMap<>();for(var claim:parent.ownership().claims())owners.put(claim.player().toString(),claim);
        if(owners.size()>16||(history!=null&&!owners.keySet().containsAll(history.owners)))throw new IllegalArgumentException("missing authoritative owner history");
        for(var value:next)if(!owners.keySet().containsAll(value.owners))throw new IllegalArgumentException("spatial claim lacks authoritative owner");
        // Non-spatial owner history has no inferred location. Retain it ONCE on the surviving
        // source seed (or minimum new ID), never manufacture cells or copy it to every child.
        int heir=0;for(int i=0;i<children.size();i++)if(children.get(i).id().equals(parent.id()))heir=i;
        for(String owner:owners.keySet())if(next.stream().noneMatch(value->value.owners.contains(owner)))next.get(heir).owners.add(owner);
        for(int i=0;i<children.size();i++){
            var expected=new Ownership(next.get(i).owners.stream().map(owners::get).toList());
            if(checkOwnership&&!children.get(i).ownership().equals(expected))throw new IllegalArgumentException("extractor child ownership must match actual source claims (not cloned parent)");
        }
        return outputs;
    }
    private static long volume(Bounds b){return Math.multiplyExact(Math.multiplyExact(b.maxX()-b.minX(),b.maxY()-b.minY()),b.maxZ()-b.minZ());}
    private static Bounds intersection(Bounds a,Bounds b){return new Bounds(Math.max(a.minX(),b.minX()),Math.max(a.minY(),b.minY()),Math.max(a.minZ(),b.minZ()),Math.min(a.maxX(),b.maxX()),Math.min(a.maxY(),b.maxY()),Math.min(a.maxZ(),b.maxZ()));}
}
