package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.ClientPoseSync;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An authoritative position-look packet is a pose correction (server teleport, vanilla movement
 * correction, dimension change), never chosen movement. The movement-ordering guard must treat it
 * as such on both sides: the server re-baselines its integration at requestTeleport, and this marks
 * the client so the next predictor tick skips the correction delta instead of integrating a stale
 * pose rollback into the semantic pose.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpiritPositionLookMixin {
    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void mysticism$poseCorrection(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        ClientPoseSync.markTeleport();
    }
}
