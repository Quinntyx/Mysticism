package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritTransitionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.WorldLoadingState;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpiritRespawnHandoffMixin {
    @Shadow private WorldLoadingState worldLoadingState;

    // AFTER forceMainThread, before joinWorld clears the outgoing player/world. Never at HEAD.
    @Inject(method = "onPlayerRespawn", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/packet/s2c/play/PlayerRespawnS2CPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/packet/s2c/play/CommonPlayerSpawnInfo;"))
    private void mysticism$begin(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        SpiritTransitionClient.beginRespawn(this, MinecraftClient.getInstance(),
                packet.commonPlayerSpawnInfo().dimension().getValue().toString());
    }

    @ModifyArg(method = "startWorldLoading", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"), index = 0)
    private Screen mysticism$omitTerrainScreen(Screen vanilla) {
        return SpiritTransitionClient.respawning() ? null : vanilla;
    }

    @Inject(method = "startWorldLoading", at = @At("TAIL"))
    private void mysticism$retainLoadingState(CallbackInfo ci) {
        WorldLoadingState loading = worldLoadingState;
        SpiritTransitionClient.loading(loading::isReady);
    }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void mysticism$restorePose(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        SpiritTransitionClient.endRespawn(MinecraftClient.getInstance());
    }
}
