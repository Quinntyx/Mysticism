package io.github.mysticism.net.mixin;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Bounded lazy source-neighborhood iteration, with abort rather than materializing all entities. */
@Mixin(ServerWorld.class)
public interface SourceEntityLookupAccessor {
    @Invoker("getEntityLookup") EntityLookup<Entity> mysticism$entityLookup();
}
