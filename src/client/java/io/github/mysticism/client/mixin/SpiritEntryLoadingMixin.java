package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritEntryTransition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep the new WorldLoadingState and chunk handshake; suppress only its second loading GUI. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpiritEntryLoadingMixin {
    @Redirect(method = "startWorldLoading", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"),
            require = 1, expect = 1, allow = 1)
    private void mysticism$keepSpiritView(MinecraftClient client, Screen screen,
            ClientPlayerEntity nextPlayer, ClientWorld nextWorld, DownloadingTerrainScreen.WorldEntryReason reason) {
        // During onPlayerRespawn, client.world is new but client.player is STILL the source player.
        // On initial join it is already the destination player, which deliberately fails this guard.
        boolean seamless = screen instanceof DownloadingTerrainScreen
                && client.world == nextWorld && client.player != null && client.player != nextPlayer
                && SpiritEntryTransition.hideLoadingScreen(client.player.getWorld().getRegistryKey().getValue().toString(),
                        nextWorld.getRegistryKey().getValue().toString(), client.player.isAlive());
        if (!seamless) client.setScreen(screen);
        // No ready-state override and no cancellation of onPlayerRespawn/onPlayerPositionLook.
        // The normal world render now reaches SpiritEntryScene.hold and the mesh-gated shader fade.
    }
}
