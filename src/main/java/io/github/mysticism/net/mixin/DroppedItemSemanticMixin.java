package io.github.mysticism.net.mixin;

import io.github.mysticism.net.DroppedItemSemanticService;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla pickup delays/ownership/inventory remain authoritative; only projected reach is added. */
@Mixin(ItemEntity.class)
public abstract class DroppedItemSemanticMixin {
    @Inject(method="tick",at=@At("HEAD"))private void mysticism$prepare(CallbackInfo ci){DroppedItemSemanticService.prepare((ItemEntity)(Object)this);}
    @Inject(method="tick",at=@At("TAIL"))private void mysticism$semanticTick(CallbackInfo ci){DroppedItemSemanticService.tick((ItemEntity)(Object)this);}
    @Inject(method="onPlayerCollision",at=@At("HEAD"),cancellable=true)private void mysticism$projectedPickup(PlayerEntity player,CallbackInfo ci){if(!DroppedItemSemanticService.mayPickup((ItemEntity)(Object)this,player))ci.cancel();}
    @Inject(method="writeCustomDataToNbt",at=@At("TAIL"))private void mysticism$write(NbtCompound nbt,CallbackInfo ci){DroppedItemSemanticService.write((ItemEntity)(Object)this,nbt);}
    @Inject(method="readCustomDataFromNbt",at=@At("TAIL"))private void mysticism$read(NbtCompound nbt,CallbackInfo ci){DroppedItemSemanticService.read((ItemEntity)(Object)this,nbt);}
}
