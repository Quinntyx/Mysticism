package io.github.mysticism.client.mixin;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client prediction must not react to invisible legacy carrier blocks. */
@Mixin(ClientPlayerEntity.class)
public abstract class SpiritCarrierPushMixin {
    @Shadow private int ticksToNextAutojump;

    @Inject(method="autoJump(FF)V",at=@At("HEAD"),cancellable=true)
    private void mysticism$noCarrierAutoJump(float dx,float dz,CallbackInfo result) {
        ClientPlayerEntity player=(ClientPlayerEntity)(Object)this;
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD)) {
            // Vanilla probes World.getCollisions and block shapes, not the observer's affine mesh.
            // Suppress that heuristic; explicit jumps and mesh movement/step collision remain intact.
            ticksToNextAutojump=0;
            result.cancel();
        }
    }

    @Inject(method="tickMovement()V",at=@At("HEAD"))
    private void mysticism$clearCarrierAutoJump(CallbackInfo result) {
        ClientPlayerEntity player=(ClientPlayerEntity)(Object)this;
        // A source-world jump may already be queued on seamless entry. Clear BEFORE vanilla turns
        // its countdown into input.jumping, even if there is no horizontal movement this tick.
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))ticksToNextAutojump=0;
    }

    @Inject(method="pushOutOfBlocks(DD)V",at=@At("HEAD"),cancellable=true)
    private void mysticism$meshOnlyPush(double x,double z,CallbackInfo result) {
        ClientPlayerEntity player=(ClientPlayerEntity)(Object)this;
        if(player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD))result.cancel();
    }
}
