package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritTransitionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MinecraftClient.class)
public abstract class SpiritJoinWorldHandoffMixin {
    // joinWorld has a SECOND loading screen via reset. Preserve the reset/world setup itself.
    @ModifyArg(method = "joinWorld", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/MinecraftClient;reset(Lnet/minecraft/client/gui/screen/Screen;)V"), index = 0)
    private Screen mysticism$omitResetScreen(Screen vanilla) {
        return SpiritTransitionClient.respawning() ? null : vanilla;
    }
}
