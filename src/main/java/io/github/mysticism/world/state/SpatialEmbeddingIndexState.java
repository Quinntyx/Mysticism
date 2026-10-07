package io.github.mysticism.world.state;

import com.mojang.serialization.Codec;
import io.github.mysticism.Codecs;
import io.github.mysticism.embedding.*;
import io.github.mysticism.vector.*;
import io.github.mysticism.world.region.*;
import io.github.mysticism.world.region.impl.BiomeSpiritualRegion;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import org.slf4j.*;
import java.util.*;
import java.util.concurrent.*;

/** Retains canonical region geometry across profile changes; vectors are rebuilt, never resized. */
public class SpatialEmbeddingIndexState extends PersistentState {
    public static final Logger LOGGER=LoggerFactory.getLogger("Mysticism-SpatialEmbeddingIndexState");
    private static final String SAVE_KEY="mysticism.spatial_index";
    private static final Codec<Map<String,Vec384f>> EMBEDDINGS=Codec.unboundedMap(Codec.STRING,Codecs.VEC384F);
    private static final Codec<Map<String,BiomeSpiritualRegion>> BIOMES=Codec.unboundedMap(Codec.STRING,BiomeSpiritualRegion.CODEC.codec());
    private static final Codec<Map<String,String>> DESCRIPTORS=Codec.unboundedMap(Codec.STRING,Codec.STRING);
    private KnnIndex index=new SimpleKnnIndex();
    private final Map<String,ISpiritualRegion> regions=new TreeMap<>();
    private final Map<String,String> descriptors=new TreeMap<>();
    private boolean needsRebuild;
    private CompletableFuture<Void> rebuilding;
    public KnnIndex getIndex(){return index;}
    public void touch(){markDirty();}
    public Map<String,ISpiritualRegion> regionsView(){return Collections.unmodifiableMap(new TreeMap<>(regions));}
    public ISpiritualRegion getRegion(String id){return regions.get(id);}
    public boolean needsRebuild(){return needsRebuild;}
    public boolean isRebuilding(){return rebuilding!=null&&!rebuilding.isDone();}
    public static SpatialEmbeddingIndexState fromNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        var state=new SpatialEmbeddingIndexState();
        // Decode geometry independently: legacy vector length must not discard canonical regions.
        var decodedGeometry=BIOMES.parse(NbtOps.INSTANCE,nbt.get("regions"));
        boolean geometryValid=decodedGeometry.error().isEmpty();
        if(!geometryValid)LOGGER.warn("Malformed spatial geometry; retaining only valid source regions");
        var geometry=decodedGeometry.result();
        geometry.ifPresent(state.regions::putAll);
        if(!geometryValid && nbt.get("regions") instanceof NbtCompound rawRegions){
            // Only wholly valid entries survive; partial decoded regions are not canonical geometry.
            for(String id:rawRegions.getKeys())parseBiomeRegion(rawRegions.get(id)).ifPresent(region->state.regions.put(id,region));
        }
        if(EmbeddingNbt.compatible(nbt)){
            try{
                var vectors=EMBEDDINGS.parse(NbtOps.INSTANCE,nbt.get("embedding")).getOrThrow();
                state.descriptors.putAll(DESCRIPTORS.parse(NbtOps.INSTANCE,nbt.get("descriptors")).getOrThrow());
                state.needsRebuild=nbt.getBoolean("needsRebuild");
                boolean complete=geometryValid&&geometry.isPresent()&&state.descriptors.keySet().equals(state.regions.keySet())
                        && (state.needsRebuild ? vectors.isEmpty() : vectors.keySet().equals(state.regions.keySet()));
                if(!complete)throw new IllegalArgumentException("Incomplete spatial generation");
                vectors.forEach(state.index::upsert);
                return state;
            }catch(RuntimeException error){LOGGER.warn("Invalid spatial generation; discarding derived vectors and rebuilding",error);}
        }else LOGGER.warn("Incompatible spatial profile; preserving geometry, rebuilding biome/dimension descriptors");
        state.index=new SimpleKnnIndex();state.descriptors.clear();state.needsRebuild=true;
        state.regions.forEach((id,region)->{
            if(region instanceof BiomeSpiritualRegion biome){String dimension=id.split("\\|",2)[0];state.descriptors.put(id,CanonicalDescriptors.region(dimension,biome.biomeId().toString()));}
        });
        state.markDirty();return state;
    }
    private static Optional<BiomeSpiritualRegion> parseBiomeRegion(NbtElement tag){return BiomeSpiritualRegion.CODEC.codec().parse(NbtOps.INSTANCE,tag).result();}
    @Override public NbtCompound writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        EmbeddingNbt.stamp(nbt);Map<String,Vec384f> vectors=new TreeMap<>();index.forEach(vectors::put);
        Map<String,BiomeSpiritualRegion> geometry=new TreeMap<>();regions.forEach((id,r)->{if(r instanceof BiomeSpiritualRegion b)geometry.put(id,b);});
        nbt.put("embedding",EMBEDDINGS.encodeStart(NbtOps.INSTANCE,vectors).getOrThrow());
        nbt.put("regions",BIOMES.encodeStart(NbtOps.INSTANCE,geometry).getOrThrow());
        nbt.put("descriptors",DESCRIPTORS.encodeStart(NbtOps.INSTANCE,descriptors).getOrThrow());
        nbt.putBoolean("needsRebuild",needsRebuild);
        nbt.remove("archive");return nbt;
    }
    public static final PersistentState.Type<SpatialEmbeddingIndexState> TYPE=new PersistentState.Type<>(SpatialEmbeddingIndexState::new,SpatialEmbeddingIndexState::fromNbt,null);
    public static SpatialEmbeddingIndexState get(MinecraftServer server){return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE,SAVE_KEY);}
    public CompletableFuture<Void> rebuildAsync(MinecraftServer server){
        if(isRebuilding())return rebuilding.copy();
        Map<String,String> canonical=new TreeMap<>();
        var biomeRegistry=server.getRegistryManager().get(net.minecraft.registry.RegistryKeys.BIOME);
        regions.forEach((id,region)->{
            if(region instanceof BiomeSpiritualRegion biome){
                var tags=biomeRegistry.getEntry(biome.biomeId()).map(entry->entry.streamTags().map(tag->tag.id().toString()).toList()).orElse(List.of());
                canonical.put(id,CanonicalDescriptors.region(id.split("\\|",2)[0],biome.biomeId().toString(),tags));
            }
        });
        if(!needsRebuild&&descriptors.equals(canonical)&&index.size()==canonical.size())return CompletableFuture.completedFuture(null);
        needsRebuild=true;index=new SimpleKnnIndex();descriptors.clear();descriptors.putAll(canonical);markDirty();
        rebuilding=IndexGeneration.build(server,canonical).thenAccept(generation->{index=generation.index();descriptors.clear();descriptors.putAll(generation.descriptors());needsRebuild=false;markDirty();});
        rebuilding.whenComplete((v,error)->{if(error!=null)LOGGER.warn("Spatial embeddings unavailable; preserved geometry remains unindexed",error);});
        return rebuilding.copy();
    }
    public boolean putIfAbsent(String id,ISpiritualRegion region,Vec384f embedding){
        if(regions.containsKey(id))return false;
        if(needsRebuild)throw new IllegalStateException("Spatial generation awaiting rebuild");
        EmbeddingSpace.requireCurrent(embedding);index.upsert(id,embedding);regions.put(id,region);
        if(region instanceof BiomeSpiritualRegion biome)descriptors.put(id,CanonicalDescriptors.region(id.split("\\|",2)[0],biome.biomeId().toString()));
        markDirty();return true;
    }
    /** Observed chunk boxes only: never expand a bounding box across unseen chunks. */
    public void observeBiome(String id,BiomeSpiritualRegion region,String descriptor,Vec384f embedding){
        if(needsRebuild)throw new IllegalStateException("Spatial generation awaiting rebuild");
        EmbeddingSpace.requireCurrent(embedding);
        ISpiritualRegion previous=regions.get(id);
        if(previous instanceof BiomeSpiritualRegion old){
            List<ChunkBox> boxes=new ArrayList<>(old.boxes());
            for(var box:region.boxes())if(!boxes.contains(box))boxes.add(box);
            if(boxes.size()==old.boxes().size())return;
            region=new BiomeSpiritualRegion(old.regionX(),old.regionZ(),old.biomeId(),boxes);
        }
        index.upsert(id,embedding);regions.put(id,region);descriptors.put(id,descriptor);markDirty();
    }
    public boolean hasAnyInVanillaRegion(ServerWorld world,int rx,int rz){String prefix=world.getRegistryKey().getValue()+"|vregion|"+rx+","+rz+"|";return regions.keySet().stream().anyMatch(id->id.startsWith(prefix));}
}
