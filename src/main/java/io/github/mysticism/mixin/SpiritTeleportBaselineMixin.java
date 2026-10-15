package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

/**
 * Every authoritative pose correction or teleport (mod entry/exit/landing, /tp, vanilla movement
 * corrections, dimension changes) must re-baseline the movement delta integration. requestTeleport
 * snaps the carrier to a pose that is not chosen movement; without the re-baseline the next evolver
 * tick would integrate the teleport offset — or a stale pre-correction pose delta — into the
 * semantic pose. While the teleport is pending the client's movement packets are dropped, so the
 * reset baseline also keeps that window from contributing any delta.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritTeleportBaselineMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "requestTeleport(DDDFFLjava/util/Set;)V", at = @At("TAIL"))
    private void mysticism$rebaseline(double x, double y, double z, float yaw, float pitch,
                                      Set<PositionFlag> flags, CallbackInfo ci) {
        if (player != null) SpiritBasisEvolver.resetMotion(player);
    }
}
