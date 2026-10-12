package io.github.mysticism.client.spiritworld;

import io.github.mysticism.navigation.SpiritTransitionGate;
import io.github.mysticism.navigation.SpiritTransitionGate.Handoff;
import io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import java.util.function.BooleanSupplier;

/** Render-thread-only handoff. Vanilla still replaces the world/player and waits for chunks. */
public final class SpiritTransitionClient {
    private static final SpiritTransitionGate GATE = new SpiritTransitionGate();
    private static Handoff respawn;
    private static boolean initialized;
    private SpiritTransitionClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        SpiritTransitionScene.init(); // after the world postprocess: never capture a menu/hand/HUD
        ClientPlayConnectionEvents.JOIN.register((h, sender, c) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> clear());
    }

    public static void offer(Object handler, Handoff handoff) {
        GATE.offer(handler, handoff, System.nanoTime());
    }

    public static void beginRespawn(Object handler, MinecraftClient client, String destination) {
        respawn = null;
        if (client.world == null || client.player == null) { GATE.clear(); return; }
        respawn = GATE.consume(handler, client.player.getUuid(),
                client.world.getRegistryKey().getValue().toString(), destination,
                client.player.isAlive(), System.nanoTime()).orElse(null);
        if (respawn != null) SpiritTransitionScene.begin(client, respawn);
        else SpiritTransitionScene.cancelHold();
    }

    public static boolean respawning() { return respawn != null; }

    public static void loading(BooleanSupplier vanillaReady) {
        if (respawn != null) {
            boolean entering = respawn.entering();
            SpiritTransitionScene.loading(() -> entering
                    ? ClientSpiritCache.active() && SpiritTerrainClient.frame().isPresent()
                    : vanillaReady.getAsBoolean());
        }
    }

    public static void endRespawn(MinecraftClient client) {
        if (respawn != null && client.player != null) {
            // Respawn creates a player at (0,0,0), facing -180. Seed the SAME authoritative
            // destination before any intervening render; vanilla position/teleport ACK is untouched.
            client.player.refreshPositionAndAngles(respawn.x(), respawn.y(), respawn.z(), respawn.yaw(), respawn.pitch());
            client.player.resetPosition();
            client.player.prevYaw = respawn.yaw(); client.player.prevPitch = respawn.pitch();
            client.player.setHeadYaw(respawn.yaw()); client.player.prevHeadYaw = respawn.yaw();
            client.player.setBodyYaw(respawn.yaw()); client.player.prevBodyYaw = respawn.yaw();
            client.player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
            client.player.fallDistance = 0;
        }
        respawn = null;
    }

    private static void clear() { GATE.clear(); respawn = null; SpiritTransitionScene.clear(); }
}
