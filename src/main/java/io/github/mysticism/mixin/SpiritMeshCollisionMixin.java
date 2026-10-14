package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only spirit PLAYER world-block collision, including airborne flight; native source worlds are untouched. */
@Mixin(Entity.class)
public abstract class SpiritMeshCollisionMixin {
    @Inject(method="adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshMovement(Vec3d requested,CallbackInfoReturnable<Vec3d> cir) {
        if((Object)this instanceof PlayerEntity p && p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))
            cir.setReturnValue(MeshCollision.move(p,requested));
    }
    @Inject(method="doesNotCollide(Lnet/minecraft/util/math/Box;)Z",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshClearance(Box body,CallbackInfoReturnable<Boolean> cir) {
        if((Object)this instanceof PlayerEntity p && p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))
            cir.setReturnValue(MeshCollision.bodyClear(p,body));
    }
    @Inject(method="isInsideWall",at=@At("HEAD"),cancellable=true)
    private void mysticism$noCarrierSuffocation(CallbackInfoReturnable<Boolean> cir) {
        if((Object)this instanceof PlayerEntity p && p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))cir.setReturnValue(false);
    }
    @Inject(method="pushOutOfBlocks(DDD)V",at=@At("HEAD"),cancellable=true)
    private void mysticism$noCarrierPush(double x,double y,double z,CallbackInfo ci) {
        // SAT depenetration in MeshCollision owns separation, not the nearest carrier-air block.
        if((Object)this instanceof PlayerEntity p && p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))ci.cancel();
    }
    @Inject(method="checkBlockCollision",at=@At("HEAD"),cancellable=true)
    private void mysticism$noCarrierBlocks(CallbackInfo ci) {
        if((Object)this instanceof PlayerEntity p && p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))ci.cancel();
    }
    @Inject(method="tickInVoid",at=@At("HEAD"),cancellable=true)
    private void mysticism$semanticNotCarrierVoid(CallbackInfo ci) {
        // Carrier Y is not source-world depth. No fabricated floor; fall/deep mode belongs to navigation.
        if((Object)this instanceof PlayerEntity p && p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))ci.cancel();
    }
}
