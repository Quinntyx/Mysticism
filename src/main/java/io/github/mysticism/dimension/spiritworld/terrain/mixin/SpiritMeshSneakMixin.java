package io.github.mysticism.dimension.spiritworld.terrain.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carrier AIR is not an unsupported ledge; sneak clipping tests the projected mesh. */
@Mixin(PlayerEntity.class)
public abstract class SpiritMeshSneakMixin {
    @Inject(method="adjustMovementForSneaking",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshLedge(Vec3d requested,MovementType type,CallbackInfoReturnable<Vec3d> cir) {
        PlayerEntity p=(PlayerEntity)(Object)this;
        if(p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))cir.setReturnValue(MeshCollision.sneak(p,requested,type));
    }
}
