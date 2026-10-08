package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Same per-player movement math as server, with movement-ordering reconciliation: pose corrections
 * (position-look teleports) are never integrated as chosen movement, and the prediction context
 * feeds {@link ClientPoseSync} so delayed/reordered authoritative pose syncs cannot roll the
 * predicted spirit pose back to a stale snapshot. Stationary CCA touch corrections still refresh
 * render state.
 */
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
            Vec3d now = mc.player.getPos();
            boolean predicting = lastPlayer == mc.player && lastWorld == mc.world && lastPos != null
                    && lastEpoch == nav.motionEpoch() && lastDeep == nav.deep() && nav.semanticReady();
            // Acquisition/anchor corrections (even <4 blocks) are NOT chosen movement; neither are
            // authoritative position-look teleports. Corrected ticks re-baseline instead of rolling
            // the integrated semantic pose back to the stale pre-correction snapshot.
            boolean corrected = ClientPoseSync.consumeTeleportCorrection();
            if (predicting && !corrected) {
                Vec3d delta = now.subtract(lastPos);
                if (delta.lengthSquared() <= 16 && delta.lengthSquared() > 0) {
                    var qBefore = q.clone(); var basisBefore = basis.clone();
                    boolean integrated = false;
                    if (nav.deep() && nav.supportApproach()) { TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z); integrated = true; }
                    else if (nav.deep() && nav.landingApproach()) { TraversalSteering.approachStep(q, target, delta.x, delta.y, delta.z); integrated = true; }
                    else if (nav.deep()) { TraversalSteering.deepStep(q, basis, target, delta.x, delta.y, delta.z, nav.hasShallowTarget()); integrated = true; }
                    else if (!nav.landmarkId().isEmpty()) { TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z); integrated = true; }
                    if (integrated) ClientPoseSync.noteIntegration(qBefore, q, basisBefore, basis);
                }
            }
            ClientPoseSync.beginPrediction(
                    mc.player.getComponent(MysticismEntityComponents.LATENT_POS),
                    mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS),
                    nav, predicting && !corrected);
            lastPos = now; lastPlayer = mc.player; lastWorld = mc.world;
            lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
        } else clear();
        // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
        ClientSpiritCache.updateObserver(q, basis);
    }
}
