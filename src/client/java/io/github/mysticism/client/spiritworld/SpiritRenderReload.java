package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

/** Fabric client-resource apply lifecycle; GL cleanup completes on the render thread. */
public final class SpiritRenderReload {
    private SpiritRenderReload() {}
    public static void register(Identifier id,Runnable invalidate) {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return id; }
            @Override public void reload(ResourceManager manager) {
                RenderSystem.assertOnRenderThread();
                invalidate.run();
            }
        });
    }
}
