package io.github.mysticism.mixin;

import io.github.mysticism.activity.SpiritActivityService;
import io.github.mysticism.activity.ActivityMath;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe actual Stats-screen values, including vanilla saturation, not requested increments. */
@Mixin(ServerPlayerEntity.class)
public abstract class ActivityStatMixin {
    @Unique private int mysticism$statBefore;
    @Inject(method="increaseStat",at=@At("HEAD"))
    private void mysticism$before(Stat<?> stat,int amount,CallbackInfo ci){mysticism$statBefore=((ServerPlayerEntity)(Object)this).getStatHandler().getStat(stat);}
    @Inject(method="increaseStat",at=@At("RETURN"))
    private void mysticism$after(Stat<?> stat,int amount,CallbackInfo ci){
        ServerPlayerEntity player=(ServerPlayerEntity)(Object)this;
        int delta=ActivityMath.delta(mysticism$statBefore,player.getStatHandler().getStat(stat));
        SpiritActivityService.statDelta(player,stat,delta);
    }
}
