package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.navigation.MotionAlignment;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Same per-player movement math as server; stationary CCA touch corrections still refresh render state. */
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
        ClientTickEvents.END_CLIENT_TICK.register(ClientLatentPredictor::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    private static void clear() { lastPos = null; lastPlayer = null; lastWorld = null; lastEpoch = -1; lastDeep = false; }
    private static void onEndTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) { clear(); return; }
        var nav = mc.player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        ClientSpiritCache.updateNavigation(nav.active(), nav.deep(), nav.sourceDimension(), nav.sourcePosition(), nav.motionEpoch());
        var basis = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        var q = mc.player.getComponent(MysticismEntityComponents.LATENT_POS).get();
        var target = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit")) && nav.active()) {
            Vec3d now = mc.player.getPos();
            // Acquisition/anchor corrections (even <4 blocks) are NOT chosen movement, and a delta
            // crossing a prediction-epoch change is re-anchored, never integrated (shared rule).
            // Publication predicts only the RENDER frame; synced components stay authoritative.
            boolean tracked = lastPlayer == mc.player && lastWorld == mc.world;
            Vec3d delta = tracked
                    ? MotionAlignment.alignedDelta(lastPos, now, lastEpoch, nav.motionEpoch())
                    : Vec3d.ZERO;
            if (!tracked || lastDeep != nav.deep() || !nav.semanticReady()) delta = Vec3d.ZERO;
            advanceFrame(q, basis, target, delta, nav.deep(), nav.supportApproach(),
                    nav.landingApproach(), nav.hasShallowTarget(), !nav.landmarkId().isEmpty());
            lastPos = now; lastPlayer = mc.player; lastWorld = mc.world;
            lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
        } else {
            clear();
            // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
            ClientSpiritCache.refreshObserver(q, basis);
            ClientSpiritCache.beginTick(Vec3d.ZERO);
        }
    }

    /** Tick publication shared with CPU regressions; no component or renderer mutation. */
    static void advanceFrame(Vec384f q, Basis384f basis, Vec384f target, Vec3d delta,
                             boolean deep, boolean supportApproach, boolean landingApproach,
                             boolean hasShallowTarget, boolean hasLandmark) {
        ClientSpiritCache.refreshObserver(q, basis);
        ClientSpiritCache.beginTick(delta);
        // Predict only the render frame. Synced components remain authoritative and untouched.
        if (delta.lengthSquared() > 0) {
            var renderPos = ClientSpiritCache.playerLatentPos;
            var renderBasis = ClientSpiritCache.playerLatentBasis;
            if (deep && supportApproach) TraversalSteering.advance(renderPos, renderBasis, delta.x, delta.y, delta.z);
            else if (deep && landingApproach) TraversalSteering.approachStep(renderPos, target, delta.x, delta.y, delta.z);
            else if (deep) TraversalSteering.deepStep(renderPos, renderBasis, target, delta.x, delta.y, delta.z, hasShallowTarget);
            else if (hasLandmark) TraversalSteering.advance(renderPos, renderBasis, delta.x, delta.y, delta.z);
        }
    }
}
