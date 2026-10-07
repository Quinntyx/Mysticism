package io.github.mysticism.client.spiritworld.mixin;
import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(DataTracker.class)
public interface SpiritTrackerEntriesAccess {
    @Accessor("entries") DataTracker.Entry<?>[] mysticism$entries();
}
