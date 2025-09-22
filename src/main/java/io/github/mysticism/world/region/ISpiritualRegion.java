package io.github.mysticism.world.region;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/** Polymorphic region interface. */
public interface ISpiritualRegion {
    /** World-agnostic bounds (block-space, inclusive min/max). */
    List<Box> bounds();

    /** Lazily compute a verified spawn location (may force gen). */
    BlockPos resolveSpawn(ServerWorld world);
}

