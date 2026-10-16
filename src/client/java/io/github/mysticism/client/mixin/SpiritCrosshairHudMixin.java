package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritCrosshair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve the normal crosshair, with non-combat feedback for projected touch. */
@Mixin(InGameHud.class)
public abstract class SpiritCrosshairHudMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "renderCrosshair", at = @At("RETURN"))
    private void mysticism$touchHint(DrawContext draw, RenderTickCounter counter, CallbackInfo result) {
        SpiritCrosshair.drawTouchHint(client, draw);
    }
}
