package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import java.io.*;
import java.security.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/** Overworld-owned paged PersistentState store. Access/mutations are server-thread confined.
 * Geometry and metadata versions occupy independent immutable NBT states; only changed pages
 * become dirty. A pending mutation publishes its catalog/aliases only after all pages are staged.
 * Vanilla saves are not cross-file crash transactions: missing/corrupt pages fail loudly.
 * Unreferenced old/staged versions are deliberately not deleted (future explicit compaction).
 */
public final class LandmarkStore extends PersistentState {
    public static final String SAVE_KEY="mysticism.landmarks.source-v2";
    public static final Type<LandmarkStore> TYPE=new Type<>(LandmarkStore::new,LandmarkStore::fromNbt,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private record Ref(String key,long revision) {}
    private record PageWrite(String key,Supplier<NbtCompound> encode) {}
    private record CachedPage(GeometryPage page,int leaves) {}
    private final NavigableMap<String,Ref> records=new TreeMap<>();
    private final NavigableMap<String,String> aliases=new TreeMap<>();
    private final NavigableMap<String,Long> tombstones=new TreeMap<>();
    private final NavigableMap<String,List<String>> lineage=new TreeMap<>();
    private EmbeddingProfile profile;
    private transient PersistentStateManager manager;
    private transient Thread owner;
    private transient PendingMutation pending;
    private transient Path dataDirectory;
    private transient GeometryRead activeRead;
    private transient long ownershipRevision;
    /** Runtime snapshot stamp for source masks/aliases/catalogue; activity-only CAS retains it.
     * Not a save-schema version. Compare only against this same store instance. */
    public long ownershipRevision(){checkThread();return ownershipRevision;}
    public static final int DECODED_PAGE_LIMIT=8, DECODED_LEAF_LIMIT=65536;
    private final LinkedHashMap<String,CachedPage> decoded=new LinkedHashMap<>(16,0.75f,true);
    private final LinkedHashMap<String,LandmarkMetadata> metadataCache=new LinkedHashMap<>(16,0.75f,true);
    private int residentLeaves;
    private long decodedLeavesTotal;
    public record CacheStats(int pages,int leaves,long reconstructedLeaves) {}
    public CacheStats cacheStats() { checkThread(); return new CacheStats(decoded.size(),residentLeaves,decodedLeavesTotal); }
    private LandmarkStore() {}
    public static LandmarkStore get(MinecraftServer server) {
        if(!server.isOnThread()) throw new IllegalStateException("landmark store requires server thread");
        var world=server.getOverworld(); if(world==null) throw new IllegalStateException("overworld unavailable");
        var manager=world.getPersistentStateManager();
        LandmarkStore state=manager.get(TYPE,SAVE_KEY);
        if(state==null && !Files.notExists(server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SAVE_KEY+".dat")))
            throw new IllegalStateException("landmark manifest exists but could not be decoded");
        if(state==null) { state=new LandmarkStore(); manager.set(SAVE_KEY,state); }
        state.attach(manager,server.getSavePath(WorldSavePath.ROOT).resolve("data")); return state;
    }
    // Package-private independent-manager entrypoint for deterministic persistence checks.
    static LandmarkStore open(PersistentStateManager manager,Path directory) {
        LandmarkStore state=manager.get(TYPE,SAVE_KEY);
        if(state==null && !Files.notExists(directory.resolve(SAVE_KEY+".dat"))) throw new IllegalStateException("unreadable manifest");
        if(state==null) { state=new LandmarkStore(); manager.set(SAVE_KEY,state); }
        state.attach(manager,directory); return state;
    }
    private void attach(PersistentStateManager manager,Path directory) {
        if(owner!=null && owner!=Thread.currentThread()) throw new IllegalStateException("different store thread");
        this.manager=manager; dataDirectory=directory; owner=Thread.currentThread();
    }
    private void checkThread() {
        if(manager==null || owner!=Thread.currentThread()) throw new IllegalStateException("unattached/off-thread landmark store");
    }
    public List<String> ids() { checkThread(); return List.copyOf(records.keySet()); }
    public String resolve(String id) {
        checkThread(); Set<String> seen=new HashSet<>();
        while(aliases.containsKey(id)) { if(!seen.add(id)) throw new IllegalArgumentException("alias cycle"); id=aliases.get(id); }
        return id;
    }
    public Map<String,String> aliases() { checkThread(); return Collections.unmodifiableMap(new TreeMap<>(aliases)); }
    public Map<String,Long> tombstones() { checkThread(); return Collections.unmodifiableMap(new TreeMap<>(tombstones)); }
    public Map<String,List<String>> lineage() { checkThread(); return Collections.unmodifiableMap(new TreeMap<>(lineage)); }
    /** Single-seed tombstone lookup; no catalog copy or geometry hydration. */
    public OptionalLong tombstoneRevision(String id) {
        checkThread(); Long revision = tombstones.get(Objects.requireNonNull(id));
        return revision == null ? OptionalLong.empty() : OptionalLong.of(revision);
    }
    /** Bounded single-seed lineage read for prepared split verification. */
    public List<String> lineageChildren(String id, int maxChildren) {
        checkThread();
        if (maxChildren < 0 || maxChildren > 512) throw new IllegalArgumentException("lineage read budget");
        var children = lineage.get(Objects.requireNonNull(id));
        if (children == null) return List.of();
        if (children.size() > maxChildren) throw new IllegalArgumentException("lineage read budget");
        return List.copyOf(children);
    }

