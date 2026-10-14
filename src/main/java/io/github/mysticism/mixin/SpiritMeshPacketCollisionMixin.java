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

/** Keep vanilla packet validation, but never validate observer terrain against hidden carrier voxels. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpiritMeshPacketCollisionMixin {
    @Shadow public ServerPlayerEntity player;

    @Redirect(method="onPlayerMove",at=@At(value="INVOKE",target="Lnet/minecraft/server/world/ServerWorld;isSpaceEmpty(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Z"))
    private boolean mysticism$meshPacketClearance(ServerWorld world,Entity entity,Box body) {
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))return MeshCollision.bodyClear(player,body);
        return world.isSpaceEmpty(entity,body);
    }

    @Inject(method="isPlayerNotCollidingWithBlocks",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshPacketIntersection(WorldView world,Box before,double x,double y,double z,CallbackInfoReturnable<Boolean> result) {
        if(!player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))return;
        Box after=player.getBoundingBox().offset(x-player.getX(),y-player.getY(),z-player.getZ());
        // Despite its name the vanilla method returns true when a NEW collision is found.
        result.setReturnValue(MeshCollision.newlyColliding(player,before,after));
    }
}
