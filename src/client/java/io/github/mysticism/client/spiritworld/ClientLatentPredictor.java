package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.navigation.SpiritPoseReconciliation;
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
 * Client prediction of the per-player semantic pose (latent q and basis), reconciled with
 * accepted spirit movement instead of oscillating against it.
 *
 * <p>The predictor advances the LOCAL player's semantic pose every client tick from predicted
 * movement, with the same per-player math the server evolver applies to accepted positions
 * (shallow translation, deep rotation/translation, support/landing approach). The four-tick
 * authoritative CCA sync is reconciled at arrival time by {@link SpiritPoseReconciliation}:
 * healthy in-flight prediction is kept (the server has simply not accepted that movement yet),
 * persistent divergence converges smoothly, and genuine divergence (mode transitions, teleports,
 * model changes) snaps to the server pose. Server-side components, persistence and payloads are
 * untouched. Stationary CCA touch corrections still refresh render state.
 */
@Environment(EnvType.CLIENT)
public final class ClientLatentPredictor {
    private static boolean initialized;
    private static final SpiritPoseReconciliation.Session SESSION = new SpiritPoseReconciliation.Session();
    private static Vec3d lastPos;
    private static ClientPlayerEntity lastPlayer;
    private static ClientWorld lastWorld;
    private static long lastEpoch = -1;
    private static boolean lastDeep;
    private static long lastSignature = -1;
    /** The pose objects the local player's components currently hold and this predictor advances. */
    private static io.github.mysticism.vector.Vec384f predictedQ;
    private static io.github.mysticism.vector.Basis384f predictedBasis;
    /** The player whose component instances carry the installed reconcilers. */
    private static ClientPlayerEntity hookedPlayer;
    private ClientLatentPredictor() {}
    public static void init() {
        if (initialized) return; initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(ClientLatentPredictor::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    private static void clear() {
        lastPos = null; lastPlayer = null; lastWorld = null; lastEpoch = -1; lastDeep = false;
        lastSignature = -1; predictedQ = null; predictedBasis = null; SESSION.reset();
    }
    /**
     * Installs the arrival-time reconciliation hooks on the LOCAL player's component instances.
     * Other players' components and all server instances keep plain sync semantics.
     */
    private static void ensureHooks(ClientPlayerEntity player) {
        if (hookedPlayer == player) return;
        hookedPlayer = player;
        SESSION.reset(); predictedQ = null; predictedBasis = null; lastPos = null; lastSignature = -1;
        player.getComponent(MysticismEntityComponents.LATENT_POS).setSyncReconciler((serverValue, current) -> {
            if (hookedPlayer != player) return serverValue; // stale instance after respawn: adopt authority
            var predicted = predictedQ != null ? predictedQ : current;
            var result = SESSION.reconcilePosition(serverValue, predicted);
            predictedQ = result;
            return result;
        });
        player.getComponent(MysticismEntityComponents.LATENT_BASIS).setSyncReconciler((serverValue, current) -> {
            if (hookedPlayer != player) return serverValue;
            var nav = player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
            // Server-owned basis trajectories (support/landing alignment) cannot be reproduced
            // client-side: adopt every sync while they are active.
            boolean serverDriven = nav.active() && nav.deep() && (nav.supportApproach() || nav.landingApproach());
            var predicted = predictedBasis != null ? predictedBasis : current;
            var result = SESSION.reconcileBasis(serverValue, predicted, serverDriven);
            predictedBasis = result;
            return result;
        });
    }
    /** Everything that changes which movement stream the prediction integrates. */
    private static long signature(io.github.mysticism.component.SpiritNavigation nav) {
        long value = nav.motionEpoch();
        value = value * 31 + (nav.deep() ? 1 : 0);
        value = value * 31 + (nav.semanticReady() ? 1 : 0);
        value = value * 31 + (nav.supportApproach() ? 1 : 0);
        value = value * 31 + (nav.landingApproach() ? 1 : 0);
        value = value * 31 + nav.landmarkId().hashCode();
        return value;
    }
    private static void onEndTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) { clear(); return; }
        var nav = mc.player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        ClientSpiritCache.updateNavigation(nav.active(), nav.deep(), nav.sourceDimension(), nav.sourcePosition());
        var posComponent = mc.player.getComponent(MysticismEntityComponents.LATENT_POS);
        var basisComponent = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS);
        var q = posComponent.get();
        var basis = basisComponent.get();
        var target = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (!(mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit")) && nav.active())) {
            clear(); // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
            ClientSpiritCache.updateObserver(q, basis);
            return;
        }
        ensureHooks(mc.player);
        long current = signature(nav);
        // Re-anchor to authoritative component values on player/world/mode changes or external
        // writes; the transition tick itself advances nothing (matches the epoch guard below).
        if (lastPlayer != mc.player || lastWorld != mc.world || current != lastSignature
                || q != predictedQ || basis != predictedBasis) {
            SESSION.reset(); predictedQ = q; predictedBasis = basis;
            lastPlayer = mc.player; lastWorld = mc.world; lastSignature = current;
            lastPos = mc.player.getPos();
            lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
            ClientSpiritCache.updateObserver(q, basis);
            return;
        }
        Vec3d now = mc.player.getPos();
        Vec3d delta = lastPos == null ? Vec3d.ZERO : now.subtract(lastPos);
        lastPos = now;
        if (delta.lengthSquared() > 16) {
            // Teleport-sized correction is not chosen movement: re-anchor and adopt the next sync.
            SESSION.reset(); predictedQ = q; predictedBasis = basis;
            lastSignature = current;
            ClientSpiritCache.updateObserver(q, basis);
            return;
        }
        if (lastEpoch == nav.motionEpoch() && lastDeep == nav.deep() && nav.semanticReady()) {
            if (nav.deep() && nav.supportApproach()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
            else if (nav.deep() && nav.landingApproach()) TraversalSteering.approachStep(q, target, delta.x, delta.y, delta.z);
            else if (nav.deep()) TraversalSteering.deepStep(q, basis, target, delta.x, delta.y, delta.z, nav.hasShallowTarget());
            else if (!nav.landmarkId().isEmpty()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
        }
        lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
        ClientSpiritCache.updateObserver(q, basis);
    }
}
