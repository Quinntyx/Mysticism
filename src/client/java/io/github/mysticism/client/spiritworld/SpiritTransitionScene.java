package io.github.mysticism.client.spiritworld;

import io.github.mysticism.navigation.SpiritTransitionBridge;
import io.github.mysticism.navigation.SpiritTransitionGate.Handoff;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A bounded outgoing WORLD-color bridge, never source geometry or a substitute collision floor.
 * Exit uses the current shallow scene, NOT the old entry photograph at a different source pose. */
final class SpiritTransitionScene {
    private static SimpleFramebuffer image;
    private static Object handler;
    private static UUID player;
    private static String dimension;
    private static long captured;
    private static final SpiritTransitionBridge BRIDGE = new SpiritTransitionBridge();
    private static BooleanSupplier ready = () -> false;
    private static boolean failed;
    private SpiritTransitionScene() {}

    static void init() {
        WorldRenderEvents.END.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (failed || client.world == null || client.player == null) return;
            try {
                if (BRIDGE.hold(client.getNetworkHandler(), client.player.getUuid(),
                        client.world.getRegistryKey().getValue().toString(), SpiritTransitionClient.respawning(),
                        ready.getAsBoolean(), System.nanoTime())) {
                    blit(client, false);
                    return;
                }
                ready = () -> false;
                capture(client);
            } catch (RuntimeException error) {
                clear(); failed = true;
                LoggerFactory.getLogger("Mysticism-Client").error("Spirit world-color handoff failed; using live world rendering", error);
            }
        });
        SpiritRenderReload.register(Identifier.of("mysticism", "spirit_transition_scene"), SpiritTransitionScene::clear);
    }

    static void begin(MinecraftClient client, Handoff handoff) {
        cancelHold();
        if (image != null && handler == client.getNetworkHandler())
            BRIDGE.begin(handler, player, dimension, captured, handoff, System.nanoTime());
    }

    static void loading(BooleanSupplier loaded) { ready = loaded; }
    static void cancelHold() { BRIDGE.cancel(); ready = () -> false; }

    private static void capture(MinecraftClient client) {
        var main = client.getFramebuffer();
        if (main.textureWidth <= 0 || main.textureHeight <= 0) return;
        if (image == null) image = new SimpleFramebuffer(main.textureWidth, main.textureHeight, false, MinecraftClient.IS_SYSTEM_MAC);
        else if (image.textureWidth != main.textureWidth || image.textureHeight != main.textureHeight)
            image.resize(main.textureWidth, main.textureHeight, MinecraftClient.IS_SYSTEM_MAC);
        blit(client, true);
        handler = client.getNetworkHandler(); player = client.player.getUuid();
        dimension = client.world.getRegistryKey().getValue().toString(); captured = System.nanoTime();
    }

    private static void blit(MinecraftClient client, boolean capture) {
        var main = client.getFramebuffer();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, capture ? main.fbo : image.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, capture ? image.fbo : main.fbo);
            GL30.glBlitFramebuffer(0, 0, capture ? main.textureWidth : image.textureWidth,
                    capture ? main.textureHeight : image.textureHeight, 0, 0,
                    capture ? image.textureWidth : main.textureWidth, capture ? image.textureHeight : main.textureHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally { main.beginWrite(false); }
    }

    static void clear() {
        cancelHold();
        if (image != null) image.delete();
        image = null; handler = null; player = null; dimension = null; captured = 0; failed = false;
    }
}
