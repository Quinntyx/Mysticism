package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritEntryTransition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve joinWorld's reset/forced source render and world replacement, without its loading GUI. */
@Mixin(MinecraftClient.class)
public abstract class SpiritEntryWorldMixin {
    @Shadow private void reset(Screen screen) { throw new AssertionError("Mixin shadow"); }

    @Redirect(method = "joinWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;reset(Lnet/minecraft/client/gui/screen/Screen;)V"),
            require = 1, expect = 1, allow = 1)
    private void mysticism$keepSourceView(MinecraftClient client, Screen screen,
            ClientWorld nextWorld, DownloadingTerrainScreen.WorldEntryReason reason) {
        boolean seamless = screen instanceof DownloadingTerrainScreen
                && client.world != null && client.player != null
                && client.player.getWorld() == client.world
                && SpiritEntryTransition.hideLoadingScreen(client.world.getRegistryKey().getValue().toString(),
                        nextWorld.getRegistryKey().getValue().toString(), client.player.isAlive());
        // reset also stops old sounds, clears the camera and performs a forced render. Never cancel it.
        reset(seamless ? null : screen);
    }
}
