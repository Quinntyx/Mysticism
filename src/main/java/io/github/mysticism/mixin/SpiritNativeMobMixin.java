package io.github.mysticism.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** V1 native allowlist: players and vanilla ItemEntity only. Source worlds/ghost presentation are untouched. */
@Mixin(ServerWorld.class)
public abstract class SpiritNativeMobMixin {
    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void mysticism$noNativeMobs(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (((ServerWorld)(Object)this).getRegistryKey().equals(SpiritTerrainService.WORLD)
                && !(entity instanceof PlayerEntity) && !(entity instanceof ItemEntity)) cir.setReturnValue(false);
    }
}
