package io.github.mysticism.world.region;

// Local to a vanilla region (0..31). Smaller -> cheaper to store.

import net.minecraft.util.math.ChunkPos;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Rectangle of chunks (inclusive) in local vanilla-region coords [0,31]. */
public record ChunkBox(int minX, int minZ, int maxX, int maxZ) {
    public static final int REGION_SIDE = 32;
    public static final int MAX_CHUNKS = REGION_SIDE * REGION_SIDE;
    /** Decode unvalidated bounds first, so constructor errors become codec errors. */
    public static final Codec<ChunkBox> CODEC = RecordCodecBuilder.<int[]>create(i -> i.group(
            Codec.intRange(0, REGION_SIDE - 1).fieldOf("x0").forGetter(v -> v[0]),
            Codec.intRange(0, REGION_SIDE - 1).fieldOf("z0").forGetter(v -> v[1]),
            Codec.intRange(0, REGION_SIDE - 1).fieldOf("x1").forGetter(v -> v[2]),
            Codec.intRange(0, REGION_SIDE - 1).fieldOf("z1").forGetter(v -> v[3])
    ).apply(i, (x0, z0, x1, z1) -> new int[]{x0, z0, x1, z1})).flatXmap(v -> {
        try { return DataResult.success(new ChunkBox(v[0], v[1], v[2], v[3])); }
        catch (IllegalArgumentException invalid) { return DataResult.error(invalid::getMessage); }
    }, box -> DataResult.success(new int[]{box.minX(), box.minZ(), box.maxX(), box.maxZ()}));

    public ChunkBox {
        if (minX < 0 || minZ < 0 || maxX >= REGION_SIDE || maxZ >= REGION_SIDE
                || minX > maxX || minZ > maxZ)
            throw new IllegalArgumentException("Chunk box must be ordered within [0,31]");
    }

    public int width()  { return maxX - minX + 1; }
    public int height() { return maxZ - minZ + 1; }
    public int area()   { return width() * height(); }
    public ChunkPos centerLocal() {
        return new ChunkPos((minX + maxX) >>> 1, (minZ + maxZ) >>> 1);
    }
    public boolean containsLocal(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
