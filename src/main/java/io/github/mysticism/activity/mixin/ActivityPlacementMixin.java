package io.github.mysticism.activity.mixin;

import io.github.mysticism.activity.SpiritActivityService;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The protected helper receives vanilla's ADJUSTED placement context (including scaffolding). */
@Mixin(BlockItem.class)
public abstract class ActivityPlacementMixin {
    @Inject(method="place(Lnet/minecraft/item/ItemPlacementContext;Lnet/minecraft/block/BlockState;)Z", at=@At("RETURN"))
    private void mysticism$placed(ItemPlacementContext context, BlockState placed, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && context.getPlayer() instanceof ServerPlayerEntity player) {
            var actual = context.getWorld().getBlockState(context.getBlockPos());
            if (actual.isOf(((BlockItem)(Object)this).getBlock()))
                SpiritActivityService.placed(player, context.getBlockPos(), actual);
        }
    }
}
