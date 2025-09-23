package io.github.mysticism.client.spiritworld;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.vector.BasisIntegrator384f;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.vector.Basis384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class ClientSpiritCache {
    /** All embeddings we’ve ever received from the server (id → vector). */
    public static final Map<String, Vec384f> VEC = new HashMap<>();

    /** Subset currently marked visible by the server. */
    public static final HashSet<String> VISIBLE = new HashSet<>();


    /** Target basis we’re easing toward (when attunement changes). */
    public static Basis384f target = new Basis384f();

    /** Mirrors of CCA-synced fields for use in rendering so we don't need to query the player object as much */
    public static Basis384f playerLatentBasis = new Basis384f();
    public static Vec384f playerLatentPos = Vec384f.ZERO();
    public static Vec384f playerLatentAttunement = Vec384f.ZERO();

    /** Last position of player, used for calculating changes in movement to update basis */
    private static Vec3d lastPos = null;

    private ClientSpiritCache() {}
}
