package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the void-world movement rejection with real mesh clearance, across the WHOLE decision.
 * <p>Vanilla 1.21.1 onPlayerMove rejects a claimed position when
 * {@code (movedWrongly && isSpaceEmpty(previousBox)) || isPlayerNotCollidingWithBlocks(...)}:
 * the moved-wrongly branch short-circuits to requestTeleport WITHOUT calling
 * isPlayerNotCollidingWithBlocks (verified bytecode: ifne jumps straight to requestTeleport). The
 * spirit dimension is a void world, so isSpaceEmpty is always true there and any re-simulation of a
 * mesh-walking claim that diverges by more than 0.25 blocks — routine on swept affine terrain
 * (slopes, steps above step height, late input, frame latency) — teleports the player back before a
 * collision verdict is ever consulted. That short-circuit is the walking jitter/rubber banding.
 * <p>The redirect therefore forces the short-circuit open for spirit players (no vanilla blocks exist
 * in the carrier world, so the predicate is meaningless there) so the mesh verdict governs the whole
 * decision: accept every collision-clear endpoint, still teleport genuinely penetrating claims back
 * to the server pose, accept recovery from an already-overlapping body, and keep vanilla speed
 * ceilings, teleport state and fall bookkeeping untouched. Source worlds are fully vanilla. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritMeshMovementMixin {
    @Shadow public ServerPlayerEntity player;
    @Inject(method="isPlayerNotCollidingWithBlocks",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshVerdict(WorldView world,Box box,double newX,double newY,double newZ,CallbackInfoReturnable<Boolean> cir) {
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))
            cir.setReturnValue(MeshCollision.movementRejected(player,box,
                    player.getBoundingBox().offset(newX-player.getX(),newY-player.getY(),newZ-player.getZ())));
    }
    /** Opens the moved-wrongly short-circuit for spirit players so the mesh verdict above is always
     * consulted; vanilla delegates unchanged everywhere else. */
    @Redirect(method="onPlayerMove",at=@At(value="INVOKE",
            target="Lnet/minecraft/server/world/ServerWorld;isSpaceEmpty(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Z"))
    private boolean mysticism$spiritShortCircuit(ServerWorld world,Entity entity,Box box) {
        if(entity==player && player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))return false;
        return world.isSpaceEmpty(entity,box);
    }
}
