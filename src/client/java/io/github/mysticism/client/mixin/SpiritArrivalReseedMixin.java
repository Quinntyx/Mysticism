package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.ClientLatentPredictor;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.navigation.TeleportReconciliation;
import net.minecraft.client.MinecraftClient;
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
 * integrates, in either packet processing order. Vanilla retains client momentum on relative axes;
 * reset that AFTER vanilla applies the packet, matching the server's spirit arrival reset without
 * changing the destination, rotation, relative flags, or teleport acknowledgement. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SpiritArrivalReseedMixin {
    @Inject(method = "onPlayerPositionLook(Lnet/minecraft/network/packet/s2c/play/PlayerPositionLookS2CPacket;)V", at = @At("TAIL"))
    private void mysticism$reseedArrivalPrediction(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null) {
            var world = client.world.getRegistryKey();
            client.player.setVelocity(TeleportReconciliation.velocityAfterArrival(world, client.player.getVelocity()));
            if (world.equals(SpiritTerrainService.WORLD)) client.player.fallDistance = 0;
        }
        ClientLatentPredictor.onArrivalApplied();
    }
}
