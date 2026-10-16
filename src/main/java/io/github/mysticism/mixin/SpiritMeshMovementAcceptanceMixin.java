package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.navigation.SpiritMovementAcceptance;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve Entity.move's mesh sweep at vanilla's final movement-packet acceptance point.
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
        var accepted = SpiritMovementAcceptance.accept(new Vec3d(x, y, z), player.getPos());
        Vec3d at = accepted.position();
        player.updatePositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        // Frame delay/desynchronization can change the sweep on the server. Correct to the
        // accepted surface, not the pre-move position; ordinary matching prediction sends nothing.
        if (accepted.correctionRequired()) requestTeleport(at.x, at.y, at.z, yaw, pitch);
    }
}
