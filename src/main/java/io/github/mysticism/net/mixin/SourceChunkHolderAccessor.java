package io.github.mysticism.net.mixin;
import net.minecraft.server.world.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Status-only generated proof; NEVER schedules a chunk load/generation. */
@Mixin(ServerChunkLoadingManager.class)
public interface SourceChunkHolderAccessor {
    @Invoker("getCurrentChunkHolder") ChunkHolder mysticism$currentHolder(long key);
}
