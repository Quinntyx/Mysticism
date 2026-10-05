package io.github.mysticism.activity;

import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.util.WorldSavePath;
import java.nio.file.Files;
import java.util.*;

/** Separate influence persistence. The landmark catalog/geometry remains authoritative in LandmarkStore. */
public final class LandmarkActivityState extends PersistentState {
    public static final int LIMIT=512, CELL_LIMIT=256;
    public static final Type<LandmarkActivityState> TYPE=new Type<>(LandmarkActivityState::new,LandmarkActivityState::read,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    static final class Influence {
        Vec384f vector; double level; long tick;
        SparseOctree<String> claims;
        final Set<String> owners=new TreeSet<>();
        Influence(Vec384f v,double l,long t){vector=v.clone();level=l;tick=t;}
        double level(long now){return level*Math.pow(0.5,Math.max(0,now-tick)/24000.0);}
    }
    final Map<String,Influence> entries=new TreeMap<>();
    private NbtCompound archive;
    private final Map<String,Integer> claimCounts=new HashMap<>();
    int claimedBy(UUID player){return claimCounts.getOrDefault(player.toString(),0);}
    void remove(String id){Influence old=entries.remove(id);if(old!=null){old.owners.forEach(owner->claimCounts.computeIfPresent(owner,(k,v)->v<=1?null:v-1));markDirty();}}
    void publish(String id,Influence next){
        Influence old=entries.put(id,next);
        if(old!=null)old.owners.forEach(owner->claimCounts.computeIfPresent(owner,(k,v)->v<=1?null:v-1));
        next.owners.forEach(owner->claimCounts.merge(owner,1,Integer::sum));markDirty();
    }
    public static LandmarkActivityState get(MinecraftServer server){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        var manager=server.getOverworld().getPersistentStateManager();String key="mysticism.landmark_activity.v1";
        LandmarkActivityState state=manager.get(TYPE,key);
        if(state==null&&!Files.notExists(server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(key+".dat")))
            throw new IllegalStateException("unreadable activity state; refusing replacement");
        if(state==null){state=new LandmarkActivityState();manager.set(key,state);}return state;
    }
    static LandmarkActivityState read(NbtCompound tag,RegistryWrapper.WrapperLookup lookup){
        LandmarkActivityState state=new LandmarkActivityState();
        if(tag.contains("embeddingArchive",NbtElement.COMPOUND_TYPE))state.archive=tag.getCompound("embeddingArchive").copy();
        if(!EmbeddingNbt.compatible(tag)||tag.getInt("schema")!=1){state.archive=tag.copy();return state;}
        if(!tag.contains("entries",NbtElement.LIST_TYPE))throw new IllegalArgumentException("missing activity entries");
        NbtList list=tag.getList("entries",NbtElement.COMPOUND_TYPE);
        if(list.size()!=((NbtList)tag.get("entries")).size())throw new IllegalArgumentException("activity entry type");
        if(list.size()>LIMIT)throw new IllegalArgumentException("activity entry budget");
        for(int i=0;i<list.size();i++){
            NbtCompound n=list.getCompound(i);String id=n.getString("id");
            if(!id.matches("lm-[0-9a-f]{64}")||state.entries.containsKey(id))throw new IllegalArgumentException("activity identity");
            if(!n.contains("vector",NbtElement.INT_ARRAY_TYPE)||!n.contains("level",NbtElement.DOUBLE_TYPE)||!n.contains("tick",NbtElement.LONG_TYPE))throw new IllegalArgumentException("activity fields");
            double level=n.getDouble("level");long tick=n.getLong("tick");
            if(!Double.isFinite(level)||level<0||level>0.35||tick<0)throw new IllegalArgumentException("activity bounds");
            Influence v=new Influence(Vec384f.fromBits(n.getIntArray("vector")),level,tick);
            if(n.contains("root")&&!n.contains("root",NbtElement.LONG_ARRAY_TYPE))throw new IllegalArgumentException("claim root type");
            if(n.contains("root",NbtElement.LONG_ARRAY_TYPE)){
                Bounds root=LandmarkNbt.getBounds(n,"root");v.claims=SparseOctree.empty(root,1,512);
                NbtList cells=n.getList("claims",NbtElement.COMPOUND_TYPE);if(cells.size()>CELL_LIMIT)throw new IllegalArgumentException("claim budget");
                for(int j=0;j<cells.size();j++){NbtCompound c=cells.getCompound(j);String owner=UUID.fromString(c.getString("owner")).toString();
                    v.claims=v.claims.with(LandmarkNbt.getBounds(c,"bounds"),owner,4096);}
                v.claims.cells(CELL_LIMIT);
            }
            NbtList owners=n.getList("owners",NbtElement.STRING_TYPE);if(owners.size()>16)throw new IllegalArgumentException("owner budget");
            for(int j=0;j<owners.size();j++)v.owners.add(UUID.fromString(owners.getString(j)).toString());
            state.publish(id,v);
        }
        return state;
    }
    @Override public NbtCompound writeNbt(NbtCompound tag,RegistryWrapper.WrapperLookup lookup){
        EmbeddingNbt.stamp(tag);tag.putInt("schema",1);NbtList list=new NbtList();
        for(var e:entries.entrySet()){
            Influence v=e.getValue();NbtCompound n=new NbtCompound();n.putString("id",e.getKey());n.putIntArray("vector",v.vector.toBits());n.putDouble("level",v.level);n.putLong("tick",v.tick);
            if(v.claims!=null){LandmarkNbt.putBounds(n,"root",v.claims.rootBounds());NbtList cells=new NbtList();
                for(var cell:v.claims.cells(CELL_LIMIT)){NbtCompound c=new NbtCompound();LandmarkNbt.putBounds(c,"bounds",cell.bounds());c.putString("owner",cell.value());cells.add(c);}n.put("claims",cells);}
            NbtList owners=new NbtList();v.owners.forEach(owner->owners.add(NbtString.of(owner)));n.put("owners",owners);
            list.add(n);
        }
        tag.put("entries",list);if(archive!=null)tag.put("embeddingArchive",archive.copy());return tag;
    }
}
