package io.github.mysticism.client.spiritworld;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.BasisIntegrator384f;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Client-side prediction for Spirit world:
 *  - evolve basis with a small plane rotation (stable near alignment)
 *  - evolve latent position with your "converge 1% per block" rule
 *
 * This keeps Δ = (obj - you) changing locally each frame, so glyphs no longer "stick to your head"
 * when you set pos == obj and start walking.
 */
@Environment(EnvType.CLIENT)
public final class ClientLatentPredictor {
    private ClientLatentPredictor() {}

    // Tune: percentage of converge toward attunement per block walked
    private static final float POS_LERP_PER_BLOCK = 0.01f;
    // Tune: small angle rotation strength per block for basis
    private static final float BASIS_ROTATION_PER_BLOCK = 0.05f;

    private static Vec3d lastPos; // world movement bookkeeping

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientLatentPredictor::onEndTick);
    }

    private static void onEndTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        if (!mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit"))) {
            lastPos = null;
            return;
        }

        Vec3d now = mc.player.getPos();
        if (lastPos == null) { lastPos = now; return; }
        double dx = now.x - lastPos.x, dy = now.y - lastPos.y, dz = now.z - lastPos.z;
        lastPos = now;

        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dist < 1e-8) return;

        // Get the live, mutable component instances
        Basis384f basis = mc.player.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        Vec384f    pos  = mc.player.getComponent(MysticismEntityComponents.LATENT_POS).get();
        Vec384f    att  = mc.player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).get();

        // Perform mutation on the live components
        BasisIntegrator384f.step(basis, att, dx, dy, dz, BASIS_ROTATION_PER_BLOCK);
        float f = (float)(POS_LERP_PER_BLOCK * dist);
        if (f > 0f) pos.converge(att, f);

        // --- THE SOLUTION ---
        // Mirror IMMUTABLE SNAPSHOTS into your client cache for the renderer.
        // The .clone() method creates a new object with a copy of the data at this exact moment.
        // The renderer will now have a stable, thread-safe copy to work with for the entire frame.
        ClientSpiritCache.playerLatentBasis = basis.clone();
        ClientSpiritCache.playerLatentPos   = pos.clone();
        ClientSpiritCache.playerLatentAttunement = att.clone();
    }
}
