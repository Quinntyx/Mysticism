package io.github.mysticism.client.mixin;

import io.github.mysticism.navigation.MovementProvenance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server position-look corrections applied to the local player are authoritative repositioning,
 * never the player's chosen movement. The client integrator skips the window containing one,
 * so a correction cannot be integrated, reacted to, and repeated as rubber-banding. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpiritPositionCorrectionMixin {
    @Inject(method = "onPlayerPositionLook(Lnet/minecraft/network/packet/s2c/play/PlayerPositionLookS2CPacket;)V", at = @At("TAIL"))
    private void mysticism$positionCorrection(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) MovementProvenance.markClientRepositioned(player.getUuid());
    }
}
