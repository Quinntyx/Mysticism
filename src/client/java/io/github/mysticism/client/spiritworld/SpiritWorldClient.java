package io.github.mysticism.client.spiritworld;

/** Optional consolidated parent registration; each entry point is idempotent. */
public final class SpiritWorldClient {
    private SpiritWorldClient() {}
    /** Parent input hook: consumes only an actually selected deep projected avatar. */
    public static boolean tryTouch(net.minecraft.client.MinecraftClient client, double reach) {
        return SpiritSemanticEntityRenderer.tryTouch(client,reach);
    }
    public static void init() {
        io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient.init();
        SpiritItemProjectionRenderer.init();
        SpiritSemanticEntityRenderer.init();
        SpiritSkybox.init();
        ShaderManager.init(); // Register depth snapshot AFTER model AFTER_TRANSLUCENT flush.
    }
}
