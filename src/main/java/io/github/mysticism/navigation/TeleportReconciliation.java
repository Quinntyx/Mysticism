package io.github.mysticism.navigation;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/** Server-authoritative teleport arrival reconciliation for the spirit world.
 * A teleport arrival is NOT chosen movement, but every stale piece of the previous movement state
 * used to leak into it and produced visible rubber banding:
 * <ul>
 *   <li>the server kept its residual velocity through the arrival (vanilla zeroes only the CLIENT
 *       velocity on an absolute PlayerPositionLook packet), so the authoritative carrier drifted away
 *       from the mutually accepted arrival pose and was then corrected back — the rubber band;</li>
 *   <li>the evolver's last-integrated pose was pre-teleport, and the existing &gt;4-block delta clamp
 *       only discarded LARGE teleports: a 0–4 block /tp inside the spirit world was integrated as
 *       real movement and advanced the latent position (both server evolver and client predictor);</li>
 *   <li>the client predictor kept its pre-teleport last pose; without an epoch advance its arrival
 *       delta would be integrated as semantic travel for the sub-4-block window.</li>
 * </ul>
 * Both hooks run on the server thread inside {@code ServerPlayNetworkHandler.requestTeleport}, the
 * single funnel every player teleport passes through (vanilla /tp, /spreadplayers, /spectate, mod
 * cross-dimension teleports). They are scoped to the spirit world; source-world teleports keep
 * vanilla behavior. */
public final class TeleportReconciliation {
    private TeleportReconciliation() {}

    /** Called BEFORE vanilla publishes the arrival position packet. Advancing the synced prediction
     * epoch here means the component update is written to the connection before PlayerPositionLook,
     * so the client predictor provably drops its stale pre-teleport pose on the arrival tick instead
     * of racing the position packet. */
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
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0;
        // The evolver re-seeds from the CURRENT (arrival) pose: the arrival delta is reconciliation,
        // never semantic travel, and ordinary movement on the next tick integrates normally.
        SpiritBasisEvolver.resetMotion(player);
    }

    private static boolean inSpirit(ServerPlayerEntity player) {
        return player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD);
    }
}
