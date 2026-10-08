package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.navigation.MotionAlignment;
import io.github.mysticism.navigation.SpiritNavigationService;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Per-player original movement-driven basis evolution. No global frame, target following or teleport drift. */
public final class SpiritBasisEvolver {
    /** Bounded PHYSICAL movement drives navigation decisions; epoch-filtered SEMANTIC movement drives q/basis integration. */
    record TickMovement(Vec3d physical, Vec3d semantic) {}

    /**
     * Physical stays epoch-agnostic so real movement signals survive lifecycle transitions (a jump
     * ascending on the tick after a shallow acquisition commit must keep its takeoff grace);
     * semantic is zeroed across an epoch change so no transition-crossing movement is integrated,
     * exactly matching ClientLatentPredictor.
     */
    static TickMovement tickMovement(Vec3d last, Vec3d now, Long recordedEpoch, long epoch) {
        Vec3d physical = MotionAlignment.boundedDelta(last, now);
        boolean continuous = recordedEpoch != null && recordedEpoch == epoch;
        return new TickMovement(physical, continuous ? physical : Vec3d.ZERO);
    }

    private static final Map<MinecraftServer, Map<UUID, Vec3d>> LAST_POS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> LAST_EPOCH = new IdentityHashMap<>();
    private static boolean initialized;
    private SpiritBasisEvolver() {}
    private static void evolve(MinecraftServer server) {
        Map<UUID, Vec3d> positions = LAST_POS.computeIfAbsent(server, s -> new HashMap<>());
        Map<UUID, Long> epochs = LAST_EPOCH.computeIfAbsent(server, s -> new HashMap<>());
        Set<UUID> live = new HashSet<>();
        for (var p : server.getPlayerManager().getPlayerList()) {
            if (!p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD)) {
                positions.remove(p.getUuid()); epochs.remove(p.getUuid()); SpiritNavigationService.update(p, Vec3d.ZERO, Vec3d.ZERO); continue;
            }
            live.add(p.getUuid());
            long epoch = p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).motionEpoch();
            Vec3d now = p.getPos(), last = positions.put(p.getUuid(), now);
            Long recorded = epochs.put(p.getUuid(), epoch);
            // Bounded physical movement feeds navigation (jump grace, blend cancel); the
            // epoch-filtered delta feeds semantic integration only, exactly like the client.
            TickMovement movement = tickMovement(last, now, recorded, epoch);
            var basis = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
            var q = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
            var target = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
            if (SpiritNavigationService.update(p, movement.physical(), movement.semantic()))
                TraversalSteering.deepStep(q, basis, target, movement.semantic().x, movement.semantic().y, movement.semantic().z,
                        p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).hasShallowTarget());
            // Periodic authoritative reconciliation also delivers touch interpolation to a stationary recipient.
            if (server.getTicks() % 4 == 0) {
                MysticismEntityComponents.LATENT_BASIS.sync(p); MysticismEntityComponents.LATENT_POS.sync(p);
            }
        }
        positions.keySet().retainAll(live);
        epochs.keySet().retainAll(live);
    }
    public static void init() {
        if (initialized) return; initialized = true;
        SpiritNavigationService.init();
        ServerTickEvents.END_SERVER_TICK.register(SpiritBasisEvolver::evolve);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {LAST_POS.remove(server); LAST_EPOCH.remove(server);});
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p, from, to) -> clear(p.getServer(), p.getUuid()));
        ServerPlayerEvents.AFTER_RESPAWN.register((old, p, alive) -> clear(p.getServer(), p.getUuid()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(server, handler.player.getUuid()));
    }
    public static void resetMotion(net.minecraft.server.network.ServerPlayerEntity player) {
        var positions = LAST_POS.get(player.getServer());
        if (positions != null) positions.put(player.getUuid(), player.getPos());
    }
    private static void clear(MinecraftServer server, UUID player) {
        var positions = LAST_POS.get(server); if (positions != null) positions.remove(player);
        var epochs = LAST_EPOCH.get(server); if (epochs != null) epochs.remove(player);
    }
}
