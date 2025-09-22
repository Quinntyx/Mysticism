//package io.github.mysticism.dimension;
//
//import io.github.mysticism.dimension.spiritworld.SpiritWorldGenerator;
//
//import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
//import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
//import net.fabricmc.fabric.api.event.player.UseBlockCallback;
//
//import net.minecraft.registry.RegistryKey;
//import net.minecraft.registry.RegistryKeys;
//import net.minecraft.registry.entry.RegistryEntry;
//
//import net.minecraft.server.MinecraftServer;
//
//import net.minecraft.util.ActionResult;
//import net.minecraft.util.Identifier;
//
//import net.minecraft.world.World;
//import net.minecraft.world.biome.Biome;
//import net.minecraft.world.biome.BiomeKeys;
//import net.minecraft.world.biome.source.BiomeSource;
//import net.minecraft.world.biome.source.FixedBiomeSource;
//
//import net.minecraft.world.dimension.DimensionOptions; // aka LevelStem
//import net.minecraft.world.dimension.DimensionType;
//import net.minecraft.world.dimension.DimensionTypes;
//
//import net.minecraft.world.gen.chunk.ChunkGenerator;
//
//// DimLib
//import qouteall.dimlib.api.DimensionAPI;
//
//public final class SpiritWorldDimension {
//    public static final Identifier SPIRIT_ID = Identifier.of("mysticism", "spirit");
//
//    // /execute in mysticism:spirit
//    public static final RegistryKey<World> WORLD_KEY =
//            RegistryKey.of(RegistryKeys.WORLD, SPIRIT_ID);
//
//    // Optional: LevelStem registry key (not strictly needed here)
//    public static final RegistryKey<DimensionOptions> STEM_KEY =
//            RegistryKey.of(RegistryKeys.DIMENSION, SPIRIT_ID);
//
//    private SpiritWorldDimension() {}
//
//    public static void init() {
//        // Register during DimLib's load event (one-arg: MinecraftServer)
//        DimensionAPI.SERVER_DIMENSIONS_LOAD_EVENT.register(server -> {
//            // Lookups via the server’s dynamic registries
//            var rm = server.getRegistryManager();
//
//            RegistryEntry<DimensionType> dimType = rm
//                    .getOrThrow(RegistryKeys.DIMENSION_TYPE)
//                    .getEntry(DimensionTypes.OVERWORLD.getValue())
//                    .orElseThrow(() -> new IllegalStateException("Missing OVERWORLD dimension type"));
//
//            RegistryEntry<Biome> voidBiome = rm
//                    .getOrThrow(RegistryKeys.BIOME)
//                    .getEntry(BiomeKeys.THE_VOID.getValue())
//                    .orElseThrow(() -> new IllegalStateException("Missing THE_VOID biome"));
//
//            BiomeSource biomeSource = new FixedBiomeSource(voidBiome);
//
//            // Your void generator (adjust ctor to your implementation)
//            ChunkGenerator generator = new SpiritWorldGenerator(
//                    biomeSource,
//                    /* minY  */ -64,
//                    /* height*/ 384,
//                    /* sea   */ 0
//            );
//
//            DimensionOptions stem = new DimensionOptions(dimType, generator);
//
//            // Add if absent (DimLib will either register during world creation
//            // or add dynamically if the server is already running)
//            DimensionAPI.addDimensionIfNotExists(server, SPIRIT_ID, () -> stem);
//
//            // (Optional) suppress the yellow "experimental" banner for your namespace:
//            // DimensionAPI.suppressExperimentalWarningForNamespace("mysticism");
//        });
//
//        // Ensure a world instance exists after startup (safe no-op if already loaded)
//        ServerLifecycleEvents.SERVER_STARTED.register(SpiritWorldDimension::ensureWorldOpened);
//
//        // Disallow block interactions inside the Spirit world
//        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
//                world.getRegistryKey().equals(WORLD_KEY) ? ActionResult.FAIL : ActionResult.PASS);
//
//        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
//                world.getRegistryKey().equals(WORLD_KEY) ? ActionResult.FAIL : ActionResult.PASS);
//    }
//
//    private static void ensureWorldOpened(MinecraftServer server) {
//        // Touching it ensures creation if it was added dynamically
//        server.getWorld(WORLD_KEY);
//    }
//}
