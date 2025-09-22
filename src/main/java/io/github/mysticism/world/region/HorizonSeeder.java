package io.github.mysticism.world.region;

import io.github.mysticism.embedding.EmbeddingHelper;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.world.region.impl.BiomeSpiritualRegion;
import io.github.mysticism.world.state.SpatialEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.lang.Math.clamp;
import static org.joml.Math.lerp;

/**
 * HorizonSeeder (vanilla-region based; simple full-area scan each tick)
 */
public final class HorizonSeeder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-HorizonSeeder");

    // Radius in chunks -> convert to vanilla regions (32x32 chunks)
    private static final int RADIUS_CHUNKS = 128;
    private static final int RADIUS_REGIONS = Math.max(1, RADIUS_CHUNKS >> 5); // 32 chunks per vanilla region

    // Queue sizing
    private static final int MAX_QUEUE = 20_000;

    // Consumer (regions/tick) tuning
    private static int regionsPerTick = 6;
    private static final int MIN_REGIONS_PER_TICK = 2;
    private static final int MAX_REGIONS_PER_TICK = 64;
    private static final double INC_FACTOR = 1.50;  // +50% when healthy
    private static final double DEC_FACTOR = 0.85;  // -15% when laggy
    private static final double LAG_THRESHOLD = 0.80; // back off when mspt > 1.20 * target

    // Work queue
    private static final Deque<Work> QUEUE = new ArrayDeque<>();
    private static final Set<String> ENQUEUED = new HashSet<>();

    private static volatile boolean STARTED = false;

    private record Work(ServerWorld world, int regionX, int regionZ, String id) {}

    private HorizonSeeder() {}

    // ---------- Public getters for /horizonseeder summary ----------
    public static int getQueueSize() { return QUEUE.size(); }
    public static int getRegionsPerTick() { return regionsPerTick; }
    public static int getMaxQueue() { return MAX_QUEUE; }
    public static boolean isQueueSaturated() { return QUEUE.size() >= MAX_QUEUE; }
    public static boolean isBusy() { return !QUEUE.isEmpty(); }

    // Tunables
    public static final double FILL_TARGET = 0.95;   // want 95% of chunks in view loaded
    public static final double FILL_MIN    = 0.85;   // below this, we strongly back off

    /** 0..1 headroom from MSPT (1 = fully healthy, 0 = at/over lag threshold). */
    private static double msptHeadroom(MinecraftServer server) {
        double mspt   = getMspt(server);
        double target = getTargetMspt(server);
        double ratio  = mspt / target;                 // >1 = slower than target
        if (ratio >= LAG_THRESHOLD) return 0.0;
        // Map ratio in [1.0, LAG_THRESHOLD] -> headroom [1..0]
        return clamp((LAG_THRESHOLD - ratio) / (LAG_THRESHOLD - 1.0), 0, 1);
    }

    /** 0..1 headroom from **chunk-fill** around players (1 = ring full, 0 = very empty). */
    public static double chunkFillHeadroom(MinecraftServer server) {
        // Worst (minimum) fill across all players/worlds
        double worstFill = 1.0;

        for (ServerWorld w : server.getWorlds()) {
            var players = w.getPlayers();
            if (players.isEmpty()) continue;

            int vd = server.getPlayerManager().getViewDistance();
            // Check a slightly larger ring than view distance, but never beyond our horizon
            int r = Math.min(RADIUS_CHUNKS, vd + 2);

            for (var p : players) {
                ChunkPos c = p.getChunkPos();
                int loaded = 0, total = 0;

                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        total++;
                        // Fast “is loaded?” check without generating/loading
                        if (w.getChunkManager().isChunkLoaded(c.x + dx, c.z + dz)) {
                            loaded++;
                        }
                    }
                }
                double fill = (total == 0) ? 1.0 : (loaded / (double) total);
                if (fill < worstFill) worstFill = fill;
            }
        }

        // Map fill ∈ [FILL_MIN, FILL_TARGET] → headroom ∈ [0..1], clamp outside
        if (worstFill >= FILL_TARGET) return 1.0;
        if (worstFill <= FILL_MIN)    return 0.0;
        return (worstFill - FILL_MIN) / (FILL_TARGET - FILL_MIN);
    }


    public static double getMspt(MinecraftServer server) {
        return server.getAverageNanosPerTick() / 1_000_000.0;
    }
    public static double getTargetMspt(MinecraftServer server) {
        double tps = Math.max(1.0, server.getTickManager().getTickRate());
        return 1000.0 / tps;
    }
    public static double getLagRatio(MinecraftServer server) {
        return getMspt(server) / getTargetMspt(server);
    }

    public static double getChunkFillHeadroom(MinecraftServer server) {
        return chunkFillHeadroom(server); // your internal method
    }

    public static double getWorstChunkFill(MinecraftServer server) {
        // return the “worstFill” we compute inside chunkFillHeadroom(); or recompute here
        double worst = 1.0;
        // (copy the same loop used by chunkFillHeadroom to compute worstFill)
        // ... or cache it when chunkFillHeadroom runs and read the cached value here.
        return worst;
    }

    // ---------------- Lifecycle ----------------
    public static void register() {
        if (STARTED) return;
        STARTED = true;

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 1) Produce: scan all vanilla regions in radius around players; enqueue missing ones
            enqueueAllAroundPlayers(server);

            // 2) Consume: drain up to regionsPerTick (no buffer)
            int budget = Math.min(regionsPerTick, QUEUE.size());
            while (budget-- > 0 && !QUEUE.isEmpty()) {
                Work w = QUEUE.pollFirst();
                ENQUEUED.remove(w.id);
                try {
                    seedOneVanillaRegion(w.world, w.regionX, w.regionZ);
                } catch (Throwable t) {
                    LOGGER.warn("Seeding failed at vregion {},{}", w.regionX, w.regionZ, t);
                }
            }

            // 3) Retune every ~2 seconds — but ONLY if we’re not idle
            if (!QUEUE.isEmpty() && (server.getTicks() % 40 == 0)) {
                adjustForLoad(server);
            }
        });

        // Still here if you later re-enable refinement
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            try { refine(world, chunk); }
            catch (Throwable t) { LOGGER.warn("Refine failed at {}", chunk.getPos(), t); }
        });
    }

    // ---------------- Producer: full-area scan around each player ----------------
    private static void enqueueAllAroundPlayers(MinecraftServer server) {
        var state = SpatialEmbeddingIndexState.get(server);
        int cap = MAX_QUEUE - QUEUE.size();
        if (cap <= 0) return;

        for (ServerWorld world : server.getWorlds()) {
            List<ServerPlayerEntity> players = world.getPlayers();
            if (players.isEmpty()) continue;

            // Shuffle players a bit so multi-player worlds spread work fairly
            int startIdx = Math.floorMod(server.getTicks(), players.size());
            for (int i = 0; i < players.size() && cap > 0; i++) {
                ServerPlayerEntity p = players.get((startIdx + i) % players.size());
                ChunkPos c = p.getChunkPos();
                int cRX = c.x >> 5, cRZ = c.z >> 5;

                int minRX = cRX - RADIUS_REGIONS;
                int maxRX = cRX + RADIUS_REGIONS;
                int minRZ = cRZ - RADIUS_REGIONS;
                int maxRZ = cRZ + RADIUS_REGIONS;

                // Simple nested scan; bail early if queue is full
                for (int rZ = minRZ; rZ <= maxRZ && cap > 0; rZ++) {
                    for (int rX = minRX; rX <= maxRX && cap > 0; rX++) {
                        String keyPrefix = vregionKey(world, rX, rZ); // used for ENQUEUED de-dupe

                        // If anything is already present in this vanilla region, skip producing it again.
                        if (state.hasAnyInVanillaRegion(world, rX, rZ)) continue;

                        if (!ENQUEUED.contains(keyPrefix)) {
                            QUEUE.addLast(new Work(world, rX, rZ, keyPrefix));
                            ENQUEUED.add(keyPrefix);
                            cap--;
                        }
                    }
                }
            }
        }
    }

    // ---------------- Rate control (consumer only; skipped when idle) ----------------
    /** Adjust regionsPerTick using BOTH MSPT and chunk-fill pressure. */
    private static void adjustForLoad(MinecraftServer server) {
        double hMspt  = msptHeadroom(server);         // 0..1
        double hFill  = chunkFillHeadroom(server);    // 0..1
        double head   = Math.min(hMspt, hFill);       // take the bottleneck

        // In single-player, leave extra headroom for the client
        if (!server.isDedicated()) head *= 0.75;      // ~25% cushion for client render thread

        // Convert headroom to a target throughput
        int target = (int)Math.round(lerp(MIN_REGIONS_PER_TICK, MAX_REGIONS_PER_TICK, head));

        // Move toward target smoothly (hysteresis)
        if (regionsPerTick < target) {
            regionsPerTick = Math.min(MAX_REGIONS_PER_TICK,
                    Math.max(MIN_REGIONS_PER_TICK, (int)Math.ceil(regionsPerTick * INC_FACTOR)));
        } else if (regionsPerTick > target) {
            regionsPerTick = Math.max(MIN_REGIONS_PER_TICK,
                    (int)Math.floor(regionsPerTick * DEC_FACTOR));
        }
    }


    // ---------------- Work execution ----------------
    private static void seedOneVanillaRegion(ServerWorld world, int rX, int rZ) {
        if (!EmbeddingHelper.isReady()) return;
        var state = SpatialEmbeddingIndexState.get(world.getServer());

        int baseX = rX << 5, baseZ = rZ << 5;
        Map<Identifier, int[]> bounds = new HashMap<>(); // biome -> [minX,minZ,maxX,maxZ] local [0,31]

        // One-pass biome bounds over 32x32 chunks
        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                int chunkX = baseX + dx, chunkZ = baseZ + dz;
                var biomeKey = world.getBiome(new BlockPos((chunkX<<4)+8, world.getSeaLevel(), (chunkZ<<4)+8)).getKey();
                if (biomeKey.isEmpty()) continue;
                Identifier bid = biomeKey.get().getValue();

                int[] b = bounds.computeIfAbsent(bid, ignore -> new int[]{ 32, 32, -1, -1});
                if (dx < b[0]) b[0] = dx; if (dz < b[1]) b[1] = dz;
                if (dx > b[2]) b[2] = dx; if (dz > b[3]) b[3] = dz;
            }
        }
        if (bounds.isEmpty()) return;

        for (var e : bounds.entrySet()) {
            Identifier biomeId = e.getKey();
            int[] bb = e.getValue();
            ChunkBox box = new ChunkBox(bb[0], bb[1], bb[2], bb[3]);

            String id = regionId(world, rX, rZ, biomeId);
            if (state.regionsView().containsKey(id)) continue;

            String prompt = "region coarse biome="+biomeId+" dim="+world.getRegistryKey().getValue();
            EmbeddingHelper.getEmbedding(prompt).whenComplete((baseVec, err) -> {
                if (err != null || baseVec == null) {
                    LOGGER.warn("embed failed for {}: {}", id, (err != null ? err.toString() : "null vec"));
                    return;
                }

                int h = Objects.hash(id);
                Random r = new Random(h);
                float[] d = new float[384];
                for (int i = 0; i < 384; i++) d[i] += (r.nextFloat() - 0.5f) * 1e-4f;
                Vec384f vec = baseVec.add(new Vec384f(d));

                world.getServer().execute(() -> {
                    if (!state.regionsView().containsKey(id)) {
                        var region = new BiomeSpiritualRegion(rX, rZ, biomeId, List.of(box));
                        state.putIfAbsent(id, region, vec);
                    }
                });
            });
        }
    }

    private static void refine(ServerWorld world, Chunk chunk) { /* no-op */ }

    // ---------------- Utils ----------------
    private static String vregionKey(ServerWorld world, int rX, int rZ) {
        return world.getRegistryKey().getValue() + "|vregion|" + rX + "," + rZ;
    }
    private static String regionId(ServerWorld world, int rX, int rZ, Identifier biomeId) {
        return vregionKey(world, rX, rZ) + "|" + biomeId;
    }
}
