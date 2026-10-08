package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.navigation.MovementIntegration;
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

/** Same per-player movement math as server; stationary CCA touch corrections still refresh render state.
 *  Prediction integrates only chosen movement: server position corrections and our own depenetration
 *  response are excluded by provenance, so prediction tracks the server instead of rubber-banding back
 *  to every authoritative sync. */
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
        ClientSpiritCache.updateNavigation(nav.active(), nav.deep(), nav.sourceDimension(), nav.sourcePosition());
        var basis = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        var q = mc.player.getComponent(MysticismEntityComponents.LATENT_POS).get();
        var target = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit")) && nav.active()) {
            // Drain provenance every spirit tick; stale markers can never skip a later window.
            boolean repositioned = MovementProvenance.drainClientRepositioned(mc.player.getUuid());
            Vec3d correction = MeshCollision.drainCorrection(mc.player);
            Vec3d now = mc.player.getPos();
            // Acquisition/anchor corrections (even <4 blocks) are NOT chosen movement.
            if (lastPlayer == mc.player && lastWorld == mc.world && lastPos != null
                    && lastEpoch == nav.motionEpoch() && lastDeep == nav.deep() && nav.semanticReady()) {
                Vec3d delta = MovementIntegration.clientSemantic(now.subtract(lastPos), correction, repositioned);
                if (nav.deep() && nav.supportApproach()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
                else if (nav.deep() && nav.landingApproach()) TraversalSteering.approachStep(q, target, delta.x, delta.y, delta.z);
                else if (nav.deep()) TraversalSteering.deepStep(q, basis, target, delta.x, delta.y, delta.z, nav.hasShallowTarget());
                else if (!nav.landmarkId().isEmpty()) TraversalSteering.advance(q, basis, delta.x, delta.y, delta.z);
            } // Window re-based: the just-measured displacement spans a mode/anchor change, not chosen travel;
            //   provenance was already drained above, so nothing stale carries into the next window.
            lastPos = now; lastPlayer = mc.player; lastWorld = mc.world;
            lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
        } else clear();
        // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
        ClientSpiritCache.updateObserver(q, basis);
    }
}
