package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the void-world movement verdict with real mesh clearance.
 * <p>Vanilla's isPlayerNotCollidingWithBlocks inspects only carrier block collisions; the spirit
 * dimension generates none, so it is constant false there and the "moved wrongly" check teleports the
 * player back to the server's re-simulated position whenever that re-simulation of a mesh-walking claim
 * diverges by more than 0.25 blocks — routine on swept affine terrain (slopes, steps, late input, frame
 * latency). That requestTeleport is the walking jitter/rubber banding. The mesh verdict accepts every
 * collision-clear endpoint, still corrects genuinely penetrating claims back to the server pose, and
 * keeps the vanilla speed ceilings untouched. Source worlds are untouched. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritMeshMovementMixin {
    @Shadow public ServerPlayerEntity player;
    @Inject(method="isPlayerNotCollidingWithBlocks",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshVerdict(WorldView world,Box box,double newX,double newY,double newZ,CallbackInfoReturnable<Boolean> cir) {
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))
            cir.setReturnValue(MeshCollision.movementRejected(player,box,
                    player.getBoundingBox().offset(newX-player.getX(),newY-player.getY(),newZ-player.getZ())));
    }
}
