package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritCrosshair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not raycast invisible prototype blocks/entities, including during entry packet gaps. */
@Mixin(GameRenderer.class)
public abstract class SpiritPickingMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "updateCrosshairTarget(F)V", at = @At("HEAD"), cancellable = true)
    private void mysticism$skipCarrierPicking(float tickDelta, CallbackInfo result) {
        if (!SpiritCrosshair.inSpiritWorld(client)) return;
        SpiritCrosshair.clearCarrierTarget(client, tickDelta);
        result.cancel();
    }
}
