package io.github.mysticism.mixin;

import io.github.mysticism.navigation.TeleportReconciliation;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

/** Reconcile spirit-world teleport arrivals. Every player teleport funnels through
 * ServerPlayNetworkHandler.requestTeleport(DDDFF, Set): vanilla /tp (same dimension), cross-dimension
 * ServerPlayerEntity#teleport/teleportTo, /spreadplayers, /spectate and mod commands. Vanilla applies
 * the authoritative arrival position inside this method; these hooks reset the stale prediction and
 * movement state around it (see TeleportReconciliation). */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritTeleportMixin {
    @Shadow public ServerPlayerEntity player;

    /** Before the arrival position packet is published: advance the synced prediction epoch so the
     * client predictor discards its stale pre-teleport pose deterministically (packet order). */
    @Inject(method = "requestTeleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void mysticism$beforeSpiritTeleport(double x, double y, double z, float yaw, float pitch,
            Set<PositionFlag> flags, CallbackInfo ci) {
        TeleportReconciliation.beforeSpiritTeleport(player);
    }

    /** After the arrival position was applied server-side: zero residual momentum, drop stale fall
     * distance, and re-seed the evolver's last-integrated pose with the arrival pose. */
    @Inject(method = "requestTeleport(DDDFFLjava/util/Set;)V", at = @At("TAIL"))
    private void mysticism$afterSpiritTeleport(double x, double y, double z, float yaw, float pitch,
            Set<PositionFlag> flags, CallbackInfo ci) {
        TeleportReconciliation.afterSpiritTeleport(player);
    }
}
