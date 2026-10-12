package io.github.mysticism.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Flight parity needs Entity's protected fall-flying flag reset, exactly like vanilla's flying travel branch. */
@Mixin(Entity.class)
public interface EntityFlagAccess {
    @Invoker("setFlag") void mysticism$setFlag(int index, boolean value);
}
