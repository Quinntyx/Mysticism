package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.MeshMovementValidation;
import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Free-flight prediction reconciliation. Deep spirit flight runs through per-player rotating
 * observer-local geometry whose authoritative mesh frame lags client prediction; vanilla's
 * "moved wrongly" snap-back treated that expected divergence as cheating and rubber-banded
 * sustained movement and direction changes. Two redirects reconcile this without abandoning
 * mesh authority:
 *
 * - The creative-gate exemption tolerates prediction divergence, so a stale-frame move is not
 *   rejected by the frame-lag noise threshold before the real check runs.
 * - The collision gate is replaced with MESH-AWARE validation: the claimed position is accepted
 *   only when the player's own recently published frame reproduces the move (lag-compensated
 *   re-simulation), and moves that cross Mysticism mesh walls under every recent frame are
 *   rejected, so vanilla teleports the mover back. Vanilla geometry is irrelevant in the
 *   air-only carrier world; Mysticism meshes are not.
 *
 * Invalid movement, pending teleports and the speed guard keep vanilla handling; shallow
 * walking keeps ordinary vanilla validation on both gates. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritFlightPredictionMixin {
    @Shadow public ServerPlayerEntity player;
    @Shadow private boolean isPlayerNotCollidingWithBlocks(WorldView world, Box box, double newX, double newY, double newZ) { throw new AssertionError(); }
    @Redirect(method = "onPlayerMove",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;isCreative()Z"))
    private boolean mysticism$flightPredictionTolerance(ServerPlayerInteractionManager manager) {
        return manager.isCreative() || SpiritNavigationService.flightPredictionTolerance(player);
    }
    @Redirect(method = "onPlayerMove",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;isPlayerNotCollidingWithBlocks(Lnet/minecraft/world/WorldView;Lnet/minecraft/util/math/Box;DDD)Z"))
    // The redirected invokevirtual consumes its handler receiver before the method arguments,
    // even though vanilla invokes this gate on the same handler this mixin is applied to.
    private boolean mysticism$meshMoveValidation(ServerPlayNetworkHandler handler, WorldView world, Box preMoveBox, double d, double e, double f) {
        if (!SpiritNavigationService.flightPredictionTolerance(player))
            return isPlayerNotCollidingWithBlocks(world, preMoveBox, d, e, f);
        // true = unsafe (vanilla teleports back); accepted only when the player's own recent
        // published mesh frame reproduces the claimed move.
        return !MeshMovementValidation.allowsMeshMove(player.getUuid(), preMoveBox, new Vec3d(d, e, f), true);
    }
}
