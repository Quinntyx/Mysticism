package io.github.mysticism.mixin;

import io.github.mysticism.navigation.MovementProvenance;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

/** Vanilla teleport/correction path: "moved too quickly/wrongly" rejections, pending teleports and
 * command teleports all apply here. The movement integrators must never fold these into semantic
 * travel; integrating a correction as chosen movement repeats the overshoot every tick. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritTeleportCorrectionMixin {
    @Shadow public ServerPlayerEntity player;
    @Inject(method = "requestTeleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void mysticism$teleportProvenance(double x, double y, double z, float yaw, float pitch,
            Set<PositionFlag> flags, CallbackInfo ci) {
        MovementProvenance.markServerTeleport(player.getUuid());
    }
}
