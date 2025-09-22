package io.github.mysticism.world.region;

// Local to a vanilla region (0..31). Smaller -> cheaper to store.

import net.minecraft.util.math.ChunkPos;

/** Rectangle of chunks (inclusive) in local vanilla-region coords [0,31]. */
public record ChunkBox(int minX, int minZ, int maxX, int maxZ) {
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
