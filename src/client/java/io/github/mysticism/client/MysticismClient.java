package io.github.mysticism.client;

import io.github.mysticism.client.net.SpiritNetworkingClient;
import io.github.mysticism.client.spiritworld.*;
import io.github.mysticism.client.util.Color;
import io.github.mysticism.embedding.EmbeddingHelper;
import io.github.mysticism.movement.SpiritMovement;
import io.github.mysticism.embedding.EmbeddingFailures;
import io.github.mysticism.embedding.EmbeddingReadinessFeedback;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
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

    private static final EmbeddingReadinessFeedback<MinecraftServer> EMBEDDING_FEEDBACK = new EmbeddingReadinessFeedback<>();
    private static final SystemToast.Type EMBEDDING_TOAST = new SystemToast.Type(10000);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Mysticism client initializing...");

        // Handle integrated server startup (singleplayer)
        ServerLifecycleEvents.SERVER_STARTED.register(this::onIntegratedServerStarted);

        ServerLifecycleEvents.SERVER_STOPPING.register(EMBEDDING_FEEDBACK::stop);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> EMBEDDING_FEEDBACK.clear());

        io.github.mysticism.client.gui.guidebook.GuidebookClient.init();
        SpiritNetworkingClient.init();
        io.github.mysticism.client.spiritworld.SpiritWorldClient.init();
        SpiritTransitionClient.init();
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
        if (server.isDedicated()) return;
        // Common startup owns the provider and registers before this client entrypoint.
        // Observe only: a second initialization here must not replace the server-owned lifecycle.
        // No readiness join, model IO or inference is performed by this client event.
        MinecraftClient client = MinecraftClient.getInstance();
        EMBEDDING_FEEDBACK.watch(server, EmbeddingHelper.readiness(), client, error -> {
            // Runs only on the client executor, and only for the still-current integrated world.
            if (client.getServer() != server || server.isStopping()) return;
            Text title = Text.literal(error == null ? "Mysticism embeddings ready" : "Mysticism embeddings unavailable");
            Text detail = Text.literal(error == null
                    ? EmbeddingHelper.profile().dimensions() + " dimensions; source discovery can resume."
                    : EmbeddingFailures.describe(error) + ". See /myst embed status.");
            client.getToastManager().add(SystemToast.create(client, EMBEDDING_TOAST, title, detail));
        });
    }
}