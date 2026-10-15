package io.github.mysticism.navigation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-tick movement provenance shared by terrain mapping, the server evolver and the client predictor.
 *  Teleports and authoritative position corrections are never chosen movement: whatever
 *  their size, the integrators must not fold them into semantic advance. Without this,
 *  a correction pulse is integrated as travel, the corrected state reacts, the correction
 *  repeats, and movement rubber-bands. */
public final class MovementProvenance {
    private static final Map<UUID, Boolean> SERVER_TELEPORT = new ConcurrentHashMap<>();
    // Terrain and semantics run in separate callbacks. Neither may consume the other's evidence.
    private static final Map<UUID, Boolean> SERVER_TERRAIN_TELEPORT = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> CLIENT_REPOSITION = new ConcurrentHashMap<>();
    private MovementProvenance() {}
    /** Vanilla requestTeleport: "moved too quickly/wrongly" corrections, pending teleports and command teleports. */
    public static void markServerTeleport(UUID player) {
        if (player == null) return;
        SERVER_TELEPORT.put(player, Boolean.TRUE);
        SERVER_TERRAIN_TELEPORT.put(player, Boolean.TRUE);
    }
    /** Client-side application of a server position-look correction to the local player. */
    public static void markClientRepositioned(UUID player) { if (player != null) CLIENT_REPOSITION.put(player, Boolean.TRUE); }
    public static boolean drainServerTeleport(UUID player) { return player != null && SERVER_TELEPORT.remove(player) != null; }
    /** Independent once-per-terrain-window copy of requestTeleport provenance, regardless of size. */
    public static boolean drainTerrainTeleport(UUID player) { return player != null && SERVER_TERRAIN_TELEPORT.remove(player) != null; }
    public static boolean drainClientRepositioned(UUID player) { return player != null && CLIENT_REPOSITION.remove(player) != null; }
    public static void clear(UUID player) {
        if (player == null) return;
        SERVER_TELEPORT.remove(player); SERVER_TERRAIN_TELEPORT.remove(player); CLIENT_REPOSITION.remove(player);
    }
}
