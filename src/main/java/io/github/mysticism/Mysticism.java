package io.github.mysticism;

import io.github.mysticism.command.EmbeddingCommand;
import io.github.mysticism.command.HorizonSeederCommand;
import io.github.mysticism.command.LatentCommands;
//import io.github.mysticism.dimension.SpiritWorldDimension;
import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import io.github.mysticism.dimension.spiritworld.SpiritVisibilityService;
import io.github.mysticism.dimension.spiritworld.SpiritWorldGenerator;
import io.github.mysticism.embedding.EmbeddingHelper;
import io.github.mysticism.net.SpiritNetworking;
import io.github.mysticism.world.region.HorizonSeeder;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mysticism implements ModInitializer, DedicatedServerModInitializer {
    public static final String MOD_ID = "mysticism";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static String id(String sub) {
        return MOD_ID + ':' + sub;
    }

    @Override
    public void onInitialize() {
        // Common initialization code here
        LOGGER.info("Mysticism mod initializing...");

        // Register commands
        CommandRegistrationCallback.EVENT.register((
                dispatcher,
                registryAccess,
                environment) -> {
            EmbeddingCommand.register(dispatcher);
            io.github.mysticism.command.SpiritCommand.register(dispatcher);
            HorizonSeederCommand.register(dispatcher);
            LatentCommands.register(dispatcher, registryAccess, environment);
        });


        SpiritNetworking.init();
        io.github.mysticism.net.SpiritProjectionService.init();
        io.github.mysticism.landmark.extract.LandmarkExtractionService.init();
        io.github.mysticism.activity.SpiritActivityService.init();
        io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService.init();
        SpiritVisibilityService.init();
        SpiritBasisEvolver.init();

        Registry.register(Registries.CHUNK_GENERATOR, Identifier.of("mysticism","spirit_generator"),
                SpiritWorldGenerator.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EmbeddingHelper.shutdown());
        ServerWorldEvents.LOAD.register(this::onWorldLoad);
    }

    @Override
    public void onInitializeServer() {
        LOGGER.info("Mysticism dedicated server initializing...");
    }

    private void onServerStarted(MinecraftServer server) {
        LOGGER.info("Server started - initializing EmbeddingHelper without toast");
        EmbeddingHelper.initializeServer();
        ItemEmbeddingIndexState itemIndex = ItemEmbeddingIndexState.get(server);
        var spatialIndex = io.github.mysticism.world.state.SpatialEmbeddingIndexState.get(server);
        // Loading state never performs inference; no startup joins or model downloads.
        EmbeddingHelper.readiness().whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Embeddings unavailable; server remains usable. {}", error.toString());
                return;
            }
        });
        // Validate canonical registry/tag generations even when HTTP readiness fails.
        // Generation builders compose readiness asynchronously; the server never joins.
        itemIndex.populateAsync(server);
        spatialIndex.rebuildAsync(server);


        // Start the horizon seeder
        LOGGER.info("Starting Horizon Seeder...");
        HorizonSeeder.register();
    }

    private void onWorldLoad(MinecraftServer server, ServerWorld world) {
//        SpatialEmbeddingIndexState.get(server);
//        ItemEmbeddingIndexState.get(server); // sanity check; it's basically free anyway // correction: not free
    }
}