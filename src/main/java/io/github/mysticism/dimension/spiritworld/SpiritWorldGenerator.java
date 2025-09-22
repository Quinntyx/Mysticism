package io.github.mysticism.dimension.spiritworld;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SpiritWorldGenerator extends ChunkGenerator {

    // --- Data-driven codec (optional but nice to have)
    public static final MapCodec<SpiritWorldGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    Codec.INT.fieldOf("min_y").forGetter(g -> g.minY),
                    Codec.INT.fieldOf("height").forGetter(g -> g.height),
                    Codec.INT.fieldOf("sea_level").forGetter(g -> g.seaLevel)
            ).apply(instance, SpiritWorldGenerator::new)
    );

    private final BiomeSource biomeSource; // keep a ref so the codec can serialize it
    private final int minY;
    private final int height;
    private final int seaLevel;

    /** Main ctor used by codec & programmatic creation */
    public SpiritWorldGenerator(BiomeSource biomeSource, int minY, int height, int seaLevel) {
        super(biomeSource);
        this.biomeSource = biomeSource;
        this.minY = minY;
        this.height = height;
        this.seaLevel = seaLevel;
    }

    /** Convenience: build a generator that uses minecraft:the_void via the dynamic registry. */
    public static SpiritWorldGenerator createVoid(DynamicRegistryManager drm,
                                                  int minY, int height, int seaLevel) {
        var biomes = drm.getOrThrow(RegistryKeys.BIOME);
        var voidBiome = biomes.getEntry(biomes.get(BiomeKeys.THE_VOID)); // (sometimes named getOrCreateEntry)
        return new SpiritWorldGenerator(new FixedBiomeSource(voidBiome), minY, height, seaLevel);
    }



    // --- Void world: everything below is a no-op or "all air"

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      net.minecraft.world.biome.source.BiomeAccess biomeAccess,
                      StructureAccessor structureAccessor, Chunk chunk) { }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) { }

    @Override
    public void populateEntities(ChunkRegion region) { }

    @Override
    public int getWorldHeight() { return height; }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig,
                                                  StructureAccessor structureAccessor, Chunk chunk) {
        // air-only
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getSeaLevel() { return seaLevel; }

    @Override
    public int getMinimumY() { return minY; }

    @Override
    public int getHeight(int x, int z, Heightmap.Type type, HeightLimitView world, NoiseConfig noiseConfig) {
        return minY; // void
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        BlockState[] states = new BlockState[height];
        Arrays.fill(states, Blocks.AIR.getDefaultState());
        return new VerticalBlockSample(minY, states);
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("SpiritWorldGenerator (void)");
        text.add("minY=" + minY + " height=" + height + " seaLevel=" + seaLevel);
    }
}
