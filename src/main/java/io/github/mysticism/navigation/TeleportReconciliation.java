package io.github.mysticism.navigation;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Server-authoritative teleport arrival reconciliation for the spirit world.
 * A teleport arrival is NOT chosen movement, but every stale piece of the previous movement state
 * used to leak into it and produced visible rubber banding:
 * <ul>
 *   <li>the server kept its residual velocity through the arrival (vanilla zeroes only the CLIENT
 *       velocity on an absolute PlayerPositionLook packet, and preserves relative-axis CLIENT
 *       velocity). Both peers must drop residual momentum, or they drift away from the mutually
 *       accepted arrival pose and are then corrected back — the rubber band;</li>
 *   <li>the evolver's last-integrated pose was pre-teleport, and the existing &gt;4-block delta clamp
 *       only discarded LARGE teleports: a 0–4 block /tp inside the spirit world was integrated as
 *       real movement and advanced the latent position (both server evolver and client predictor);</li>
 *   <li>the client predictor kept its pre-teleport last pose; without an epoch advance its arrival
 *       delta would be integrated as semantic travel for the sub-4-block window — and an epoch
 *       advance alone is insufficient, because the epoch update can be processed one client tick
 *       before the arrival position packet. The predictor therefore ALSO re-seeds when the arrival
 *       position is actually applied client-side (SpiritArrivalReseedMixin), covering both
 *       processing orders.</li>
 * </ul>
 * Both hooks run on the server thread inside {@code ServerPlayNetworkHandler.requestTeleport}, the
 * single funnel every player teleport passes through (vanilla /tp, /spreadplayers, /spectate, mod
 * cross-dimension teleports). They are scoped to the spirit world; source-world teleports keep
 * vanilla behavior. */
public final class TeleportReconciliation {
    private TeleportReconciliation() {}

    /** Called BEFORE vanilla publishes the arrival position packet. Advancing the synced prediction
     * epoch here means the component update is written to the connection before PlayerPositionLook,
     * closing the same-tick processing case. It cannot close the split-tick case (the epoch update
     * may be processed one client tick before the position packet, recording the new epoch against
     * the pre-teleport pose); the deterministic guarantee is the client-side re-seed when the arrival
     * position is actually applied (SpiritArrivalReseedMixin → PredictionContinuity). */
    public static void beforeSpiritTeleport(ServerPlayerEntity player) {
        if (!inSpirit(player)) return;
        var nav = player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        if (!nav.active()) return; // Inactive navigation never integrates movement; predictor is idle.
        nav.invalidateMotionPrediction();
        MysticismEntityComponents.SPIRIT_NAVIGATION.sync(player);
    }

    /** Called AFTER vanilla applied the arrival position server-side. Residual momentum is not
     * chosen arrival movement: zero it, drop stale fall distance, and re-seed the evolver's
     * last-integrated pose with the arrival pose so the next integration delta is exactly ZERO. */
    public static void afterSpiritTeleport(ServerPlayerEntity player) {
        if (!inSpirit(player)) return;
        player.setVelocity(velocityAfterArrival(player.getWorld().getRegistryKey(), player.getVelocity()));
        player.fallDistance = 0;
        // The evolver re-seeds from the CURRENT (arrival) pose: the arrival delta is reconciliation,
        // never semantic travel, and ordinary movement on the next tick integrates normally.
        SpiritBasisEvolver.resetMotion(player);
    }

    /** Shared arrival momentum policy for both peers. Apply AFTER vanilla resolves relative position
     * flags: those flags preserve client velocity on their axes in 1.21.1, but an arrival in spirit
     * must start from rest just like the server. Source-world arrivals retain vanilla momentum.
     * Independent of navigation mode/readiness and the order of prediction-epoch delivery. */
    public static Vec3d velocityAfterArrival(RegistryKey<World> world, Vec3d vanillaVelocity) {
        return world.equals(SpiritTerrainService.WORLD) ? Vec3d.ZERO : vanillaVelocity;
    }

    private static boolean inSpirit(ServerPlayerEntity player) {
        return player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD);
    }
}
