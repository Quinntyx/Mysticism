package io.github.mysticism.landmark;

import java.util.*;

/** Server-thread-owned pure catalog. Alias resolution never silently redirects a split to an
 * arbitrary child. Physical merges require revision-guarded extractor verification.
 */
public final class LandmarkRepository {
    public record RevisionRef(String id, long revision) {
        public RevisionRef { Objects.requireNonNull(id); if(revision<0) throw new IllegalArgumentException("revision"); }
    }
    /** Explicit extractor evidence; similarity grouping is NOT a connectivity proof. */
    public record VerifiedConnectivity(List<RevisionRef> fragments, String evidence) {
        public VerifiedConnectivity {
            fragments=fragments.stream().sorted(Comparator.comparing(RevisionRef::id)).toList();
            if(fragments.size()<2 || fragments.stream().map(RevisionRef::id).distinct().count()!=fragments.size() || evidence==null || evidence.isBlank())
                throw new IllegalArgumentException("connectivity evidence");
        }
    }
    public record Snapshot(List<Landmark> landmarks, Map<String,String> aliases, Map<String,Long> tombstones,
                           Map<String,List<String>> lineage) {
        public Snapshot {
            landmarks=landmarks.stream().sorted(Comparator.comparing(Landmark::id)).toList();
            aliases=Map.copyOf(aliases); tombstones=Map.copyOf(tombstones);
            TreeMap<String,List<String>> copy=new TreeMap<>(); lineage.forEach((id,children)->copy.put(id,children.stream().sorted().distinct().toList()));
            lineage=Collections.unmodifiableMap(copy);
        }
    }
    private final NavigableMap<String,Landmark> records=new TreeMap<>();
    private final NavigableMap<String,String> aliases=new TreeMap<>();
    private final NavigableMap<String,Long> tombstones=new TreeMap<>();
    private final NavigableMap<String,List<String>> lineage=new TreeMap<>();
    private EmbeddingProfile profile;
    public LandmarkRepository() {}
    public static LandmarkRepository restore(Snapshot s) {
        LandmarkRepository r=new LandmarkRepository();
        for(Landmark l:s.landmarks) {
            r.requireProfile(l); if(r.records.putIfAbsent(l.id(),l)!=null) throw new IllegalArgumentException("duplicate record");
        }
        r.tombstones.putAll(s.tombstones); r.lineage.putAll(s.lineage); r.aliases.putAll(s.aliases);
        for(var e:r.tombstones.entrySet()) if(e.getValue()<0 || r.records.containsKey(e.getKey()) || r.aliases.containsKey(e.getKey())) throw new IllegalArgumentException("invalid tombstone");
        for(String id:r.aliases.keySet()) {
            if(r.records.containsKey(id)) throw new IllegalArgumentException("alias shadows live record");
            String target=r.resolve(id);
            if(!r.records.containsKey(target) && !r.tombstones.containsKey(target)) throw new IllegalArgumentException("dangling alias");
        }
        r.flattenAliases(); return r;
    }
    private void requireProfile(Landmark l) {
        if(profile!=null) profile.requireCompatible(l.baseEmbedding().profile());
        else profile=l.baseEmbedding().profile();
    }
    public String resolve(String id) {
        Set<String> visited=new HashSet<>(); String next=id;
        while(aliases.containsKey(next)) {
            if(!visited.add(next)) throw new IllegalArgumentException("alias cycle");
            next=aliases.get(next);
        }
        return next;
    }
    public Optional<Landmark> get(String id) { return Optional.ofNullable(records.get(resolve(id))); }
    public List<Landmark> landmarks() { return List.copyOf(records.values()); }
    public Snapshot snapshot() { return new Snapshot(landmarks(),aliases,tombstones,lineage); }
    /** expectedRevision=-1 creates a seed; otherwise compare-and-set the canonical live record. */
    public void put(Landmark value,long expectedRevision) {
        String id=value.id(); if(!resolve(id).equals(id) || tombstones.containsKey(id)) throw new IllegalArgumentException("retired seed");
        Landmark old=records.get(id);
        if(expectedRevision==-1 ? old!=null : old==null || old.revision()!=expectedRevision) throw new IllegalStateException("stale landmark revision");
        if(old!=null && value.revision()<=old.revision()) throw new IllegalArgumentException("non-increasing revision");
        requireProfile(value); records.put(id,value);
    }
    private Landmark require(RevisionRef ref) {
        Landmark l=get(ref.id).orElseThrow(()->new IllegalStateException("missing landmark"));
        if(l.revision()!=ref.revision) throw new IllegalStateException("stale landmark revision"); return l;
    }
    private static void sameSource(Landmark a,Landmark b) {
        a.baseEmbedding().profile().requireCompatible(b.baseEmbedding().profile());
        if(!a.dimension().equals(b.dimension()) || a.kind()!=b.kind() || !a.biome().equals(b.biome()) || !a.algorithmVersion().equals(b.algorithmVersion()))
            throw new IllegalArgumentException("different extraction domains");
    }
    /** Canonical representative is lexicographically smallest seed ID; base vector/anchor remain
     * that seed's. Geometry conflicts are rejected atomically, not resolved by arrival order.
     */
    public Landmark mergeVerified(VerifiedConnectivity proof,long tick,ImportancePolicy policy) {
        return mergeVerified(proof,tick,policy,null);
    }
    /** Optional extractor reconciliation resolves duplicate observation masks/frontier closure.
     * Every prior known material must survive; unknown cells may become known, never the reverse.
     */
    public Landmark mergeVerified(VerifiedConnectivity proof,long tick,ImportancePolicy policy,SourceGeometry reconciled) {
        List<Landmark> group=proof.fragments.stream().map(this::require).distinct().sorted(Comparator.comparing(Landmark::id)).toList();
        if(group.size()<2) throw new IllegalArgumentException("already merged fragments");
        Landmark seed=group.getFirst(); Bounds bounds=seed.bounds(); Ownership claims=new Ownership(List.of());
        TreeMap<String,GeometryPage> pages=new TreeMap<>(); List<FrontierFace> frontiers=new ArrayList<>();
        double importance=0,activity=0; long revision=0;
        for(Landmark l:group) {
            sameSource(seed,l); bounds=bounds.union(l.bounds()); claims=claims.merge(l.ownership());
            importance=Math.max(importance,l.baseImportance()); activity=Math.max(activity,l.activity().decayed(tick,policy)); revision=Math.max(revision,l.revision());
            frontiers.addAll(l.geometry().frontiers());
            if(reconciled==null) for(GeometryPage p:l.geometry().pages()) {
                GeometryPage old=pages.putIfAbsent(p.id(),p);
                if(old!=null && (old.revision()!=p.revision() || !old.bounds().equals(p.bounds()) || !old.palette().equals(p.palette()) || !old.knownCells().equals(p.knownCells())))
                    throw new IllegalArgumentException("conflicting observation page; reconcile before merge");
            }
        }
        SourceGeometry geometry=reconciled==null?new SourceGeometry(List.copyOf(pages.values()),frontiers):reconciled;
        if(reconciled!=null) validatePreservedMaterials(group,reconciled);
        Landmark merged=new Landmark(seed.id(),seed.dimension(),seed.algorithmVersion(),seed.kind(),seed.biome(),seed.anchor(),bounds,seed.baseEmbedding(),
                Math.min(policy.cap(),importance),new ActivityMetadata(activity,tick),claims,geometry,
                Math.addExact(revision,1),proof.evidence());
        for(Landmark l:group) if(!l.id().equals(seed.id())) { records.remove(l.id()); aliases.put(l.id(),seed.id()); }
        records.put(seed.id(),merged); flattenAliases(); return merged;
    }
    private static void validatePreservedMaterials(List<Landmark> group,SourceGeometry reconciled) {
        for(Landmark l:group) for(GeometryPage page:l.geometry().pages()) for(var old:page.knownCells()) {
            Bounds b=old.bounds(); long covered=0;
            for(var material:reconciled.queryMaterials(b,GeometryPage.MAX_SIDE*GeometryPage.MAX_SIDE*GeometryPage.MAX_SIDE)) {
                if(!material.material().equals(page.palette().state(old.value().paletteIndex())) || material.sample().occupancy()!=old.value().occupancy())
                    throw new IllegalArgumentException("reconciliation changed known material; revise observations first");
                Bounds c=material.bounds();
                covered+=(Math.min(b.maxX(),c.maxX())-Math.max(b.minX(),c.minX()))
                        *(Math.min(b.maxY(),c.maxY())-Math.max(b.minY(),c.minY()))*(Math.min(b.maxZ(),c.maxZ())-Math.max(b.minZ(),c.minZ()));
            }
            long volume=(b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ());
            if(covered!=volume) throw new IllegalArgumentException("reconciliation discarded known geometry");
        }
    }
    /** Split results must be recomputed by the extractor. A child containing the original seed
     * can retain its ID; otherwise parent and its aliases tombstone, with explicit one-to-many lineage.
     */
    public void split(RevisionRef parentRef,List<Landmark> children) {
        Landmark parent=require(parentRef);
        if(children.size()<2 || children.stream().map(Landmark::id).distinct().count()!=children.size()) throw new IllegalArgumentException("split children");
        for(Landmark child:children) {
            sameSource(parent,child);
            if(!parent.bounds().contains(child.bounds()) || child.revision()<=parent.revision() || tombstones.containsKey(child.id()) || aliases.containsKey(child.id())
                    || (records.containsKey(child.id()) && !child.id().equals(parent.id()))) throw new IllegalArgumentException("invalid split child");
        }
        records.remove(parent.id());
        if(children.stream().noneMatch(c->c.id().equals(parent.id()))) tombstones.put(parent.id(),Math.addExact(parent.revision(),1));
        children.stream().sorted(Comparator.comparing(Landmark::id)).forEach(c->records.put(c.id(),c));
        lineage.put(parent.id(),children.stream().map(Landmark::id).sorted().toList());
    }
    public void delete(RevisionRef ref) {
        Landmark old=require(ref); records.remove(old.id()); tombstones.put(old.id(),Math.addExact(old.revision(),1));
    }
    private void flattenAliases() { for(String id:List.copyOf(aliases.keySet())) aliases.put(id,resolve(id)); }
    /** Spatial source-space query: never compares embeddings. Over-budget results fail explicitly. */
    public List<Landmark> sourceRange(String dimension,Bounds range,int maxResults) {
        if(maxResults<0) throw new IllegalArgumentException("result budget");
        List<Landmark> out=new ArrayList<>();
        for(Landmark l:records.values()) if(l.dimension().equals(dimension) && l.bounds().intersects(range)) {
            if(out.size()>=maxResults) throw new IllegalArgumentException("source query budget exceeded"); out.add(l);
        }
        return List.copyOf(out);
    }
    /** Semantic query uses a strict Euclidean radius and exact profile; importance is irrelevant. */
    public List<Landmark> semanticRange(LandmarkEmbedding current,double radius,int maxResults) {
        if(!Double.isFinite(radius) || radius<=0 || !Double.isFinite(radius*radius) || radius*radius==0 || maxResults<0) throw new IllegalArgumentException("semantic query");
        if(profile!=null) profile.requireCompatible(current.profile());
        List<Landmark> out=new ArrayList<>();
        for(Landmark l:records.values()) if(l.baseEmbedding().distanceSquared(current)<radius*radius) {
            if(out.size()>=maxResults) throw new IllegalArgumentException("semantic query budget exceeded"); out.add(l);
        }
        return List.copyOf(out);
    }
}
