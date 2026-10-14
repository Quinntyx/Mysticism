package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carrier AIR is not an unsupported ledge; sneak clipping tests the projected mesh. */
@Mixin(PlayerEntity.class)
public abstract class SpiritMeshSneakMixin {
    @Redirect(method="canChangeIntoPose",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;isSpaceEmpty(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Z"))
    private boolean mysticism$meshPoseClearance(World world,Entity entity,Box body) {
        PlayerEntity p=(PlayerEntity)(Object)this;
        return p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD)
                ? MeshCollision.bodyClear(p,body) : world.isSpaceEmpty(entity,body);
    }
    @Inject(method="adjustMovementForSneaking",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshLedge(Vec3d requested,MovementType type,CallbackInfoReturnable<Vec3d> cir) {
        PlayerEntity p=(PlayerEntity)(Object)this;
        if(p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))cir.setReturnValue(MeshCollision.sneak(p,requested,type));
    }
}
