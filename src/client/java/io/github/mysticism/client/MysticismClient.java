package io.github.mysticism.client;

import io.github.mysticism.client.net.SpiritNetworkingClient;
import io.github.mysticism.client.spiritworld.ClientLatentPredictor;
import io.github.mysticism.client.spiritworld.ClientSpiritCache;
import io.github.mysticism.client.spiritworld.SpiritWorldPostEffectProcessor;
import io.github.mysticism.client.spiritworld.SpiritWorldRenderer;
import io.github.mysticism.embedding.EmbeddingHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MysticismClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-Client");

    // Flag to track if we've initialized on client side (for singleplayer)
    private static boolean clientInitialized = false;

    private static final SpiritWorldPostEffectProcessor POST = new SpiritWorldPostEffectProcessor();

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

        SpiritNetworkingClient.init();
        ClientLatentPredictor.init();
        SpiritWorldRenderer.init();

        WorldRenderEvents.END.register(ctx -> POST.onWorldRenderEnd());

        // Clean up on client shutdown
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> POST.close());
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