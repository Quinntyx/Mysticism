package io.github.mysticism.landmark.extract;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import java.util.*;

/** Scheduling/immutable-page revision ledger only; LandmarkStore is the authoritative geometry store. */
public final class ExtractionJournal extends PersistentState {
    public static final int MAX_REGIONS=512, MAX_IDS=8192;
    public record Region(String dimension,int x,int y,int z) {
        public String key(){return dimension+"/"+x+"/"+y+"/"+z;}
    }
    public static final class Entry {
        public final Region region;
        private long version;
        private List<String> ids=List.of();
        private String fingerprint="";
        private Set<String> seen=Set.of();
        private Entry(Region region){this.region=region;}
        public long version(){return version;}
        public List<String> ids(){return ids;}
        public String fingerprint(){return fingerprint;}
        public Set<String> seen(){return seen;}
    }
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private int idCount,seenCount;
    public Collection<Entry> entries(){return Collections.unmodifiableCollection(entries.values());}
    public Entry entry(Region region){
        Entry e=entries.get(region.key());if(e!=null)return e;
        if(entries.size()==MAX_REGIONS)return null;
        e=new Entry(region);entries.put(region.key(),e);markDirty();return e;
    }
    /** Reserve BEFORE any async work/staging: cancelled attempts never reuse page versions. */
    public long reserve(Entry e){e.version=Math.incrementExact(e.version);markDirty();return e.version;}
    public void publish(Entry e,List<String> ids){
        List<String> next=ids.stream().distinct().sorted().toList();
        if(next.size()>ExtractionGraph.MAX_FEATURES || idCount-e.ids.size()+next.size()>MAX_IDS)throw new IllegalStateException("Extraction journal identity budget");
        requireCapacity(e,next);Set<String> all=new TreeSet<>(e.seen);all.addAll(next);seenCount+=all.size()-e.seen.size();e.seen=Set.copyOf(all);
        idCount+=next.size()-e.ids.size();e.ids=next;e.fingerprint="";markDirty();
    }
    public void requireCapacity(Entry e,List<String> next){long added=next.stream().distinct().filter(id->!e.seen.contains(id)).count();if(seenCount+added>MAX_IDS)throw new IllegalStateException("Extraction source lineage budget");}
    public void completed(Entry e,String fingerprint){if(!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Snapshot fingerprint");e.fingerprint=fingerprint;markDirty();}
    public static final Type<ExtractionJournal> TYPE=new Type<>(ExtractionJournal::new,ExtractionJournal::fromNbt,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    public static ExtractionJournal get(MinecraftServer server){return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE,"mysticism.extraction.v1");}
    public static ExtractionJournal fromNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        if(nbt.getInt("schema")!=1)throw new IllegalArgumentException("Extraction journal schema");
        ExtractionJournal result=new ExtractionJournal();NbtList list=nbt.getList("regions",NbtElement.COMPOUND_TYPE);
        if(list.size()>MAX_REGIONS)throw new IllegalArgumentException("Extraction region budget");
        for(int i=0;i<list.size();i++){
            NbtCompound c=list.getCompound(i);Region r=new Region(c.getString("dimension"),c.getInt("x"),c.getInt("y"),c.getInt("z"));
            if(!r.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || Math.floorMod(r.x()-8,32)!=0 || Math.floorMod(r.z()-8,32)!=0 || Math.floorMod(r.y(),32)!=0)
                throw new IllegalArgumentException("Invalid extraction source region");
            Entry e=new Entry(r);e.version=c.getLong("version");if(e.version<0)throw new IllegalArgumentException("Negative page revision");
            NbtList ids=c.getList("ids",NbtElement.STRING_TYPE);if(ids.size()>ExtractionGraph.MAX_FEATURES)throw new IllegalArgumentException("Regional identity budget");ArrayList<String> values=new ArrayList<>();
            for(int j=0;j<ids.size();j++){String id=ids.getString(j);if(!id.matches("lm-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid extraction id");values.add(id);}
            if(result.entries.putIfAbsent(r.key(),e)!=null)throw new IllegalArgumentException("Duplicate extraction source region");
            result.publish(e,values);
            NbtList history=c.getList("seen",NbtElement.STRING_TYPE);if(history.size()>MAX_IDS)throw new IllegalArgumentException("Source lineage budget");Set<String> all=new TreeSet<>(e.seen);for(int j=0;j<history.size();j++){String id=history.getString(j);if(!id.matches("lm-[a-f0-9]{64}"))throw new IllegalArgumentException("Source lineage id");all.add(id);}result.seenCount+=all.size()-e.seen.size();if(result.seenCount>MAX_IDS)throw new IllegalArgumentException("Source lineage budget");e.seen=Set.copyOf(all);
            e.fingerprint=c.getString("fingerprint");if(!e.fingerprint.isEmpty() && !e.fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Snapshot fingerprint");
        }
        result.setDirty(false);return result;
    }
    @Override public NbtCompound writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        nbt.putInt("schema",1);NbtList list=new NbtList();
        for(Entry e:entries.values()){
            NbtCompound c=new NbtCompound();c.putString("dimension",e.region.dimension());c.putInt("x",e.region.x());c.putInt("y",e.region.y());c.putInt("z",e.region.z());c.putLong("version",e.version);c.putString("fingerprint",e.fingerprint);
            NbtList ids=new NbtList();for(String id:e.ids)ids.add(NbtString.of(id));c.put("ids",ids);NbtList history=new NbtList();for(String id:new TreeSet<>(e.seen))history.add(NbtString.of(id));c.put("seen",history);list.add(c);
        }
        nbt.put("regions",list);return nbt;
    }
}
