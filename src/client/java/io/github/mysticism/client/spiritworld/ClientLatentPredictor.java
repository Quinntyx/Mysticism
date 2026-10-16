package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.navigation.WalkingModePolicy;
import io.github.mysticism.navigation.MovementProvenance;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Shared server/client movement math: correction provenance excludes repositioning and applied
 *  depenetration; post-integration pose history rejects delayed/reordered authoritative snapshots.
 *  Stationary CCA touch corrections still refresh render state. */
@Environment(EnvType.CLIENT)
public final class ClientLatentPredictor {
    private static boolean initialized;
    private static Vec3d lastPos;
    private static ClientPlayerEntity lastPlayer;
    private static ClientWorld lastWorld;
    private static long lastEpoch = -1;
    private static boolean lastDeep;
    private ClientLatentPredictor() {}
    public static void init() {
        if (initialized) return; initialized = true;
        // The pose sync ordering guard shares this predictor's context; install before any sync lands.
        ClientPoseSync.install();
        ClientTickEvents.END_CLIENT_TICK.register(ClientLatentPredictor::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    private static void clear() {
        lastPos = null; lastPlayer = null; lastWorld = null; lastEpoch = -1; lastDeep = false;
        ClientPoseSync.clear();
    }
    private static void onEndTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) { clear(); return; }
        var nav = mc.player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        ClientSpiritCache.updateNavigation(nav.active(), nav.deep(), nav.sourceDimension(), nav.sourcePosition());
        var basis = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        var q = mc.player.getComponent(MysticismEntityComponents.LATENT_POS).get();
        var target = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit")) && nav.active()) {
            // Drain provenance every spirit tick; stale markers can never skip a later window.
            boolean repositioned = MovementProvenance.drainClientRepositioned(mc.player.getUuid());
            Vec3d correction = MeshCollision.drainCorrection(mc.player);
            Vec3d now = mc.player.getPos();
            boolean predicting = lastPlayer == mc.player && lastWorld == mc.world && lastPos != null
                    && lastEpoch == nav.motionEpoch() && lastDeep == nav.deep() && nav.semanticReady()
                    && mc.player.getComponent(MysticismEntityComponents.LATENT_POS).hasCoordinate();
            // Consume even during a baseline break: a correction cannot skip a later clean window.
            boolean corrected = ClientPoseSync.consumeTeleportCorrection() || repositioned;
            // Register the authoritative baseline before any first-window prediction can advance it.
            ClientPoseSync.preparePrediction(
                    mc.player.getComponent(MysticismEntityComponents.LATENT_POS),
                    mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS), nav, predicting && !corrected);
            if (predicting && !corrected) {
                Vec3d delta = MovementIntegration.clientSemantic(now.subtract(lastPos), correction, false);
                if (nav.deep() && nav.supportApproach()) WalkingModePolicy.advanceSupport(q, basis, delta.x, delta.y, delta.z);
                else if (nav.deep() && nav.landingApproach()) TraversalSteering.approachStep(q, target, delta.x, delta.y, delta.z);
                else if (nav.deep()) TraversalSteering.deepStep(q, basis, target, delta.x, delta.y, delta.z, nav.hasShallowTarget(), ClientPoseSync::recordPredictionPose);
                else if (!nav.landmarkId().isEmpty()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
            }
            // Record AFTER integration so a reversed/accumulated movement endpoint is not lost.
            ClientPoseSync.recordPredictionPose(q, basis);
            lastPos = now; lastPlayer = mc.player; lastWorld = mc.world;
            lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
        } else clear();
        // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
        ClientSpiritCache.updateObserver(q, basis);
    }
}
