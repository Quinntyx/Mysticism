package io.github.mysticism.mixin;

import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.util.math.Vec3d;
import java.util.Set;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla flight toggles and accepted movement: always run AFTER the server-thread guard. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritFlightToggleMixin {
    @Shadow public ServerPlayerEntity player;
    @Unique private Vec3d mysticism$beforeMove;

    // HEAD runs on Netty too. Snapshot only after forceMainThread has returned on the server thread.
    @Inject(method = "onPlayerMove", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void mysticism$beforeMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        mysticism$beforeMove = player.getPos();
    }

    // Vanilla's rejected/pending-teleport/vehicle paths return early. TAIL is only the final return,
    // after the accepted position is committed; never measure a requested or intermediate mesh pose.
    @Inject(method = "onPlayerMove", at = @At("TAIL"))
    private void mysticism$acceptedMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        SpiritNavigationService.recordAcceptedMovement(player, mysticism$beforeMove);
    }

    @Inject(method = "requestTeleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void mysticism$teleport(double x, double y, double z, float yaw, float pitch,
                                    Set<PositionFlag> flags, CallbackInfo ci) {
        SpiritNavigationService.resetHandoffMotion(player);
    }

    @Inject(method = "onUpdatePlayerAbilities", at = @At("TAIL"))
    private void mysticism$flight(UpdatePlayerAbilitiesC2SPacket packet, CallbackInfo ci) {
        SpiritNavigationService.onFlightToggle(player, packet.isFlying());
    }
}
