package io.github.mysticism.mixin;

import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Free-flight prediction acceptance: the vanilla "moved wrongly" rubber-band snap-back treats
 * expected deep-flight prediction divergence (per-player rotating projected geometry, lagging
 * mesh frames) as cheating and teleports the player back on sustained movement and direction
 * changes. Vanilla itself exempts creative flight from that check; deep spirit flight receives
 * the same tolerance. Invalid/teleporting/speed-limited movement keeps vanilla handling. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritFlightPredictionMixin {
    @Shadow public ServerPlayerEntity player;
    @Redirect(method = "onPlayerMove",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;isCreative()Z"))
    private boolean mysticism$acceptFlightPrediction(ServerPlayerInteractionManager manager) {
        return manager.isCreative() || SpiritNavigationService.flightPredictionTolerance(player);
    }
}
