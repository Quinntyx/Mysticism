package io.github.mysticism.client.spiritworld;

/** Compatibility entry point retained for the parent-owned client initializer. */
public final class SpiritWorldRenderer {
    private SpiritWorldRenderer() {}
    public static void init() { SpiritItemProjectionRenderer.init(); }
}
