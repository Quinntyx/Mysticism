package io.github.mysticism.client;

import io.github.mysticism.client.net.SpiritNetworkingClient;
import io.github.mysticism.client.spiritworld.*;
import io.github.mysticism.client.util.Color;
import io.github.mysticism.embedding.EmbeddingHelper;
import io.github.mysticism.movement.SpiritMovement;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MysticismClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-Client");

    // Flag to track if we've initialized on client side (for singleplayer)
    private static boolean clientInitialized = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Mysticism client initializing...");

        // Handle integrated server startup (singleplayer)
        ServerLifecycleEvents.SERVER_STARTED.register(this::onIntegratedServerStarted);

        // Reset flag when client shuts down so it reinitializes on restart
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("Client stopping - resetting initialization flag");
            clientInitialized = false;
        });

        io.github.mysticism.client.gui.guidebook.GuidebookClient.init();
        SpiritNetworkingClient.init();
        io.github.mysticism.client.spiritworld.SpiritWorldClient.init();
        ClientLatentPredictor.init();
        SpiritFogVoxels.init();
        installSpiritFlightInput();

        SpiritSkybox.setMode(SpiritSkybox.Mode.FLAT);
    }

    /**
     * Spirit deep flight reads the same key state vanilla flight used, under the same camera condition,
     * so the consistent model in SpiritMovement strips and replaces vanilla's vertical impulse exactly.
     */
    private void installSpiritFlightInput() {
        SpiritMovement.installVerticalInput(player -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!(player instanceof ClientPlayerEntity spirit) || client.getCameraEntity() != spirit) return 0;
            return (spirit.input.jumping ? 1 : 0) - (spirit.input.sneaking ? 1 : 0);
        });
    }

    private void onIntegratedServerStarted(MinecraftServer server) {
        // Only handle integrated servers (singleplayer), not dedicated servers
        if (!server.isDedicated() && !clientInitialized) {
            LOGGER.info("Integrated server started - initializing EmbeddingHelper with toast");
            EmbeddingHelper.initializeServer();
            clientInitialized = true;
        }
    }
}