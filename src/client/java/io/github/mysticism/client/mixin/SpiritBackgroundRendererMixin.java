package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.ShaderManager;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Parent must add this name to mysticism.client.mixins.json's client list. */
@Mixin(BackgroundRenderer.class)
public abstract class SpiritBackgroundRendererMixin {
    @Inject(method = "applyFog", at = @At("RETURN"))
    private static void mysticism$spiritFog(Camera camera, BackgroundRenderer.FogType type,
            float viewDistance, boolean thickFog, float tickDelta, CallbackInfo ci) {
        ShaderManager.applySpiritFog();
    }

    @Inject(method = "applyFogColor", at = @At("RETURN"))
    private static void mysticism$spiritFogColor(CallbackInfo ci) {
        ShaderManager.applySpiritFogColor();
    }
}
