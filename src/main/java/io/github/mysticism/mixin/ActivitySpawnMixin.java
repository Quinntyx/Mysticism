package io.github.mysticism.mixin;

import io.github.mysticism.activity.SpiritActivityService;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public abstract class ActivitySpawnMixin {
    @Inject(method="spawnEntity",at=@At("RETURN"))
    private void mysticism$spawned(Entity entity,CallbackInfoReturnable<Boolean> cir){
        if(cir.getReturnValue())SpiritActivityService.spawned((ServerWorld)(Object)this,entity);
    }
}
