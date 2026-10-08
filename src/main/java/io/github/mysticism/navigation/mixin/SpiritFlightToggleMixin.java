package io.github.mysticism.navigation.mixin;

import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla double-jump/flight packet: run AFTER its server-thread guard, not on the Netty thread. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritFlightToggleMixin {
    @Shadow public ServerPlayerEntity player;
    @Inject(method = "onUpdatePlayerAbilities", at = @At("TAIL"))
    private void mysticism$flight(UpdatePlayerAbilitiesC2SPacket packet, CallbackInfo ci) {
        SpiritNavigationService.onFlightToggle(player, packet.isFlying());
    }
}
