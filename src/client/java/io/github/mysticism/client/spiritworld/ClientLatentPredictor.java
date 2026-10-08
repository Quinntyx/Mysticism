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
            // Acquisition/anchor corrections (even <4 blocks) are NOT chosen movement.
            boolean continuous = lastPlayer == mc.player && lastWorld == mc.world && lastPos != null
                    && lastEpoch == nav.motionEpoch() && lastDeep == nav.deep() && nav.semanticReady();
            Vec3d delta = continuous ? now.subtract(lastPos) : Vec3d.ZERO;
            if (delta.lengthSquared() > 16) delta = Vec3d.ZERO;
            // Roll render history, then publish the authoritative mirror. A sync that arrives while
            // moving is kept render-continuous (see ClientSpiritCache.updateObserver) instead of
            // snapping every projected glyph/peer; navigation transitions still snap by design.
            ClientSpiritCache.beginTick();
            ClientSpiritCache.updateObserver(q, basis, delta);
            // Predicted render-frame advance between authoritative syncs. Components are never
            // mutated client-side, so a later sync replaces only the mirror and the continuity
            // offset compensates the divergence, keeping motion continuous while moving.
            if (delta.lengthSquared() > 0) {
                var renderPos = ClientSpiritCache.playerLatentPos;
                var renderBasis = ClientSpiritCache.playerLatentBasis;
                if (nav.deep() && nav.supportApproach()) TraversalSteering.advance(renderPos, renderBasis, delta.x, delta.y, delta.z);
                else if (nav.deep() && nav.landingApproach()) TraversalSteering.approachStep(renderPos, target, delta.x, delta.y, delta.z);
                else if (nav.deep()) TraversalSteering.deepStep(renderPos, renderBasis, target, delta.x, delta.y, delta.z, nav.hasShallowTarget());
                else if (!nav.landmarkId().isEmpty()) TraversalSteering.advance(renderPos, renderBasis, delta.x, delta.y, delta.z);
            }
            lastPos = now; lastPlayer = mc.player; lastWorld = mc.world;
            lastEpoch = nav.motionEpoch(); lastDeep = nav.deep();
        } else {
            clear();
            // Refresh even at rest: touch blends and authoritative vector/profile updates are not movement.
            ClientSpiritCache.updateObserver(q, basis, Vec3d.ZERO);
        }
    }
}