    /** Metadata-only: no geometry NBT copies or reconstruction. */
    public Optional<LandmarkMetadata> metadata(String id) {
        checkThread(); id=resolve(id); Ref ref=records.get(id); if(ref==null) return Optional.empty();
        LandmarkMetadata value=metadataCache.get(ref.key);
        if(value==null) {
            value=LandmarkNbt.decodeMetadata(load(ref.key));
            String legacy="mysticism.landmark.record."+value.id()+"."+value.revision();
            if(!ref.key.equals(legacy) && !ref.key.equals(recordKey(value))) throw new IllegalStateException("metadata content/reference mismatch");
            metadataCache.put(ref.key,value);
            while(metadataCache.size()>128) metadataCache.remove(metadataCache.keySet().iterator().next());
        }
        if(!value.id().equals(id) || value.revision()!=ref.revision) throw new IllegalStateException("record/reference mismatch");
        profile.requireCompatible(value.header().baseEmbedding().profile());
        value.geometryKeys().forEach(LandmarkStore::checkKey); return Optional.of(value);
    }
    /** Compatibility lookup is cache-only. Explicit geometry reads are required on cache miss. */
    public Optional<Landmark> find(String id) {
        var metadata=metadata(id); if(metadata.isEmpty()) return Optional.empty();
        if(metadata.get().geometryKeys().size()>DECODED_PAGE_LIMIT) throw new IllegalStateException("large landmark: stream beginGeometryRead batches instead of find");
        List<GeometryPage> pages=new ArrayList<>();
        for(String key:metadata.get().geometryKeys()) {
            CachedPage cached=decoded.get(key);
            if(cached==null) throw new IllegalStateException("geometry not hydrated; use beginGeometryRead: "+key);
            pages.add(cached.page);
        }
        return Optional.of(LandmarkNbt.hydrate(metadata.get(),pages));
    }
    public List<LandmarkMetadata> sourceRange(String dimension,Bounds range,int maxResults,int maxScanned) {
        return metadataRange(maxResults,maxScanned,m->m.header().dimension().equals(dimension) && m.header().bounds().intersects(range));
    }
    /** One resumable ID-ordered source page, not a catalogue-size/result overflow gate.
     * Resume exclusively after nextId; end means the current suffix is exhausted. This is
     * a live view (not a topology snapshot); deleted cursor IDs remain valid seek positions.
     * At most 128 metadata reads, including cache hits; no geometry or ID-list materialization.
     * Cold metadata reads still use vanilla synchronous bounded-record IO.
     */
    public record SourceRangePage(List<LandmarkMetadata> landmarks,String nextId,int scanned,boolean end) {
        public SourceRangePage { landmarks=List.copyOf(landmarks); }
    }
    public SourceRangePage sourceRangePage(String dimension,Bounds range,String afterId,int maxResults,int maxScanned) {
        checkThread(); Objects.requireNonNull(dimension); Objects.requireNonNull(range);
        if(maxResults<1 || maxResults>128 || maxScanned<1 || maxScanned>128) throw new IllegalArgumentException("source page budget");
        String id=afterId==null?(records.isEmpty()?null:records.firstKey()):records.higherKey(afterId);
        String last=afterId; int scanned=0; List<LandmarkMetadata> result=new ArrayList<>();
        while(id!=null && scanned<maxScanned && result.size()<maxResults) {
            LandmarkMetadata m=metadata(id).orElseThrow(); last=id; scanned++;
            if(m.header().dimension().equals(dimension) && m.header().bounds().intersects(range)) result.add(m);
            id=records.higherKey(id);
        }
        return new SourceRangePage(result,last,scanned,id==null);
    }
    /** Discard disposable generated catalogue/profile, never source chunks or player data. */
    private int sourceGeneration;
    public boolean usesNativeSourceProfile(EmbeddingProfile current){checkThread();return sourceGeneration==2&&(profile==null||profile.equals(current));}
    public void clearGeneratedIfIncompatible(EmbeddingProfile current) {
        checkThread();if(sourceGeneration!=2 || profile!=null && !profile.equals(current)){unlocked();records.clear();aliases.clear();tombstones.clear();lineage.clear();decoded.clear();metadataCache.clear();residentLeaves=0;profile=current;sourceGeneration=2;ownershipRevision++;markDirty();}
    }
    /** Resumable semantic admission; no total-catalogue cap or hydration. */
    public SourceRangePage semanticRangePage(LandmarkEmbedding current,double radius,String afterId,int maxResults,int maxScanned) {
        return semanticRangePage(current,radius,afterId,maxResults,maxScanned,m->m.header().baseEmbedding());
    }
    public SourceRangePage semanticRangePage(LandmarkEmbedding current,double radius,String afterId,int maxResults,int maxScanned,java.util.function.Function<LandmarkMetadata,LandmarkEmbedding> effective) {
        checkThread();if(!Double.isFinite(radius)||radius<=0||!Double.isFinite(radius*radius)||radius*radius==0||maxResults<1||maxResults>128||maxScanned<1||maxScanned>128)throw new IllegalArgumentException("semantic page budget");
        if(profile!=null)profile.requireCompatible(current.profile());
        String id=afterId==null?(records.isEmpty()?null:records.firstKey()):records.higherKey(afterId),last=afterId;int scanned=0;List<LandmarkMetadata> found=new ArrayList<>();
        while(id!=null && scanned<maxScanned && found.size()<maxResults){var m=metadata(id).orElseThrow();last=id;scanned++;if(effective.apply(m).distanceSquared(current)<radius*radius)found.add(m);id=records.higherKey(id);}
        return new SourceRangePage(found,last,scanned,id==null);
    }
    public List<LandmarkMetadata> semanticRange(LandmarkEmbedding current,double radius,int maxResults,int maxScanned) {
        checkThread();
        if(!Double.isFinite(radius) || radius<=0 || !Double.isFinite(radius*radius) || radius*radius==0) throw new IllegalArgumentException("radius");
        if(profile!=null) profile.requireCompatible(current.profile());
        return metadataRange(maxResults,maxScanned,m->m.header().baseEmbedding().distanceSquared(current)<radius*radius);
    }
    private List<LandmarkMetadata> metadataRange(int maxResults,int maxScanned,java.util.function.Predicate<LandmarkMetadata> filter) {
        checkThread(); if(maxResults<0 || maxScanned<0 || records.size()>maxScanned) throw new IllegalArgumentException("metadata scan budget");
        List<LandmarkMetadata> result=new ArrayList<>();
        for(String id:records.keySet()) { LandmarkMetadata m=metadata(id).orElseThrow(); if(filter.test(m)) { if(result.size()==maxResults) throw new IllegalArgumentException("result budget"); result.add(m); } }
        return List.copyOf(result);
    }
    private BlobState blob(String key) {
        checkKey(key); BlobState state=manager.get(BlobState.TYPE,key);
        // Vanilla get() conflates absent files with failed decoding (and caches null).
        // Only a positively absent file permits creation; unreadability is NEVER replacement.
        if(state==null && !Files.notExists(dataDirectory.resolve(key+".dat"))) throw new IllegalStateException("unreadable landmark page: "+key);
        return state;
    }
    private NbtCompound load(String key) {
        BlobState state=blob(key);
        if(state==null || state.payload==null) throw new IllegalStateException("missing/corrupt landmark page: "+key);
        return state.payload; // private read-only codecs; never exposed to callers
    }
    public boolean geometryReadAvailable(){checkThread();return activeRead==null;}
    public GeometryRead beginGeometryRead(String id) {return beginGeometryRead(id,null);}
    /** Bounds-gated cold geometry traversal: skip unrelated page leaves, one header per operation. */
    public GeometryRead beginGeometryRead(String id,Bounds range) {return beginGeometryRead(id,range,0);}
    /** Resume only at a completed/skipped page boundary against the caller's immutable key list. */
    public GeometryRead beginGeometryRead(String id,Bounds range,int nextPageIndex) {
        checkThread(); if(activeRead!=null) throw new IllegalStateException("another geometry read is active");
        LandmarkMetadata value=metadata(id).orElseThrow(()->new IllegalStateException("missing landmark"));
        if(nextPageIndex<0 || nextPageIndex>value.geometryKeys().size()) throw new IllegalArgumentException("geometry page cursor");
        activeRead=new GeometryRead(value,range,nextPageIndex); return activeRead;
    }
    /** One active read; batches hold <=8 pages. Drain before advancing. Budgets count cache hits too. */
    public final class GeometryRead {
        private final LandmarkMetadata metadata;
        private final Bounds range;
        private final List<GeometryPage> ready=new ArrayList<>();
        private int cursor;
        private LandmarkNbt.GeometryDecoder decoder;
        private boolean cancelled;
        public boolean isCurrent() { checkThread(); Ref ref=records.get(metadata.id()); return ref!=null && ref.revision==metadata.revision(); }
        private GeometryRead(LandmarkMetadata metadata,Bounds range,int nextPageIndex) { this.metadata=metadata;this.range=range;this.cursor=nextPageIndex; }
        /** Does not advance while the current page is partially decoded. */
        public int nextPageIndex() { checkThread(); return cursor; }
        public LandmarkMetadata metadata() { checkThread(); return metadata; }
        public boolean complete() { checkThread(); return cursor==metadata.geometryKeys().size(); }
        public int advance(int maxPages,int maxLeaves) {
            checkThread(); if(cancelled) throw new IllegalStateException("cancelled read");
            if(maxPages<1 || maxPages>DECODED_PAGE_LIMIT || maxLeaves<1 || maxLeaves>GeometryPage.MAX_LEAVES || !ready.isEmpty()) throw new IllegalArgumentException("read budget/drain required");
            int pages=0,leaves=0;
            while(!complete() && pages<maxPages && leaves<maxLeaves) {
                String key=metadata.geometryKeys().get(cursor); CachedPage cached=decoded.get(key); GeometryPage page=cached==null?null:cached.page;
                if(page==null) {
                    if(decoder==null) decoder=new LandmarkNbt.GeometryDecoder(load(key));
                    if(range!=null && !range.intersects(decoder.bounds())){decoder=null;cursor++;pages++;continue;}
                    int worked=decoder.advance(maxLeaves-leaves); leaves+=worked; decodedLeavesTotal+=worked;
                    if(!decoder.complete()) break;
                    page=decoder.result(); int leafCount=decoder.leafCount(); decoder=null;
                    if(!geometryKey(page).equals(key)) throw new IllegalStateException("geometry/reference mismatch");
                    cache(key,page,leafCount);
                }
                if(range!=null && !range.intersects(page.bounds())){cursor++;pages++;continue;}
                if(!metadata.header().bounds().contains(page.bounds())) throw new IllegalStateException("page outside landmark");
                ready.add(page); cursor++; pages++;
            }
            if(complete()) activeRead=null;
            return leaves;
        }
        public List<GeometryPage> drain() { checkThread(); List<GeometryPage> out=List.copyOf(ready); ready.clear(); return out; }
        public void cancel() { checkThread(); cancelled=true; decoder=null; ready.clear(); if(activeRead==this) activeRead=null; }
    }
    private void cache(String key,GeometryPage page,int leaves) {
        CachedPage old=decoded.put(key,new CachedPage(page,leaves)); residentLeaves+=leaves-(old==null?0:old.leaves);
        while(decoded.size()>DECODED_PAGE_LIMIT || residentLeaves>DECODED_LEAF_LIMIT) {
            String first=decoded.keySet().iterator().next(); residentLeaves-=decoded.remove(first).leaves;
        }
    }
    private static void checkKey(String key) {
        if(!key.matches("mysticism[.]landmark[.](geometry[.]lm-[0-9a-f]{64}[.][0-9]+|record[.]lm-[0-9a-f]{64}[.][0-9]+([.][0-9a-f]{64})?)")) throw new IllegalArgumentException("invalid landmark page key");
    }
    private static String geometryKey(GeometryPage p) { return "mysticism.landmark.geometry."+p.id()+"."+p.revision(); }
    // CAS revisions name committed identities, not staging attempts. Content addressing makes
    // cancelled/restarted attempts independent without overwriting any immutable metadata.
    private static String recordKey(LandmarkMetadata value) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            var output=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash));
            hashNbt(output,LandmarkNbt.encodeMetadata(value));
            return "mysticism.landmark.record."+value.id()+"."+value.revision()+"."+HexFormat.of().formatHex(hash.digest());
        } catch(IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("metadata digest",e); }
    }
    private static void hashNbt(DataOutput out,NbtElement value) throws IOException {
        out.writeByte(value.getType());
        if(value instanceof NbtCompound compound) {
            var keys=new TreeSet<>(compound.getKeys()); out.writeInt(keys.size());
            for(String key:keys) { out.writeUTF(key); hashNbt(out,compound.get(key)); }
        } else if(value instanceof NbtList list) {
            out.writeInt(list.size()); for(NbtElement element:list) hashNbt(out,element);
        } else value.write(out);
    }
    private void unlocked() { checkThread(); if(pending!=null) throw new IllegalStateException("another landmark mutation is pending"); }
    /** expectedRevision=-1 creates a seed; otherwise compare-and-set a canonical live ID. */
    public PendingMutation stagePut(Landmark value,long expectedRevision) {
        unlocked(); String canonical=resolve(value.id());
        if(!canonical.equals(value.id()) || tombstones.containsKey(value.id())) throw new IllegalArgumentException("retired seed");
        Ref old=records.get(value.id());
        if(expectedRevision==-1?old!=null:old==null || old.revision!=expectedRevision) throw new IllegalStateException("stale landmark revision");
        if(old!=null && value.revision()<=old.revision) throw new IllegalArgumentException("non-increasing revision");
        if(old!=null) metadata(value.id()).orElseThrow(()->new IllegalStateException("missing record")); // never bypass corrupt existing versions
        return stage(List.of(value),Set.of(),Map.of(),Map.of(),Map.of());
    }
    /** Activity/claims CAS retaining geometry references, without decoding any geometry leaves. */
    public PendingMutation stageActivity(LandmarkRepository.RevisionRef ref,ActivityMetadata activity,Ownership ownership) {
        unlocked(); LandmarkMetadata old=metadata(ref.id()).orElseThrow(()->new IllegalStateException("missing record"));
        if(old.revision()!=ref.revision()) throw new IllegalStateException("stale landmark revision");
        LandmarkMetadata next=new LandmarkMetadata(old.header().withActivity(activity,ownership),old.geometryKeys());
        List<PageWrite> writes=new ArrayList<>();
        for(String key:old.geometryKeys()) writes.add(new PageWrite(key,null)); // required immutable references; never recreated
        writes.add(new PageWrite(recordKey(next),()->LandmarkNbt.encodeMetadata(next)));
        pending=new PendingMutation(List.copyOf(writes),List.of(next),Set.of(),Map.of(),Map.of(),Map.of(),profile);pending.changesSource=false; return pending;
    }
    /** Metadata/CAS merge with opaque references. Distinct page AABBs must be disjoint;
     * overlapping masks require the reconciled overload. Validation is budgeted by advance. */
    public PendingMutation stageMerge(LandmarkRepository.VerifiedConnectivity proof,long tick,ImportancePolicy policy) {
        return stageMerge(proof,tick,policy,null);
    }
    public PendingMutation stageMerge(LandmarkRepository.VerifiedConnectivity proof,long tick,ImportancePolicy policy,SourceGeometry reconciled) {return stageMerge(proof,tick,policy,reconciled,false);}
    /** Activity convergence may override initial cave biome/kind classification, never physical proof. */
    public PendingMutation stageSemanticMerge(LandmarkRepository.VerifiedConnectivity proof,long tick,ImportancePolicy policy,SourceGeometry reconciled) {return stageMerge(proof,tick,policy,reconciled,true);}
    private PendingMutation stageMerge(LandmarkRepository.VerifiedConnectivity proof,long tick,ImportancePolicy policy,SourceGeometry reconciled,boolean converged) {
        unlocked(); Set<String> touched=new TreeSet<>(); List<LandmarkMetadata> fragments=new ArrayList<>();
        for(var ref:proof.fragments()) {
            LandmarkMetadata m=metadata(ref.id()).orElseThrow(()->new IllegalStateException("missing fragment"));
            if(m.revision()!=ref.revision()) throw new IllegalStateException("stale landmark revision");
            if(touched.add(m.id())) fragments.add(m);
        }
        Map<String,String> localAliases=new TreeMap<>(); aliases.forEach((id,target)->{ if(touched.contains(target)) localAliases.put(id,target); });
        LandmarkRepository repo=LandmarkRepository.restore(new LandmarkRepository.Snapshot(fragments.stream().map(LandmarkMetadata::header).toList(),localAliases,Map.of(),Map.of()));
        Landmark merged=converged?repo.mergeConverged(proof,tick,policy):repo.mergeVerified(proof,tick,policy); var snapshot=repo.snapshot();
        touched.remove(merged.id());
        List<String> keys=fragments.stream().flatMap(m->m.geometryKeys().stream()).distinct().sorted().toList();
        if(reconciled==null) {
            // Same observation ID at different versions requires explicit extractor reconciliation.
            LandmarkMetadata next=new LandmarkMetadata(merged,keys);
            PendingMutation mutation=stageMetadata(List.of(next),touched,snapshot.aliases(),Map.of(),Map.of());
            mutation.validations=keys.stream().map(k->new Validation(k,merged.bounds(),null,true)).toList();
            return mutation;
        }
        Landmark value=new Landmark(merged.id(),merged.dimension(),merged.algorithmVersion(),merged.kind(),merged.biome(),merged.anchor(),merged.bounds(),merged.baseEmbedding(),merged.baseImportance(),merged.activity(),merged.ownership(),reconciled,merged.revision(),merged.provenance());
        PendingMutation mutation=stage(List.of(value),touched,snapshot.aliases(),Map.of(),Map.of());
        mutation.validations=keys.stream().map(k->new Validation(k,merged.bounds(),reconciled,false)).toList();
        return mutation;
    }
    /** Atomic exclusive cell transfer. Caller certifies source contiguity; prior materials are
     * incrementally conserved against the disjoint replacement union before publication. */
    public PendingMutation stageTransfer(List<LandmarkRepository.RevisionRef> parents,List<Landmark> replacements,SourceGeometry conserved) {
        unlocked();if(parents.size()!=2 || replacements.size()!=2)throw new IllegalArgumentException("transfer participants");
        Set<String> ids=new HashSet<>();List<LandmarkMetadata> previous=new ArrayList<>();
        for(var ref:parents){var old=metadata(ref.id()).orElseThrow();if(!old.id().equals(ref.id())||old.revision()!=ref.revision()||!ids.add(ref.id()))throw new IllegalStateException("stale transfer");previous.add(old);}
        if(!previous.getFirst().header().dimension().equals(previous.getLast().header().dimension()))throw new IllegalArgumentException("transfer dimension");
        for(var value:replacements){var old=previous.stream().filter(m->m.id().equals(value.id())).findFirst().orElseThrow();if(value.revision()!=old.revision()+1 || !value.dimension().equals(old.header().dimension()))throw new IllegalArgumentException("transfer identity");}
        if(replacements.stream().map(Landmark::id).distinct().count()!=2)throw new IllegalArgumentException("transfer replacements");
        var mutation=stage(replacements,Set.of(),Map.of(),Map.of(),Map.of());Bounds union=replacements.getFirst().bounds().union(replacements.getLast().bounds());
        mutation.validations=previous.stream().flatMap(m->m.geometryKeys().stream()).distinct().map(k->new Validation(k,union,conserved,false)).toList();return mutation;
    }
    /** Extractor-recomputed children; parent validation is metadata-only, never find/hydration. */
    public PendingMutation stageSplit(LandmarkRepository.RevisionRef parent,List<Landmark> children) {
        unlocked(); LandmarkMetadata old=metadata(parent.id()).orElseThrow(()->new IllegalStateException("missing parent"));
        var snapshot=splitSnapshot(parent,old,children);
        return stage(snapshot.landmarks(),Set.of(old.id()),Map.of(),snapshot.tombstones(),snapshot.lineage());
    }
    /** Split reusing immutable parent page references; no geometry hydration. New masks use stageSplit. */
    public PendingMutation stageSplitMetadata(LandmarkRepository.RevisionRef parent,List<LandmarkMetadata> children) {
        unlocked(); LandmarkMetadata old=metadata(parent.id()).orElseThrow(()->new IllegalStateException("missing parent"));
        var snapshot=splitSnapshot(parent,old,children.stream().map(LandmarkMetadata::header).toList());
        Set<String> allowed=new HashSet<>(old.geometryKeys()),used=new HashSet<>(); List<Validation> validations=new ArrayList<>();
        for(LandmarkMetadata child:children) for(String key:child.geometryKeys()) {
            if(!allowed.contains(key) || !used.add(key)) throw new IllegalArgumentException("split references must uniquely partition parent pages");
            validations.add(new Validation(key,child.header().bounds(),null,false));
        }
        if(!used.equals(allowed)) throw new IllegalArgumentException("split references must preserve every parent page; recomputed masks use stageSplit");
        PendingMutation mutation=stageMetadata(children,Set.of(old.id()),Map.of(),snapshot.tombstones(),snapshot.lineage());
        mutation.validations=List.copyOf(validations); return mutation;
    }
    private LandmarkRepository.Snapshot splitSnapshot(LandmarkRepository.RevisionRef parent,LandmarkMetadata old,List<Landmark> children) {
        LandmarkRepository repo=new LandmarkRepository(); repo.put(old.header(),-1);
        repo.split(new LandmarkRepository.RevisionRef(old.id(),parent.revision()),children);
        for(Landmark child:children) if((records.containsKey(child.id()) && !child.id().equals(old.id())) || aliases.containsKey(child.id()) || tombstones.containsKey(child.id()))
            throw new IllegalArgumentException("split seed conflicts with catalog");
        return repo.snapshot();
    }
    public PendingMutation stageDelete(LandmarkRepository.RevisionRef ref) {
        unlocked(); Landmark l=metadata(ref.id()).orElseThrow(()->new IllegalStateException("missing record")).header();
        if(l.revision()!=ref.revision()) throw new IllegalStateException("stale landmark revision");
        return stage(List.of(),Set.of(l.id()),Map.of(),Map.of(l.id(),Math.addExact(l.revision(),1)),Map.of());
    }
    private PendingMutation stage(List<Landmark> values,Set<String> removals,Map<String,String> newAliases,Map<String,Long> retired,Map<String,List<String>> history) {
        List<PageWrite> writes=new ArrayList<>(); Map<String,GeometryPage> unique=new TreeMap<>();
        EmbeddingProfile nextProfile=profile;
        for(Landmark l:values) {
            if(nextProfile==null) nextProfile=l.baseEmbedding().profile(); else nextProfile.requireCompatible(l.baseEmbedding().profile());
            if(l.geometry().pages().size()>LandmarkNbt.MAX_GEOMETRY_REFS || l.geometry().frontiers().size()>LandmarkNbt.MAX_FRONTIERS || l.ownership().claims().size()>LandmarkNbt.MAX_CLAIMS)
                throw new IllegalArgumentException("metadata page limits");
            for(GeometryPage p:l.geometry().pages()) {
                String key=geometryKey(p); checkKey(key); GeometryPage prior=unique.putIfAbsent(key,p);
                if(prior!=null && (!prior.palette().equals(p.palette()) || !prior.bounds().equals(p.bounds()) || !prior.knownCells().equals(p.knownCells()))) throw new IllegalArgumentException("conflicting page versions");
            }
        }
        unique.forEach((key,page)->writes.add(new PageWrite(key,()->LandmarkNbt.encodeGeometry(page))));
        List<LandmarkMetadata> metadata=values.stream().map(l->LandmarkNbt.decodeMetadata(LandmarkNbt.encodeLandmark(l,l.geometry().pages().stream().map(LandmarkStore::geometryKey).toList()))).toList();
        appendMetadataWrites(writes,metadata);
        pending=new PendingMutation(List.copyOf(writes),metadata,Set.copyOf(removals),Map.copyOf(newAliases),Map.copyOf(retired),Map.copyOf(history),nextProfile); return pending;
    }
    private static void appendMetadataWrites(List<PageWrite> writes,List<LandmarkMetadata> values) {
        for(LandmarkMetadata value:values) writes.add(new PageWrite(recordKey(value),()->LandmarkNbt.encodeMetadata(value)));
    }
    private PendingMutation stageMetadata(List<LandmarkMetadata> values,Set<String> removals,Map<String,String> newAliases,Map<String,Long> retired,Map<String,List<String>> history) {
        List<PageWrite> writes=new ArrayList<>(); Set<String> keys=new TreeSet<>();
        for(LandmarkMetadata value:values) { profile.requireCompatible(value.header().baseEmbedding().profile()); keys.addAll(value.geometryKeys()); }
        keys.forEach(key->writes.add(new PageWrite(key,null))); appendMetadataWrites(writes,values);
        pending=new PendingMutation(List.copyOf(writes),List.copyOf(values),Set.copyOf(removals),Map.copyOf(newAliases),Map.copyOf(retired),Map.copyOf(history),profile); return pending;
    }
    private record Validation(String key,Bounds bounds,SourceGeometry replacement,boolean disjoint) {}
    public final class PendingMutation {
        private final List<PageWrite> writes;
        private final List<LandmarkMetadata> values;
        private final Set<String> removals;
        private final Map<String,String> newAliases;
        private final Map<String,Long> retired;
        private final Map<String,List<String>> history;
        private final EmbeddingProfile nextProfile;
        private int cursor,validationCursor;
        private List<Validation> validations=List.of();
        private LandmarkNbt.GeometryDecoder validationDecoder;
        private final List<Bounds> validatedBounds=new ArrayList<>();
        private long validatedLeaves;
        public long validatedLeaves() { checkThread(); return validatedLeaves; }
        private boolean complete,cancelled,changesSource=true;
        private PendingMutation(List<PageWrite> writes,List<LandmarkMetadata> values,Set<String> removals,Map<String,String> newAliases,Map<String,Long> retired,Map<String,List<String>> history,EmbeddingProfile nextProfile) {
            this.writes=writes; this.values=values; this.removals=removals; this.newAliases=newAliases; this.retired=retired; this.history=history; this.nextProfile=nextProfile;
        }
        public boolean complete() { checkThread(); return complete; }
        public int remainingPages() { checkThread(); return complete?0:validations.size()-validationCursor+writes.size()-cursor+1; }
        /** At most maxPages newly dirtied states, including the final manifest publication.
         * Unchanged immutable versions consume work but are not marked dirty. No automatic disk IO.
         */
        public int advance(int maxPages) { return advance(maxPages,GeometryPage.MAX_LEAVES); }
        /** Validation visits, writes and manifest publication share maxPages; old-leaf
         * reconciliation uses at most maxLeaves. One partially reconstructed old page, never cached. */
        public int advance(int maxPages,int maxLeaves) {
            checkThread(); if(cancelled) throw new IllegalStateException("cancelled mutation");
            if(maxPages<=0 || maxLeaves<1 || maxLeaves>GeometryPage.MAX_LEAVES) throw new IllegalArgumentException("mutation budget"); if(complete) return 0;
            int worked=0,leaves=0;
            while(validationCursor<validations.size() && worked<maxPages && leaves<maxLeaves) {
                Validation v=validations.get(validationCursor);
                if(validationDecoder==null) {
                    validationDecoder=new LandmarkNbt.GeometryDecoder(load(v.key));
                    if(!validationDecoder.key().equals(v.key) || !v.bounds.contains(validationDecoder.bounds())) throw new IllegalArgumentException("topology page identity/bounds mismatch");
                }
                worked++;
                if(v.replacement!=null) {
                    var decoder=validationDecoder;
                    int count=decoder.advance(maxLeaves-leaves,cell->LandmarkRepository.validatePreservedCell(cell,decoder.palette(),v.replacement));
                    leaves+=count; validatedLeaves+=count;
                    if(!decoder.complete()) return worked;
                } else if(v.disjoint) {
                    for(Bounds prior:validatedBounds) if(prior.intersects(validationDecoder.bounds())) throw new IllegalArgumentException("overlapping observation bounds: supply reconciled geometry");
                    validatedBounds.add(validationDecoder.bounds());
                }
                validationDecoder=null; validationCursor++;
            }
            if(validationCursor<validations.size()) return worked;
            while(cursor<writes.size() && worked<maxPages) {
                PageWrite write=writes.get(cursor); BlobState old=blob(write.key);
                if(write.encode==null) {
                    if(old==null || old.payload==null) throw new IllegalStateException("missing geometry reference: "+write.key);
                } else {
                    NbtCompound encoded=write.encode.get();
                    if(old==null) { old=new BlobState(); old.payload=encoded; old.markDirty(); manager.set(write.key,old); }
                    else if(!encoded.equals(old.payload)) throw new IllegalStateException("immutable page version conflict: "+write.key);
                }
                cursor++; worked++;
            }
            if(cursor==writes.size() && worked<maxPages) {
                removals.forEach(records::remove); for(LandmarkMetadata l:values) records.put(l.id(),new Ref(recordKey(l),l.revision()));
                aliases.putAll(newAliases); tombstones.putAll(retired); lineage.putAll(history); profile=nextProfile;
                for(String id:List.copyOf(aliases.keySet())) aliases.put(id,resolve(id));
                if(changesSource)ownershipRevision++;
                markDirty(); complete=true; pending=null; worked++;
            }
            return worked;
        }
        /** Cancel leaves only unreferenced immutable pages, never partially published topology. */
        public void cancel() { checkThread(); if(complete) throw new IllegalStateException("already published"); cancelled=true; validationDecoder=null; if(pending==this) pending=null; }
    }
    private static final class BlobState extends PersistentState {
        private NbtCompound payload;
        private static final Type<BlobState> TYPE=new Type<>(BlobState::new,(n,r)->{
            if(!n.contains("payload",NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("invalid page payload");
            BlobState s=new BlobState(); s.payload=n.getCompound("payload"); return s;
        },DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
        @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup r) {
            if(payload==null) throw new IllegalStateException("uninitialized page"); n.put("payload",payload.copy()); return n;
        }
    }
    public static LandmarkStore fromNbt(NbtCompound n,RegistryWrapper.WrapperLookup r) {
        if(!n.contains("schema",NbtElement.INT_TYPE) || n.getInt("schema")!=LandmarkNbt.SCHEMA) throw new IllegalArgumentException("unsupported landmark manifest");
        LandmarkStore store=new LandmarkStore();store.sourceGeneration=n.getInt("sourceGeneration");
        if(n.contains("profile",NbtElement.COMPOUND_TYPE)) store.profile=LandmarkNbt.decodeProfile(n.getCompound("profile"));
        for(String field:List.of("records","aliases","tombstones","lineage")) if(!n.contains(field,NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("missing manifest field: "+field);
        NbtCompound records=n.getCompound("records");
        for(String id:records.getKeys()) {
            if(!records.contains(id,NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("invalid record reference");
            NbtCompound ref=records.getCompound(id);
            if(!ref.contains("key",NbtElement.STRING_TYPE) || !ref.contains("revision",NbtElement.LONG_TYPE) || ref.getLong("revision")<0) throw new IllegalArgumentException("invalid record revision");
            String key=ref.getString("key"); checkKey(key);
            if(!key.equals("mysticism.landmark.record."+id+"."+ref.getLong("revision")) && !key.matches("mysticism[.]landmark[.]record[.]"+id+"[.]"+ref.getLong("revision")+"[.][0-9a-f]{64}")) throw new IllegalArgumentException("record key identity mismatch");
            store.records.put(id,new Ref(key,ref.getLong("revision")));
        }
        NbtCompound aliases=n.getCompound("aliases"),tombstones=n.getCompound("tombstones"),lineage=n.getCompound("lineage");
        for(String id:aliases.getKeys()) { if(!aliases.contains(id,NbtElement.STRING_TYPE)) throw new IllegalArgumentException("invalid alias"); store.aliases.put(id,aliases.getString(id)); }
        for(String id:tombstones.getKeys()) { if(!tombstones.contains(id,NbtElement.LONG_TYPE) || tombstones.getLong(id)<0) throw new IllegalArgumentException("invalid tombstone"); store.tombstones.put(id,tombstones.getLong(id)); }
        for(String id:lineage.getKeys()) {
            if(!lineage.contains(id,NbtElement.LIST_TYPE)) throw new IllegalArgumentException("invalid lineage");
            NbtList children=(NbtList)lineage.get(id); if(!children.isEmpty() && children.getHeldType()!=NbtElement.STRING_TYPE) throw new IllegalArgumentException("invalid lineage child");
            List<String> ids=new ArrayList<>(); for(NbtElement e:children) ids.add(e.asString()); store.lineage.put(id,ids.stream().sorted().distinct().toList());
        }
        if(!store.records.isEmpty() && store.profile==null) throw new IllegalArgumentException("missing catalog profile");
        // Reuse pure alias validation without loading any geometry or metadata pages.
        for(String id:store.aliases.keySet()) {
            if(store.records.containsKey(id) || store.tombstones.containsKey(id)) throw new IllegalArgumentException("alias shadows canonical ID");
            Set<String> seen=new HashSet<>(); String target=id;
            while(store.aliases.containsKey(target)) { if(!seen.add(target)) throw new IllegalArgumentException("alias cycle"); target=store.aliases.get(target); }
            if(!store.records.containsKey(target) && !store.tombstones.containsKey(target)) throw new IllegalArgumentException("dangling alias");
            store.aliases.put(id,target);
        }
        for(String id:store.tombstones.keySet()) if(store.records.containsKey(id)) throw new IllegalArgumentException("live tombstone");
        return store;
    }
    @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup r) {
        n.putInt("schema",LandmarkNbt.SCHEMA);n.putInt("sourceGeneration",sourceGeneration); if(profile!=null) n.put("profile",LandmarkNbt.encodeProfile(profile));
        NbtCompound refs=new NbtCompound(); records.forEach((id,ref)->{ NbtCompound e=new NbtCompound(); e.putString("key",ref.key); e.putLong("revision",ref.revision); refs.put(id,e); }); n.put("records",refs);
        NbtCompound alias=new NbtCompound(); aliases.forEach(alias::putString); n.put("aliases",alias);
        NbtCompound retired=new NbtCompound(); tombstones.forEach(retired::putLong); n.put("tombstones",retired);
        NbtCompound history=new NbtCompound(); lineage.forEach((id,children)->{ NbtList list=new NbtList(); children.forEach(child->list.add(NbtString.of(child))); history.put(id,list); }); n.put("lineage",history); return n;
    }
}
