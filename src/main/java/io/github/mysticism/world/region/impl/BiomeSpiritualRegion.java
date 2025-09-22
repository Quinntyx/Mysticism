package io.github.mysticism.world.region.impl;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mysticism.world.region.ChunkBox;
import io.github.mysticism.world.region.ISpiritualRegion;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class BiomeSpiritualRegion implements ISpiritualRegion {
    /** Vanilla region coordinate (chunk >> 5). */
    private final int regionX;
    private final int regionZ;
    private final Identifier biomeId;
    private final List<ChunkBox> boxes;

    /** Optional, cached global BlockPos (serialized). */
    private Optional<BlockPos> cachedSpawn; // serialized

    // ---- CODEC ----

    private static final MapCodec<ChunkBox> BOX_CODEC = RecordCodecBuilder.mapCodec(i ->
            i.group(
                    Codec.INT.fieldOf("x0").forGetter(ChunkBox::minX),
                    Codec.INT.fieldOf("z0").forGetter(ChunkBox::minZ),
                    Codec.INT.fieldOf("x1").forGetter(ChunkBox::maxX),
                    Codec.INT.fieldOf("z1").forGetter(ChunkBox::maxZ)
            ).apply(i, ChunkBox::new)
    );

    // BlockPos codec via long for compactness
    private static final Codec<BlockPos> BLOCKPOS_LONG_CODEC =
            Codec.LONG.xmap(BlockPos::fromLong, BlockPos::asLong);

    public static final MapCodec<BiomeSpiritualRegion> CODEC = RecordCodecBuilder.mapCodec(i ->
            i.group(
                    Codec.INT.fieldOf("rx").forGetter(BiomeSpiritualRegion::regionX),
                    Codec.INT.fieldOf("rz").forGetter(BiomeSpiritualRegion::regionZ),
                    Identifier.CODEC.fieldOf("biome").forGetter(BiomeSpiritualRegion::biomeId),
                    BOX_CODEC.codec().listOf().fieldOf("boxes").forGetter(BiomeSpiritualRegion::boxes),
                    BLOCKPOS_LONG_CODEC.optionalFieldOf("spawn").forGetter(BiomeSpiritualRegion::cachedSpawn)
            ).apply(i, BiomeSpiritualRegion::new)
    );

    // ---- ctor & getters ----

    public BiomeSpiritualRegion(int rx, int rz, Identifier biomeId, List<ChunkBox> boxes) {
        this(rx, rz, biomeId, boxes, Optional.empty());
    }

    public BiomeSpiritualRegion(int rx, int rz, Identifier biomeId, List<ChunkBox> boxes, Optional<BlockPos> spawn) {
        this.regionX = rx;
        this.regionZ = rz;
        this.biomeId = biomeId;
        this.boxes = List.copyOf(boxes);
        this.cachedSpawn = spawn;
    }

    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }
    public Identifier biomeId() { return biomeId; }
    public List<ChunkBox> boxes() { return boxes; }
    public Optional<BlockPos> cachedSpawn() { return cachedSpawn; }

    /**
     * Return a good spawn within this region, computing it if needed.
     * Search strategy:
     *  1) Try center of largest box (preferred).
     *  2) Fallback: scan other chunks within the region, closest-first to that center.
     *
     * @param world Server world (must be same dimension the id targets).
     * @param maxChunksToScan safety cap; typical 128..512. Use Integer.MAX_VALUE to allow full scan (<= 1024).
     */
    public Optional<BlockPos> getOrComputeSpawn(ServerWorld world, int maxChunksToScan) {
        if (cachedSpawn != null && cachedSpawn.isPresent()) return cachedSpawn;

        // Center chunk for ordering (largest box)
        ChunkBox largest = boxes.stream().max(Comparator.comparingInt(ChunkBox::area))
                .orElseGet(() -> new ChunkBox(16, 16, 16, 16));
        ChunkPos centerLocal = largest.centerLocal();
        ChunkPos centerGlobal = localToGlobal(centerLocal);

        // Build candidate list (all chunks in all boxes), distance-sorted
        List<ChunkPos> candidates = enumerateAllChunksSorted(centerGlobal);

        // 1) First: try the center chunk itself
        Optional<BlockPos> center = findSpawnInChunk(world, centerGlobal);
        if (center.isPresent()) {
            cachedSpawn = center;
            return cachedSpawn;
        }

        // 2) Then: walk other chunks, capped
        int scanned = 0;
        for (ChunkPos c : candidates) {
            if (c.equals(centerGlobal)) continue;
            if (++scanned > Math.max(1, maxChunksToScan)) break;

            Optional<BlockPos> p = findSpawnInChunk(world, c);
            if (p.isPresent()) {
                cachedSpawn = p;
                return cachedSpawn;
            }
        }

        // Not found (very rare: fully ocean, frozen oceans at night etc.)
        cachedSpawn = Optional.empty();
        return cachedSpawn;
    }

    /**
     * Fast path for teleport: if spawn not computed, try center; if still unknown, pick
     * the center of largest box as a fallback (still playable even if water).
     */
    public BlockPos teleport(ServerWorld world) {
        Optional<BlockPos> s = getOrComputeSpawn(world, 256);
        if (s.isPresent()) return s.get();

        // Fallback: center of largest box at sea level
        ChunkBox best = boxes.stream().max(Comparator.comparingInt(ChunkBox::area))
                .orElseGet(() -> new ChunkBox(16,16,16,16));
        ChunkPos local = best.centerLocal();
        ChunkPos global = localToGlobal(local);
        return new BlockPos((global.x << 4) + 8, world.getSeaLevel(), (global.z << 4) + 8);
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    /** Convert local [0,31] chunk coords to global chunk coords. */
    private ChunkPos localToGlobal(ChunkPos local) {
        return new ChunkPos((regionX << 5) + local.x, (regionZ << 5) + local.z);
    }

    /** Enumerate all chunks covered by boxes, sorted by distance to centerGlobal (Manhattan). */
    private List<ChunkPos> enumerateAllChunksSorted(ChunkPos centerGlobal) {
        ArrayList<ChunkPos> out = new ArrayList<>();
        for (ChunkBox b : boxes) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    out.add(localToGlobal(new ChunkPos(x, z)));
                }
            }
        }
        out.sort(Comparator.comparingInt(c ->
                Math.abs(c.x - centerGlobal.x) + Math.abs(c.z - centerGlobal.z)));
        return out;
    }

    /**
     * Try to find a top surface in a chunk that is:
     *  - exposed to sky
     *  - not water
     *  - solid block to stand on
     * Scans positions in a 16×16 spiral centered at (8,8).
     */
    private static Optional<BlockPos> findSpawnInChunk(ServerWorld world, ChunkPos chunk) {
        // ensure chunk is generated/loaded (blocking on server thread)
        world.getChunk(chunk.x, chunk.z); // FULL by default

        // Spiral over local positions with center bias
        final int cx = (chunk.getStartX()) + 8;
        final int cz = (chunk.getStartZ()) + 8;

        // spiral radii 0..8
        for (int r = 0; r <= 8; r++) {
            // top & bottom rows
            for (int dx = -r; dx <= r; dx++) {
                int x1 = cx + dx, z1 = cz - r;
                Optional<BlockPos> p1 = tryColumn(world, x1, z1);
                if (p1.isPresent()) return p1;

                int z2 = cz + r;
                if (r != 0) { // avoid duplicate when r==0
                    Optional<BlockPos> p2 = tryColumn(world, x1, z2);
                    if (p2.isPresent()) return p2;
                }
            }
            // left & right cols (without corners)
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                int z1 = cz + dz, xL = cx - r;
                Optional<BlockPos> pL = tryColumn(world, xL, z1);
                if (pL.isPresent()) return pL;

                int xR = cx + r;
                Optional<BlockPos> pR = tryColumn(world, xR, z1);
                if (pR.isPresent()) return pR;
            }
        }
        return Optional.empty();
    }

    /** Column test at (x,z): SKY, no water, walkable below, air at pos. */
    private static Optional<BlockPos> tryColumn(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z); // topmost ground-ish
        BlockPos pos = new BlockPos(x, y, z);

        // Require sky and dry
        if (!world.isSkyVisible(pos)) return Optional.empty();
        if (!world.getFluidState(pos).isEmpty()) return Optional.empty();

        var here = world.getBlockState(pos);
        var below = world.getBlockState(pos.down());

        if (!here.isAir()) return Optional.empty();
        if (!below.isOpaque()) return Optional.empty(); // simple walkable check

        return Optional.of(pos);
    }

    @Override
    public List<Box> bounds() {
        return List.of();
    }

    @Override
    public BlockPos resolveSpawn(ServerWorld world) {
        return this.teleport(world);
    }
}