package io.github.mysticism.activity.mixin;

import io.github.mysticism.activity.SpiritActivityService;
import net.minecraft.item.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RETURN distinguishes failed/cancelled placement; inspect the resulting real source block. */
@Mixin(BlockItem.class)
public abstract class ActivityPlacementMixin {
    @Inject(method="place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",at=@At("RETURN"))
    private void mysticism$placed(ItemPlacementContext context,CallbackInfoReturnable<ActionResult> cir){
        if(cir.getReturnValue().isAccepted()&&context.getPlayer() instanceof ServerPlayerEntity player){
            var actual=context.getWorld().getBlockState(context.getBlockPos());
            if(actual.isOf(((BlockItem)(Object)this).getBlock()))SpiritActivityService.placed(player,context.getBlockPos(),actual);
        }
    }
}
