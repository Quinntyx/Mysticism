package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.ClientLatentPredictor;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-seed client prediction when a server-decided arrival position is actually APPLIED. The synced
 * prediction-epoch advance can be processed one client tick before the position packet, which would
 * otherwise record the new epoch against the pre-teleport pose and let a sub-band arrival integrate
 * as chosen movement (retained reconciliation jitter). PlayerPositionLook is the single client
 * application point of server teleports: re-baseline prediction here so the arrival tick never
 * integrates, in either packet processing order. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpiritArrivalReseedMixin {
    @Inject(method = "onPlayerPositionLook(Lnet/minecraft/network/packet/s2c/play/PlayerPositionLookS2CPacket;)V", at = @At("TAIL"))
    private void mysticism$reseedArrivalPrediction(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        ClientLatentPredictor.onArrivalApplied();
    }
}
