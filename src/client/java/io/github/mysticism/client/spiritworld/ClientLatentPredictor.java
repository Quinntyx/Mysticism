package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Same per-player movement math as server; stationary CCA touch corrections still refresh render state. */
@Environment(EnvType.CLIENT)
public final class ClientLatentPredictor {
    private static boolean initialized;
    private static final PredictionContinuity CONTINUITY = new PredictionContinuity();
    private ClientLatentPredictor() {}
    public static void init() {
        if (initialized) return; initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(ClientLatentPredictor::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    private static void clear() { CONTINUITY.cleared(); }
    /** A server-decided arrival position was just applied client-side (PlayerPositionLook): re-seed
     * prediction so the arrival delta is never integrated, even when the epoch update was processed
     * one tick earlier. Called by the client arrival mixin on the main thread. */
    public static void onArrivalApplied() { CONTINUITY.onArrivalApplied(); }
    /** The predictor integrates movement only across an unbroken (player, world, pose, epoch, mode,
     * readiness) chain. TeleportReconciliation advances the synced epoch before the arrival position
     * packet is published (closing the same-tick case), and the client arrival mixin re-seeds the
     * pose when PlayerPositionLook is applied (closing the split-tick case), so a teleport arrival is
     * never integrated as chosen semantic travel in either processing order. */
    public static boolean integratesMovement(boolean samePlayer, boolean sameWorld, boolean hadLastPose,
            long lastEpoch, long epoch, boolean lastDeep, boolean deep, boolean semanticReady) {
        return samePlayer && sameWorld && hadLastPose && lastEpoch == epoch && lastDeep == deep && semanticReady;
    }
    private static void onEndTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) { clear(); return; }
        var nav = mc.player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        ClientSpiritCache.updateNavigation(nav.active(), nav.deep(), nav.sourceDimension(), nav.sourcePosition());
        var basis = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        var q = mc.player.getComponent(MysticismEntityComponents.LATENT_POS).get();
        var target = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit")) && nav.active()) {
            Vec3d now = mc.player.getPos();
            // Acquisition/anchor corrections (even <4 blocks) are NOT chosen movement; the continuity
            // chain re-baselines on epoch advances AND on the applied arrival position itself.
            Vec3d delta = CONTINUITY.integrableDelta(mc.player, mc.world, now,
                    nav.motionEpoch(), nav.deep(), nav.semanticReady());
            if (delta != null) {
                if (nav.deep() && nav.supportApproach()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
                else if (nav.deep() && nav.landingApproach()) TraversalSteering.approachStep(q, target, delta.x, delta.y, delta.z);
                else if (nav.deep()) TraversalSteering.deepStep(q, basis, target, delta.x, delta.y, delta.z, nav.hasShallowTarget());
                else if (!nav.landmarkId().isEmpty()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
            }
        } else clear();
        // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
        ClientSpiritCache.updateObserver(q, basis);
    }
}
