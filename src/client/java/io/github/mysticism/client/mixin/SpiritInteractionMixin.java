package io.github.mysticism.client.mixin;

import io.github.mysticism.client.spiritworld.SpiritWorldClient;
import io.github.mysticism.component.MysticismEntityComponents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Route touches through the projected scene, never vanilla carrier-space combat. */
@Mixin(MinecraftClient.class)
public abstract class SpiritInteractionMixin {
    private static boolean spirit(MinecraftClient client) {
        return client.player != null && client.world != null
                && client.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit"));
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void mysticism$projectedAttack(CallbackInfoReturnable<Boolean> result) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!spirit(client)) return;
        if (MysticismEntityComponents.SPIRIT_NAVIGATION.get(client.player).deep()
                && SpiritWorldClient.tryTouch(client, 4.0)) client.player.swingHand(Hand.MAIN_HAND);
        // Ghosts and carrier-space entities must never receive an ordinary attack.
        result.setReturnValue(false);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void mysticism$projectedUse(CallbackInfo result) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!spirit(client)) return;
        if (MysticismEntityComponents.SPIRIT_NAVIGATION.get(client.player).deep()
                && SpiritWorldClient.tryTouch(client, 4.0)) {
            client.player.swingHand(Hand.MAIN_HAND);
            result.cancel();
        } else if (client.crosshairTarget != null
                && client.crosshairTarget.getType() != HitResult.Type.MISS) {
            // Permit held-item use (including magnets) on empty space, not world interactions.
            result.cancel();
        }
    }
}
