package io.github.mysticism.client.spiritworld;

/** Optional consolidated parent registration; each entry point is idempotent. */
public final class SpiritWorldClient {
    private SpiritWorldClient() {}
    public static void init() {
        ShaderManager.init();
        SpiritItemProjectionRenderer.init();
        SpiritSkybox.init();
    }
}
