package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.vector.BasisIntegrator384f;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpiritBasisEvolver {
    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();

    private static boolean isSpiritWorld(ServerPlayerEntity p) {
        return p.getWorld().getRegistryKey().getValue().equals(Identifier.of("mysticism","spirit"));
    }

    private static void evolveBasis(MinecraftServer server, float eta) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!isSpiritWorld(p)) {
                LAST_POS.remove(p.getUuid());
                continue;
            }
            Vec3d now = p.getPos();
            Vec3d last = LAST_POS.put(p.getUuid(), now);
            if (last == null) continue;

            double dx = now.x - last.x, dy = now.y - last.y, dz = now.z - last.z;
            var basis = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
            var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).get();

            boolean changed = BasisIntegrator384f.step(basis, att, dx, dy, dz, eta);
            // Optional drift confirmation every 2 seconds:
            if (changed && (server.getTicks() % 40 == 0)) {
                MysticismEntityComponents.LATENT_BASIS.sync(p);
            }
        }
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            // TODO: tune proper eta based on visual appearance, make sure to keep same client/server
            evolveBasis(server, 0.05f);
        });
    }
}
