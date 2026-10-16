package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.component.LatentPos;
import io.github.mysticism.component.LatentBasis;
import io.github.mysticism.navigation.MovementProvenance;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Shared server/client movement math: correction provenance excludes repositioning and applied
 * depenetration. Prediction changes only the observer render frame; synced components retain ordered
 * authority. Explicit corrections retire render prediction even if the authoritative pose is unchanged.
 * Stationary CCA touch corrections still refresh render state. */
@Environment(EnvType.CLIENT)
public final class ClientLatentPredictor {
    private static boolean initialized;
    private static final PredictionContinuity CONTINUITY = new PredictionContinuity();
    private static LatentPos trackedPosition;
    private static LatentBasis trackedBasis;
    private static long positionCorrection, basisCorrection;
    private ClientLatentPredictor() {}
    public static void init() {
        if (initialized) return; initialized = true;
        // Install before sync delivery; component-local wire guards also reject stale/reordered snapshots.
        // Render-only prediction deliberately never registers a component-mutating prediction context.
        ClientPoseSync.install();
        ClientTickEvents.END_CLIENT_TICK.register(ClientLatentPredictor::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    private static void clear() {
        CONTINUITY.cleared(); ClientPoseSync.clear();
        trackedPosition=null; trackedBasis=null; positionCorrection=basisCorrection=0;
    }
    /** Render-only prediction must honor forced authority even when its coordinates did not change.
     * Revisions are independent: receiving q cannot retire a later basis correction (or vice versa). */
    static boolean refreshCorrectionState(LatentPos position, LatentBasis basis) {
        boolean reset = trackedPosition != position || trackedBasis != basis
                || positionCorrection != position.receivedCorrectionRevision()
                || basisCorrection != basis.receivedCorrectionRevision();
        trackedPosition=position; trackedBasis=basis;
        positionCorrection=position.receivedCorrectionRevision(); basisCorrection=basis.receivedCorrectionRevision();
        if (reset) ClientSpiritCache.resetPrediction();
        return reset;
    }
    /** A server-decided arrival position was just applied client-side (PlayerPositionLook): re-seed
     * prediction so the arrival delta is never integrated, even when the epoch update was processed
     * one tick earlier. Called by the client arrival mixin on the main thread. */
    public static void onArrivalApplied() {
        CONTINUITY.onArrivalApplied(); ClientSpiritCache.resetPrediction();
    }
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
        ClientSpiritCache.updateNavigation(nav.active(), nav.deep(), nav.sourceDimension(), nav.sourcePosition(), nav.motionEpoch());
        var basis = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        var q = mc.player.getComponent(MysticismEntityComponents.LATENT_POS).get();
        var target = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit")) && nav.active()) {
            // Drain provenance every spirit tick; stale markers can never skip a later window.
            boolean repositioned = MovementProvenance.drainClientRepositioned(mc.player.getUuid());
            Vec3d correction = MeshCollision.drainCorrection(mc.player);
            Vec3d now = mc.player.getPos();
            // Arrival packets and epoch/mode changes re-baseline only the render prediction.
            Vec3d delta = CONTINUITY.integrableDelta(mc.player, mc.world, now,
                    nav.motionEpoch(), nav.deep(), nav.semanticReady());
            boolean corrected = refreshCorrectionState(
                    mc.player.getComponent(MysticismEntityComponents.LATENT_POS),
                    mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS));
            // Always consume both correction channels, including when another already marked it.
            boolean positionLook = ClientPoseSync.consumeTeleportCorrection();
            delta = semanticWindow(delta, correction, repositioned || positionLook || corrected);
            advanceFrame(q, basis, target, delta, nav.deep(), nav.supportApproach(),
                    nav.landingApproach(), nav.hasShallowTarget(), !nav.landmarkId().isEmpty());
        } else {
            clear();
            // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
            ClientSpiritCache.refreshObserver(q, basis);
            ClientSpiritCache.beginTick(Vec3d.ZERO);
        }
    }

    /** Same correction-accounted window used by the actual end-tick callback and regression replay. */
    static Vec3d semanticWindow(Vec3d delta, Vec3d correction, boolean repositioned) {
        return delta == null ? Vec3d.ZERO : MovementIntegration.clientSemantic(delta, correction, repositioned);
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
