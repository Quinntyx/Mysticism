package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.navigation.SpiritMovementAcceptance;
import io.github.mysticism.navigation.SpiritFlightReplayAccess;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve the validated mesh replay (or Entity.move's sweep for ordinary walking) at final acceptance.
 * Vanilla speed/teleport/thread/invalid-packet guards and rejection paths remain intact.
 * This does not intercept command teleports; it only handles onPlayerMove's pose writes. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritMeshMovementAcceptanceMixin {
    @Shadow public abstract void requestTeleport(double x, double y, double z, float yaw, float pitch);

    @Redirect(method = "onPlayerMove", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerEntity;updatePositionAndAngles(DDDFF)V", ordinal = 1))
    private void mysticism$acceptSweptPose(ServerPlayerEntity player, double x, double y, double z, float yaw, float pitch) {
        if (!player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD)
                || player.hasVehicle() || player.noClip || player.isSleeping()) {
            player.updatePositionAndAngles(x, y, z, yaw, pitch);
            return;
        }
        Vec3d requested=new Vec3d(x,y,z);
        var replay=((SpiritFlightReplayAccess)this).mysticism$takeFlightMeshDecision(requested);
        var accepted = SpiritMovementAcceptance.accept(requested, player.getPos(), replay);
        Vec3d at = accepted.position();
        player.updatePositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        // Only genuine replay clipping needs a correction. A different provisional current-frame
        // sweep must not reset a prediction already reproduced by a recent published frame.
        if (accepted.correctionRequired()) requestTeleport(at.x, at.y, at.z, yaw, pitch);
    }
}
