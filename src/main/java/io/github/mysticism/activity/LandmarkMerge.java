package io.github.mysticism.activity;

import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Activity half of a physically verified local union. Never infers connectivity from embeddings. */
public final class LandmarkMerge {
    static final double SEMANTIC_RADIUS=0.2;
    private LandmarkMerge(){}
    /** Rendering score only. Conserved local source/activity mass lives in the persisted overlay. */
    public static double importance(MinecraftServer server,LandmarkMetadata landmark){
        var influence=LandmarkActivityState.get(server).entries.get(landmark.id());
        long now=server.getOverworld().getTime();return influence==null?landmark.header().baseImportance()*LandmarkActivityState.decay(now-landmark.header().activity().evaluatedTick()):influence.importance(landmark.header().baseImportance(),now);
    }
    /** Extractor calls BEFORE staging its real core proof/reconciled geometry, then commit()
     * immediately AFTER that core mutation completes. Cancellation never publishes history. */
    public static Plan prepare(MinecraftServer server,LandmarkRepository.VerifiedConnectivity proof){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        return prepare(LandmarkStore.get(server),LandmarkActivityState.get(server),proof,server.getOverworld().getTime());
    }
    static Plan prepare(LandmarkStore store,LandmarkActivityState state,LandmarkRepository.VerifiedConnectivity proof,long now){
        if(proof.fragments().size()<2||proof.fragments().size()>8)throw new IllegalArgumentException("merge budget");
        List<LandmarkMetadata> fragments=new ArrayList<>();
        for(var ref:proof.fragments()){
            var m=store.metadata(ref.id()).orElseThrow();
            if(!m.id().equals(ref.id())||m.revision()!=ref.revision())throw new IllegalStateException("stale/aliased merge source");
            fragments.add(m);
        }
        fragments.sort(Comparator.comparing(LandmarkMetadata::id));
        List<LandmarkActivityState.Influence> history=new ArrayList<>();
        for(var m:fragments)history.add(state.entries.get(m.id()));
        var combined=combine(fragments,history,now);
        validateBudget(state,history,combined);
        return new Plan(store,state,List.copyOf(fragments),history,combined);
    }
    private static void validateBudget(LandmarkActivityState state,List<LandmarkActivityState.Influence> history,
                                       LandmarkActivityState.Influence combined){
        for(String owner:combined.owners){
            long removed=history.stream().filter(Objects::nonNull).filter(h->h.owners.contains(owner)).count();
            if(state.claimedBy(UUID.fromString(owner))-removed+1>8)throw new IllegalArgumentException("player claim quota");
        }
    }
    public static final class Plan {
        private final LandmarkStore store;private final LandmarkActivityState state;
        private final List<LandmarkMetadata> fragments;private final List<LandmarkActivityState.Influence> history;
        private final LandmarkActivityState.Influence combined;
        private boolean finished;
        private Plan(LandmarkStore store,LandmarkActivityState state,List<LandmarkMetadata> fragments,
                     List<LandmarkActivityState.Influence> history,LandmarkActivityState.Influence combined){
            this.store=store;this.state=state;this.fragments=fragments;this.history=history;this.combined=combined;
        }
        public void cancel(){finished=true;}
        public void commit(){
            if(finished)throw new IllegalStateException("finished activity merge");
            String canonical=fragments.getFirst().id();
            long revision=Math.incrementExact(fragments.stream().mapToLong(LandmarkMetadata::revision).max().orElseThrow());
            var published=store.metadata(canonical).orElseThrow();
            if(published.revision()!=revision||!published.header().anchor().equals(fragments.getFirst().header().anchor()))
                throw new IllegalStateException("core merge not committed/current");
            for(int i=0;i<fragments.size();i++){
                String id=fragments.get(i).id();
                if(!store.resolve(id).equals(canonical)||state.entries.get(id)!=history.get(i))throw new IllegalStateException("stale activity merge");
            }
            validateBudget(state,history,combined);
            // All guards precede any mutation. Core aliases/ownership/source seed stay authoritative.
            for(var m:fragments)state.remove(m.id());state.publish(canonical,combined);finished=true;
        }
    }
    static LandmarkActivityState.Influence combine(List<LandmarkMetadata> fragments,
            List<LandmarkActivityState.Influence> history,long now){
        if(fragments.size()<2||fragments.size()>8||history.size()!=fragments.size()||now<0)
            throw new IllegalArgumentException("merge budget");
        // Determinism does not rely on proof arrival order.
        List<Integer> order=new ArrayList<>();for(int i=0;i<fragments.size();i++)order.add(i);
        order.sort(Comparator.comparing(i->fragments.get(i).id()));
        var seed=fragments.get(order.getFirst()).header();Bounds union=seed.bounds();
        Set<String> ids=new HashSet<>();
        List<Vec384f> vectors=new ArrayList<>();List<Double> weights=new ArrayList<>();
        double bases=0,levels=0;int cells=0;
        var combined=new LandmarkActivityState.Influence(seed.baseEmbedding().vector(),0,now);
        for(int i:order){
            var h=fragments.get(i).header();var influence=history.get(i);
            LandmarkProfiles.current().requireCompatible(h.baseEmbedding().profile());
            if(!ids.add(h.id())||!h.dimension().equals(seed.dimension())||!h.algorithmVersion().equals(seed.algorithmVersion()))throw new IllegalArgumentException("merge domain");
            union=union.union(h.bounds());
            Vec384f vector=influence==null?h.baseEmbedding().vector():influence.vector;
            for(Vec384f other:vectors)if(vector.squareDistance(other)>=SEMANTIC_RADIUS*SEMANTIC_RADIUS)
                throw new IllegalArgumentException("merge semantic radius");
            vectors.add(vector);
            double base=(influence==null?h.baseImportance():influence.base(h.baseImportance()))*LandmarkActivityState.decay(now-(influence==null?h.activity().evaluatedTick():influence.tick));
            double level=influence==null?0:influence.level(now);
            weights.add(Math.max(0.01,base+level));bases+=base;levels+=level;
            if(bases+levels>LandmarkActivityState.MAX_IMPORTANCE_MASS)throw new IllegalArgumentException("merge mass budget");
            if(influence!=null){
                if(now<influence.tick)throw new IllegalArgumentException("merge time reversal");
                combined.owners.addAll(influence.owners);
                if(influence.claims!=null){
                    var leaves=influence.claims.cells(LandmarkActivityState.CELL_LIMIT);
                    cells+=leaves.size();if(cells>LandmarkActivityState.CELL_LIMIT)throw new IllegalArgumentException("combined claim budget");
                    for(var cell:leaves){
                        if(combined.claims==null)combined.claims=SparseOctree.empty(influence.claims.rootBounds(),1,1L<<32);
                        // Full spatial union; sorted source IDs pick the deterministic overlapping
                        // cell winner, while owners/core Ownership retain ALL player histories.
                        combined.claims=combined.claims.with(cell.bounds(),cell.value(),256);
                    }
                }
            }
            h.ownership().claims().forEach(c->combined.owners.add(c.player().toString()));
        }
        if(combined.owners.size()>16)throw new IllegalArgumentException("combined owner budget");
        double max=weights.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        for(int i=0;i<weights.size();i++)weights.set(i,weights.get(i)/max);
        combined.vector=ActivityMath.weighted(vectors,weights);
        if(combined.vector.length()==0)combined.vector=seed.baseEmbedding().vector();
        combined.mergedBase=bases;combined.level=levels;
        if(combined.claims!=null)combined.claims.cells(LandmarkActivityState.CELL_LIMIT);
        return combined;
    }
}
