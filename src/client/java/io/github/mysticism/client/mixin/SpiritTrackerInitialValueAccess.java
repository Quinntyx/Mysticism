package io.github.mysticism.client.mixin;
import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(DataTracker.Entry.class)
public interface SpiritTrackerInitialValueAccess {
    @Accessor("initialValue") Object mysticism$initialValue();
}
