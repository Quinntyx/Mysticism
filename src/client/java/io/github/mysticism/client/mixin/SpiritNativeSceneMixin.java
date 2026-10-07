package io.github.mysticism.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the observer mesh/semantic replicas may draw the spirit scene. */
@Mixin(WorldRenderer.class)
public abstract class SpiritNativeSceneMixin {
    private static boolean spirit() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null && client.world.getRegistryKey().getValue()
                .equals(Identifier.of("mysticism", "spirit"));
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    private void mysticism$hideCarrierChunks(CallbackInfo result) {
        // Includes any old prototype blocks; do not mutate source or existing saves to hide them.
        if (spirit()) result.cancel();
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void mysticism$hideCarrierEntities(Entity entity, double x, double y, double z,
            float tickDelta, MatrixStack matrices, VertexConsumerProvider vertices, CallbackInfo result) {
        if (spirit() && entity != MinecraftClient.getInstance().player) result.cancel();
        // Custom replicas render directly through EntityRenderDispatcher, bypassing this loop.
    }
}
