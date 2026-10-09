package io.github.mysticism.mixin;

import io.github.mysticism.movement.SpiritMovement;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces only spirit DEEP flight travel with the consistent isotropic model.
 * Shallow walking, swimming, vehicles and every non-spirit world fall through to vanilla untouched,
 * and server players never simulate travel, so no server-side speed correction exists to fight.
 */
@Mixin(PlayerEntity.class)
public abstract class SpiritTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void mysticism$consistentFlight(Vec3d input, CallbackInfo ci) {
        if (SpiritMovement.travel((PlayerEntity) (Object) this, input)) ci.cancel();
    }
}
