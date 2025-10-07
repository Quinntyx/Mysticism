package io.github.mysticism.world.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mysticism.Codecs;
import io.github.mysticism.vector.KnnIndex;
import io.github.mysticism.vector.SimpleKnnIndex;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.world.region.impl.BiomeSpiritualRegion;
import io.github.mysticism.world.region.ISpiritualRegion;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SpatialEmbeddingIndexState extends PersistentState {
    public static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-SpatialEmbeddingIndexState");
    private static final String SAVE_KEY = "mysticism.spatial_index";

    /** id -> embedding vector (stable, forward compatible) */
    private static final Codec<Map<String, Vec384f>> EMBEDDING =
            Codec.unboundedMap(Codec.STRING, Codecs.VEC384F);

    /** id -> biome-region (vanilla-region bounded, possibly multiple ChunkBoxes) */
    private static final Codec<Map<String, BiomeSpiritualRegion>> BIOME_MAP =
            Codec.unboundedMap(Codec.STRING, BiomeSpiritualRegion.CODEC.codec());

    /** In-memory stores */
    private final KnnIndex index = new SimpleKnnIndex();
    private final HashMap<String, ISpiritualRegion> regions = new HashMap<>();

    public KnnIndex getIndex() { return index; }
    public void touch() { this.markDirty(); }
    public Map<String, ISpiritualRegion> regionsView() { return Collections.unmodifiableMap(regions); }

    /**
     * Persistent codec:
     * - "embedding": map of id -> Vec384f (full snapshot of the KNN index)
     * - "regions":   map of id -> BiomeSpiritualRegion (single type for now; extend later if you add more)
     */
    public static final Codec<SpatialEmbeddingIndexState> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    EMBEDDING.fieldOf("embedding").forGetter(SpatialEmbeddingIndexState::snapshotEmbeddings),
                    BIOME_MAP.optionalFieldOf("regions", Map.of()).forGetter(SpatialEmbeddingIndexState::snapshotBiomeRegions)
            ).apply(i, SpatialEmbeddingIndexState::fromSnapshot));

    /** Decode path: rebuild KNN index and region map from snapshots. */
    private static SpatialEmbeddingIndexState fromSnapshot(Map<String, Vec384f> embSnap,
                                                           Map<String, BiomeSpiritualRegion> regionSnap) {
        LOGGER.info("Loading SpatialIndex from snapshot");
        SpatialEmbeddingIndexState s = new SpatialEmbeddingIndexState();

        // restore vectors
        embSnap.forEach(s.index::upsert);

        // restore regions
        s.regions.putAll(regionSnap);

        return s;
    }

    /** Encode path: stable snapshot of embeddings. */
    private Map<String, Vec384f> snapshotEmbeddings() {
        Map<String, Vec384f> out = new HashMap<>();
        index.forEach((id, vec) -> out.put(id, vec.clone()));
        return out;
    }

    /** Encode path: only write biome regions (other region types can be added later). */
    private Map<String, BiomeSpiritualRegion> snapshotBiomeRegions() {
        Map<String, BiomeSpiritualRegion> out = new HashMap<>();
        for (var e : regions.entrySet()) {
            if (e.getValue() instanceof BiomeSpiritualRegion br) {
                out.put(e.getKey(), br);
            }
        }
        return out;
    }

    // ======== 1.21.4 Changes Start ========

    /**
     * Creates a state object from NBT data using the codec.
     * This is the deserializer function required by PersistentState.Type.
     */
    // THIS IS THE LINE THAT WAS FIXED:
    public static SpatialEmbeddingIndexState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup wrapperLookup) {
        return CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, nbt))
                .resultOrPartial(LOGGER::error)
                .orElseGet(SpatialEmbeddingIndexState::new);
    }

    /**
     * Writes the state object to NBT data using the codec.
     * This overrides the method in PersistentState.
     */
    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup wrapperLookup) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .resultOrPartial(LOGGER::error)
                .ifPresent(encodedNbt -> {
                    if (encodedNbt instanceof NbtCompound compound) {
                        compound.getKeys().forEach(key -> nbt.put(key, compound.get(key)));
                    }
                });
        return nbt;
    }

    /**
     * The state type for 1.21.4, which takes a supplier and a deserializer.
     * The third argument (DataFixTypes) can be null if you are not using data fixers.
     */
    public static final PersistentState.Type<SpatialEmbeddingIndexState> TYPE =
            new PersistentState.Type<>(SpatialEmbeddingIndexState::new, SpatialEmbeddingIndexState::fromNbt, null);

    /**
     * Accessor that creates/loads on world startup.
     * The save key is now passed as a separate argument to getOrCreate.
     */
    public static SpatialEmbeddingIndexState get(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available yet.");
        }
        return overworld.getPersistentStateManager().getOrCreate(TYPE, SAVE_KEY);
    }

    // ======== 1.21.4 Changes End ========

    /** Insert if absent; also upserts the embedding. */
    public boolean putIfAbsent(String id, ISpiritualRegion region, Vec384f embedding) {
        if (regions.containsKey(id)) return false;
        regions.put(id, region);
        index.upsert(id, embedding);
        touch();
        return true;
    }

    /**
     * Optional fast pre-check used by HorizonSeeder:
     * returns true if ANY region already exists within the given vanilla region (rX,rZ)
     * for this world's dimension.
     *
     * Keys are formatted as:
     *   <dimId> | "vregion" | rX "," rZ | "|" | <biomeId>
     */
    public boolean hasAnyInVanillaRegion(ServerWorld world, int rX, int rZ) {
        String prefix = world.getRegistryKey().getValue() + "|vregion|" + rX + "," + rZ + "|";
        // A linear scan over keys is usually fine; optimize with a side-index if needed later.
        for (String key : regions.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }
}