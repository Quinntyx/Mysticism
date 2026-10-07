package io.github.mysticism.client.spiritworld;

/** Compatibility entry point. CPU fog boxes are retired: never integrate fog a second time. */
public final class SpiritFogVoxels {
    private SpiritFogVoxels() {}
    public static void init() { ShaderManager.init(); }
}
