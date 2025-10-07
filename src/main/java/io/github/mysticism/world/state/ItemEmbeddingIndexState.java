package io.github.mysticism.world.state;

import ai.djl.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mysticism.Codecs;
import io.github.mysticism.vector.KnnIndex;
import io.github.mysticism.vector.Metric;
import io.github.mysticism.vector.SimpleKnnIndex;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class ItemEmbeddingIndexState extends PersistentState {
    private static final String SAVE_KEY = "mysticism.item_index";
    public static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-ItemEmbeddingIndexState");

    private final KnnIndex index = new SimpleKnnIndex();
    private boolean populated = false;
    public KnnIndex getIndex() { return index; }
    public boolean isPopulated() { return populated; }
    public void touch() { this.markDirty(); }

    public static final Codec<ItemEmbeddingIndexState> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.unboundedMap(Codec.STRING, Codecs.VEC384F).fieldOf("entries").forGetter(ItemEmbeddingIndexState::snapshot),
                    Codec.BOOL.fieldOf("populated").forGetter(f -> f.populated)
            ).apply(instance, ItemEmbeddingIndexState::fromSnapshot));

    /** Create a state from a snapshot map (decode path) */
    private static ItemEmbeddingIndexState fromSnapshot(Map<String, Vec384f> snapshot, boolean populated) {
        LOGGER.info("Loading item embeddings from snapshot...");
        ItemEmbeddingIndexState s = new ItemEmbeddingIndexState();
        snapshot.forEach((id, v) -> {
            s.index.upsert(id, v);
//            LOGGER.info("Loading embedding for {}", id);
        });
        s.populated = populated;
        return s;
    }

    /** Build a stable snapshot map (encode path) */
    private Map<String, Vec384f> snapshot() {
        LOGGER.info("Creating snapshot...");
        Map<String, Vec384f> out = new HashMap<>();
        // Use the index's safe iterator if you have one
        index.forEach((id, vec) -> out.put(id, vec.clone()));
        return out;
    }

    // ======== 1.21.4 Changes Start ========

    /**
     * Creates a state object from NBT data using the codec.
     * This is the deserializer function required by PersistentState.Type.
     */
    public static ItemEmbeddingIndexState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup wrapperLookup) {
        return CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, nbt))
                .resultOrPartial(LOGGER::error)
                .orElseGet(ItemEmbeddingIndexState::new);
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
    public static final PersistentState.Type<ItemEmbeddingIndexState> TYPE =
            new PersistentState.Type<>(ItemEmbeddingIndexState::new, ItemEmbeddingIndexState::fromNbt, null);

    /**
     * Accessor that creates/loads on world startup.
     * The save key is now passed as a separate argument to getOrCreate.
     */
    public static ItemEmbeddingIndexState get(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available yet.");
        }
        return overworld.getPersistentStateManager().getOrCreate(TYPE, SAVE_KEY);
    }

    // ======== 1.21.4 Changes End ========

    public boolean populateIfNeeded(Runnable seeder) {
        LOGGER.info("Attempting to populate item embedding...");
        if (this.populated) {
            LOGGER.info("Item embedding already populated!");
            return false;
        }
        LOGGER.info("Populating item embedding...");
        seeder.run();
        this.populated = true;
        this.markDirty();
        LOGGER.info("Done!");
        return true;
    }

    public List<String> nearestIds(int k, Vec384f q) {
        List<String> out = new ArrayList<>();
        for (var i : this.index.kNN(k, q, Metric.EUCLIDEAN)) out.add(i.getKey());

        return out;
    }

    public Vec384f getVec(String id) {
        // expose a lookup; if you don’t have one, add it to your index impl
        return this.index.get(id);
    }

}