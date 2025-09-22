package io.github.mysticism.world.region.impl;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mysticism.world.region.ISpiritualRegion;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

import static io.github.mysticism.Mysticism.LOGGER;

@Deprecated
public final class ChunkSpiritualRegion implements ISpiritualRegion {
    public static final Identifier TYPE_ID = Identifier.of("mysticism", "chunk");

    private final ChunkPos pos;
    private boolean refined;

    public ChunkSpiritualRegion(ChunkPos pos) {
        this(pos, false);
    }
    public ChunkSpiritualRegion(ChunkPos pos, boolean refined) { this.pos = pos; this.refined = refined; }


    public ChunkPos pos() { return pos; }

    public boolean isRefined() {
        return this.refined;
    }

    public void markRefined() {
        this.refined = true;
    }

//    @Override public Identifier typeKey() { return TYPE_ID; }

    @Override public List<Box> bounds() {
        int x0 = pos.getStartX(), z0 = pos.getStartZ();
        List<Box> out = new ArrayList<>();
        out.add(new Box(x0, Integer.MIN_VALUE / 4, z0, x0 + 16, Integer.MAX_VALUE / 4, z0 + 16));
        return out;
    }

    @Override public BlockPos resolveSpawn(ServerWorld world) {
        world.setChunkForced(pos.x, pos.z, true);
        world.getChunk(pos.x, pos.z);
        int x = pos.getStartX() + 8, z = pos.getStartZ() + 8;
        int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
        var out = new BlockPos(x, Math.max(y, world.getBottomY()), z);
        world.setChunkForced(pos.x, pos.z, false);
        return out;
    }

    public static final MapCodec<ChunkSpiritualRegion> CODEC =
            RecordCodecBuilder.<ChunkSpiritualRegion>mapCodec(i -> i.group(
                            ChunkPos.CODEC.fieldOf("chunk").forGetter(ChunkSpiritualRegion::pos),          // lambda helps inference too
                            Codec.BOOL.fieldOf("refined").forGetter(ChunkSpiritualRegion::isRefined)
                    ).apply(i, ChunkSpiritualRegion::new))
                    .flatXmap(
                            csr -> { LOGGER.info("[CSR] decode OK {}", csr.pos()); return DataResult.success(csr); },
                            DataResult::success
                    );

}

