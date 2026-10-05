package io.github.mysticism.world.state;

import com.mojang.serialization.Codec;
import io.github.mysticism.Codecs;
import io.github.mysticism.embedding.*;
import io.github.mysticism.vector.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import org.slf4j.*;
import java.util.*;
import java.util.concurrent.*;

/** Server-thread state; new generations activate only after every canonical item succeeds. */
public class ItemEmbeddingIndexState extends PersistentState {
    private static final String SAVE_KEY="mysticism.item_index";
    public static final Logger LOGGER=LoggerFactory.getLogger("Mysticism-ItemEmbeddingIndexState");
    private static final Codec<Map<String,Vec384f>> ENTRIES=Codec.unboundedMap(Codec.STRING,Codecs.VEC384F);
    private static final Codec<Map<String,String>> DESCRIPTORS=Codec.unboundedMap(Codec.STRING,Codec.STRING);
    private KnnIndex index=new SimpleKnnIndex();
    private Map<String,String> descriptors=Map.of();
    private boolean populated;
    private NbtCompound archive;
    private CompletableFuture<Void> rebuilding;
    public KnnIndex getIndex(){return index;}
    public boolean isPopulated(){return populated;}
    public void touch(){markDirty();}
    public Vec384f getVec(String id){return index.get(id);}
    public List<String> nearestIds(int k,Vec384f q){return index.kNN(k,q,Metric.EUCLIDEAN).stream().map(pair->pair.getKey()).toList();}
    public static ItemEmbeddingIndexState fromNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        var state=new ItemEmbeddingIndexState();
        if(EmbeddingNbt.compatible(nbt)){
            try{
                var vectors=ENTRIES.parse(NbtOps.INSTANCE,nbt.get("entries")).getOrThrow();
                state.descriptors=Map.copyOf(DESCRIPTORS.parse(NbtOps.INSTANCE,nbt.get("descriptors")).getOrThrow());
                if(!vectors.keySet().equals(state.descriptors.keySet()))throw new IllegalArgumentException("Incomplete item generation");
                vectors.forEach(state.index::upsert);state.populated=nbt.getBoolean("populated");
                if(nbt.contains("archive"))state.archive=nbt.getCompound("archive").copy();
                return state;
            }catch(RuntimeException error){LOGGER.warn("Invalid item generation; archiving and rebuilding",error);}
        }else LOGGER.warn("Incompatible item embedding profile; archiving legacy vectors, rebuilding from registry IDs");
        state=new ItemEmbeddingIndexState();state.archive=nbt.copy();state.markDirty();return state;
    }
    @Override public NbtCompound writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        EmbeddingNbt.stamp(nbt);Map<String,Vec384f> snapshot=new TreeMap<>();index.forEach(snapshot::put);
        nbt.put("entries",ENTRIES.encodeStart(NbtOps.INSTANCE,snapshot).getOrThrow());
        nbt.put("descriptors",DESCRIPTORS.encodeStart(NbtOps.INSTANCE,descriptors).getOrThrow());
        nbt.putBoolean("populated",populated);if(archive!=null)nbt.put("archive",archive.copy());return nbt;
    }
    public static final PersistentState.Type<ItemEmbeddingIndexState> TYPE=new PersistentState.Type<>(ItemEmbeddingIndexState::new,ItemEmbeddingIndexState::fromNbt,null);
    public static ItemEmbeddingIndexState get(MinecraftServer server){return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE,SAVE_KEY);}
    /** Must be called on server thread. No joins. Rechecks registry/tag descriptors after reload. */
    public CompletableFuture<Void> populateAsync(MinecraftServer server){
        if(rebuilding!=null&&!rebuilding.isDone())return rebuilding.copy();
        TreeMap<String,String> canonical=new TreeMap<>();
        for(var item:Registries.ITEM){String id=Registries.ITEM.getId(item).toString();
            var tags=Registries.ITEM.getEntry(item).streamTags().map(tag->tag.id().toString()).toList();
            canonical.put(id,CanonicalDescriptors.item(id,tags));
        }
        if(populated&&descriptors.equals(canonical)&&index.size()==canonical.size())return CompletableFuture.completedFuture(null);
        // A stale same-profile registry generation is not advertised while rebuilding either.
        populated=false;index=new SimpleKnnIndex();descriptors=Map.of();markDirty();
        rebuilding=IndexGeneration.build(server,canonical).thenAccept(generation->{index=generation.index();descriptors=generation.descriptors();populated=true;markDirty();});
        rebuilding.whenComplete((v,error)->{if(error!=null)LOGGER.warn("Item embeddings unavailable; generation not activated",error);});
        return rebuilding.copy();
    }
    /** Legacy callback adapter; it cannot certify a generation. Use populateAsync for activation. */
    @Deprecated public boolean populateIfNeeded(Runnable seeder){if(populated)return false;seeder.run();markDirty();return true;}
}
