package io.github.mysticism.landmark.extract;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Parent must register this optional common mixin; periodic loaded-chunk retries remain active. */
@Mixin(World.class)
public abstract class SourceBlockUpdateMixin {
    @Inject(method="setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",at=@At("RETURN"))
    private void mysticism$sourceEdited(BlockPos pos,BlockState state,int flags,int maxUpdateDepth,CallbackInfoReturnable<Boolean> cir){
        if(cir.getReturnValueZ() && (Object)this instanceof ServerWorld world)LandmarkExtractionService.changed(world,pos);
    }
}
