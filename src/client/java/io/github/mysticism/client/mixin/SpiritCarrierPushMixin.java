package io.github.mysticism.client.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client prediction must not kick the player out of invisible legacy carrier blocks. */
@Mixin(ClientPlayerEntity.class)
public abstract class SpiritCarrierPushMixin {
    @Inject(method="pushOutOfBlocks(DD)V",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshOnlyPush(double x,double z,CallbackInfo result) {
        ClientPlayerEntity player=(ClientPlayerEntity)(Object)this;
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))result.cancel();
    }
}
