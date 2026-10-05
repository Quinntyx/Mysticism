package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

/** Overworld-owned paged PersistentState store. Access/mutations are server-thread confined.
 * Geometry and metadata versions occupy independent immutable NBT states; only changed pages
 * become dirty. A pending mutation publishes its catalog/aliases only after all pages are staged.
 * Vanilla saves are not cross-file crash transactions: missing/corrupt pages fail loudly.
 * Unreferenced old/staged versions are deliberately not deleted (future explicit compaction).
 */
public final class LandmarkStore extends PersistentState {
    public static final String SAVE_KEY="mysticism.landmarks.v1";
    public static final Type<LandmarkStore> TYPE=new Type<>(LandmarkStore::new,LandmarkStore::fromNbt,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private record Ref(String key,long revision) {}
    private record PageWrite(String key,Supplier<NbtCompound> encode) {}
    private final NavigableMap<String,Ref> records=new TreeMap<>();
    private final NavigableMap<String,String> aliases=new TreeMap<>();
    private final NavigableMap<String,Long> tombstones=new TreeMap<>();
    private final NavigableMap<String,List<String>> lineage=new TreeMap<>();
    private EmbeddingProfile profile;
    private transient PersistentStateManager manager;
    private transient Thread owner;
    private transient PendingMutation pending;
    private LandmarkStore() {}
    public static LandmarkStore get(MinecraftServer server) {
        if(!server.isOnThread()) throw new IllegalStateException("landmark store requires server thread");
        var world=server.getOverworld(); if(world==null) throw new IllegalStateException("overworld unavailable");
        var manager=world.getPersistentStateManager();
        LandmarkStore state=manager.get(TYPE,SAVE_KEY);
        if(state==null && Files.exists(server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SAVE_KEY+".dat")))
            throw new IllegalStateException("landmark manifest exists but could not be decoded");
        if(state==null) { state=new LandmarkStore(); manager.set(SAVE_KEY,state); }
        state.attach(manager); return state;
    }
    // Package-private independent-manager entrypoint for deterministic persistence checks.
    static LandmarkStore open(PersistentStateManager manager) {
        LandmarkStore state=manager.getOrCreate(TYPE,SAVE_KEY); state.attach(manager); return state;
    }
    private void attach(PersistentStateManager manager) {
        if(owner!=null && owner!=Thread.currentThread()) throw new IllegalStateException("different store thread");
        this.manager=manager; owner=Thread.currentThread();
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
    public Optional<Landmark> find(String id) {
        checkThread(); id=resolve(id); Ref ref=records.get(id); if(ref==null) return Optional.empty();
        Landmark value=LandmarkNbt.decodeLandmark(load(ref.key),this::loadGeometry);
        if(!value.id().equals(id) || value.revision()!=ref.revision) throw new IllegalStateException("record/reference mismatch");
        profile.requireCompatible(value.baseEmbedding().profile()); return Optional.of(value);
    }
    private NbtCompound load(String key) {
        checkKey(key); BlobState blob=manager.get(BlobState.TYPE,key);
        if(blob==null || blob.payload==null) throw new IllegalStateException("missing/corrupt landmark page: "+key);
        return blob.payload.copy();
    }
    private GeometryPage loadGeometry(String key) {
        GeometryPage page=LandmarkNbt.decodeGeometry(load(key));
        if(!geometryKey(page).equals(key)) throw new IllegalStateException("geometry/reference mismatch");
        return page;
    }
    private static void checkKey(String key) {
        if(!key.matches("mysticism[.]landmark[.](geometry|record)[.]lm-[0-9a-f]{64}[.][0-9]+")) throw new IllegalArgumentException("invalid landmark page key");
    }
    private static String geometryKey(GeometryPage p) { return "mysticism.landmark.geometry."+p.id()+"."+p.revision(); }
    private static String recordKey(Landmark l) { return "mysticism.landmark.record."+l.id()+"."+l.revision(); }
    private void unlocked() { checkThread(); if(pending!=null) throw new IllegalStateException("another landmark mutation is pending"); }
    /** expectedRevision=-1 creates a seed; otherwise compare-and-set a canonical live ID. */
    public PendingMutation stagePut(Landmark value,long expectedRevision) {
        unlocked(); String canonical=resolve(value.id());
        if(!canonical.equals(value.id()) || tombstones.containsKey(value.id())) throw new IllegalArgumentException("retired seed");
        Ref old=records.get(value.id());
        if(expectedRevision==-1?old!=null:old==null || old.revision!=expectedRevision) throw new IllegalStateException("stale landmark revision");
        if(old!=null && value.revision()<=old.revision) throw new IllegalArgumentException("non-increasing revision");
        return stage(List.of(value),Set.of(),Map.of(),Map.of(),Map.of());
    }
    public PendingMutation stageMerge(LandmarkRepository.VerifiedConnectivity proof,long tick,ImportancePolicy policy) {
        return stageMerge(proof,tick,policy,null);
    }
    public PendingMutation stageMerge(LandmarkRepository.VerifiedConnectivity proof,long tick,ImportancePolicy policy,SourceGeometry reconciled) {
        unlocked(); Set<String> touched=new TreeSet<>(); List<Landmark> fragments=new ArrayList<>();
        for(var ref:proof.fragments()) { Landmark l=find(ref.id()).orElseThrow(()->new IllegalStateException("missing fragment")); if(touched.add(l.id())) fragments.add(l); }
        Map<String,String> localAliases=new TreeMap<>(); aliases.forEach((id,target)->{ if(touched.contains(target)) localAliases.put(id,target); });
        LandmarkRepository repo=LandmarkRepository.restore(new LandmarkRepository.Snapshot(fragments,localAliases,Map.of(),Map.of()));
        Landmark merged=repo.mergeVerified(proof,tick,policy,reconciled); var snapshot=repo.snapshot();
        touched.remove(merged.id());
        return stage(List.of(merged),touched,snapshot.aliases(),Map.of(),Map.of());
    }
    public PendingMutation stageSplit(LandmarkRepository.RevisionRef parent,List<Landmark> children) {
        unlocked(); Landmark old=find(parent.id()).orElseThrow(()->new IllegalStateException("missing parent"));
        LandmarkRepository repo=new LandmarkRepository(); repo.put(old,-1); repo.split(new LandmarkRepository.RevisionRef(old.id(),parent.revision()),children);
        for(Landmark child:children) if((records.containsKey(child.id()) && !child.id().equals(old.id())) || aliases.containsKey(child.id()) || tombstones.containsKey(child.id()))
            throw new IllegalArgumentException("split seed conflicts with catalog");
        var snapshot=repo.snapshot(); return stage(snapshot.landmarks(),Set.of(old.id()),Map.of(),snapshot.tombstones(),snapshot.lineage());
    }
    public PendingMutation stageDelete(LandmarkRepository.RevisionRef ref) {
        unlocked(); Landmark l=find(ref.id()).orElseThrow(()->new IllegalStateException("missing record"));
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
        for(Landmark l:values) { String key=recordKey(l); checkKey(key); List<String> geometryKeys=l.geometry().pages().stream().map(LandmarkStore::geometryKey).toList(); writes.add(new PageWrite(key,()->LandmarkNbt.encodeLandmark(l,geometryKeys))); }
        pending=new PendingMutation(List.copyOf(writes),List.copyOf(values),Set.copyOf(removals),Map.copyOf(newAliases),Map.copyOf(retired),Map.copyOf(history),nextProfile); return pending;
    }
    public final class PendingMutation {
        private final List<PageWrite> writes;
        private final List<Landmark> values;
        private final Set<String> removals;
        private final Map<String,String> newAliases;
        private final Map<String,Long> retired;
        private final Map<String,List<String>> history;
        private final EmbeddingProfile nextProfile;
        private int cursor;
        private boolean complete,cancelled;
        private PendingMutation(List<PageWrite> writes,List<Landmark> values,Set<String> removals,Map<String,String> newAliases,Map<String,Long> retired,Map<String,List<String>> history,EmbeddingProfile nextProfile) {
            this.writes=writes; this.values=values; this.removals=removals; this.newAliases=newAliases; this.retired=retired; this.history=history; this.nextProfile=nextProfile;
        }
        public boolean complete() { checkThread(); return complete; }
        public int remainingPages() { checkThread(); return complete?0:writes.size()-cursor+1; }
        /** At most maxPages newly dirtied states, including the final manifest publication.
         * Unchanged immutable versions consume work but are not marked dirty. No automatic disk IO.
         */
        public int advance(int maxPages) {
            checkThread(); if(cancelled) throw new IllegalStateException("cancelled mutation");
            if(maxPages<=0) throw new IllegalArgumentException("page budget"); if(complete) return 0;
            int worked=0;
            while(cursor<writes.size() && worked<maxPages) {
                PageWrite write=writes.get(cursor); NbtCompound encoded=write.encode.get(); BlobState old=manager.get(BlobState.TYPE,write.key);
                if(old==null) { old=new BlobState(); old.payload=encoded.copy(); old.markDirty(); manager.set(write.key,old); }
                else if(!encoded.equals(old.payload)) throw new IllegalStateException("immutable page version conflict: "+write.key);
                cursor++; worked++;
            }
            if(cursor==writes.size() && worked<maxPages) {
                removals.forEach(records::remove); for(Landmark l:values) records.put(l.id(),new Ref(recordKey(l),l.revision()));
                aliases.putAll(newAliases); tombstones.putAll(retired); lineage.putAll(history); profile=nextProfile;
                for(String id:List.copyOf(aliases.keySet())) aliases.put(id,resolve(id));
                markDirty(); complete=true; pending=null; worked++;
            }
            return worked;
        }
        /** Cancel leaves only unreferenced immutable pages, never partially published topology. */
        public void cancel() { checkThread(); if(complete) throw new IllegalStateException("already published"); cancelled=true; if(pending==this) pending=null; }
    }
    private static final class BlobState extends PersistentState {
        private NbtCompound payload;
        private static final Type<BlobState> TYPE=new Type<>(BlobState::new,(n,r)->{
            if(!n.contains("payload",NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("invalid page payload");
            BlobState s=new BlobState(); s.payload=n.getCompound("payload").copy(); return s;
        },DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
        @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup r) {
            if(payload==null) throw new IllegalStateException("uninitialized page"); n.put("payload",payload.copy()); return n;
        }
    }
    public static LandmarkStore fromNbt(NbtCompound n,RegistryWrapper.WrapperLookup r) {
        if(!n.contains("schema",NbtElement.INT_TYPE) || n.getInt("schema")!=LandmarkNbt.SCHEMA) throw new IllegalArgumentException("unsupported landmark manifest");
        LandmarkStore store=new LandmarkStore();
        if(n.contains("profile",NbtElement.COMPOUND_TYPE)) store.profile=LandmarkNbt.decodeProfile(n.getCompound("profile"));
        for(String field:List.of("records","aliases","tombstones","lineage")) if(!n.contains(field,NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("missing manifest field: "+field);
        NbtCompound records=n.getCompound("records");
        for(String id:records.getKeys()) {
            if(!records.contains(id,NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("invalid record reference");
            NbtCompound ref=records.getCompound(id);
            if(!ref.contains("key",NbtElement.STRING_TYPE) || !ref.contains("revision",NbtElement.LONG_TYPE) || ref.getLong("revision")<0) throw new IllegalArgumentException("invalid record revision");
            String key=ref.getString("key"); checkKey(key);
            if(!key.equals("mysticism.landmark.record."+id+"."+ref.getLong("revision"))) throw new IllegalArgumentException("record key identity mismatch");
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
        n.putInt("schema",LandmarkNbt.SCHEMA); if(profile!=null) n.put("profile",LandmarkNbt.encodeProfile(profile));
        NbtCompound refs=new NbtCompound(); records.forEach((id,ref)->{ NbtCompound e=new NbtCompound(); e.putString("key",ref.key); e.putLong("revision",ref.revision); refs.put(id,e); }); n.put("records",refs);
        NbtCompound alias=new NbtCompound(); aliases.forEach(alias::putString); n.put("aliases",alias);
        NbtCompound retired=new NbtCompound(); tombstones.forEach(retired::putLong); n.put("tombstones",retired);
        NbtCompound history=new NbtCompound(); lineage.forEach((id,children)->{ NbtList list=new NbtList(); children.forEach(child->list.add(NbtString.of(child))); history.put(id,list); }); n.put("lineage",history); return n;
    }
}
