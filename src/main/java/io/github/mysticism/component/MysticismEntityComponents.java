// io/github/mysticism/component/LatentComponents.java
package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import io.github.mysticism.Mysticism;
import io.github.mysticism.entity.LatentGlyphEntity;

public final class MysticismEntityComponents implements EntityComponentInitializer {
    public static final ComponentKey<LatentPos>   LATENT_POS   =
            ComponentRegistry.getOrCreate(Identifier.of(Mysticism.MOD_ID, "latent_pos"), LatentPos.class);
    public static final ComponentKey<LatentBasis> LATENT_BASIS =
            ComponentRegistry.getOrCreate(Identifier.of(Mysticism.MOD_ID, "latent_basis"), LatentBasis.class);
    public static final ComponentKey<LatentAttunement> LATENT_ATTUNEMENT =
            ComponentRegistry.getOrCreate(Identifier.of(Mysticism.MOD_ID, "latent_attunement"), LatentAttunement.class);


    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        // Players: persist + copy on respawn
        registry.registerForPlayers(LATENT_POS,   p -> new LatentPos(),   RespawnCopyStrategy.ALWAYS_COPY);
        registry.registerForPlayers(LATENT_BASIS, p -> new LatentBasis(), RespawnCopyStrategy.ALWAYS_COPY);
        registry.registerForPlayers(LATENT_ATTUNEMENT, p -> new LatentAttunement(), RespawnCopyStrategy.ALWAYS_COPY);

        // Your custom glyph/entity can carry a latent vector too (no respawn semantics needed)
        registry.registerFor(LatentGlyphEntity.class, LATENT_POS, e -> new LatentPos());
    }

    // Convenience helpers
    public static void setLatentPos(ServerPlayerEntity player, Vec384f vec384) {
        player.getComponent(LATENT_POS).set(vec384);
        LATENT_POS.sync(player);
    }
    public static Vec384f getLatentPos(ServerPlayerEntity player) {
        return player.getComponent(LATENT_POS).get();
    }

    public static void setLatentAttunement(ServerPlayerEntity player, Vec384f vec384) {
        player.getComponent(LATENT_ATTUNEMENT).set(vec384);
        LATENT_ATTUNEMENT.sync(player);
    }

    public static Vec384f getLatentAttunement(ServerPlayerEntity player) {
        return player.getComponent(LATENT_ATTUNEMENT).get();
    }
}
