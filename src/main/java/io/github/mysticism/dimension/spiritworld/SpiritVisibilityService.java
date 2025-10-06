package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.net.SpiritDeltaPayload;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.*;

public final class SpiritVisibilityService {
    private SpiritVisibilityService() {}

    private static final Map<UUID, Set<String>> LAST = new HashMap<>();

    public static boolean isSpiritWorld(ServerPlayerEntity p) {
        return p.getWorld().getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit"));
    }

    public static void tick(MinecraftServer server, int k) {
        var mgr = server.getPlayerManager();
        for (ServerPlayerEntity p : mgr.getPlayerList()) {
            if (!isSpiritWorld(p)) continue;

            var state = ItemEmbeddingIndexState.get(server);
            var q = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
            // Adapt to your KnnIndex API if needed:
            // assuming: List<String> ids = state.getIndex().knn(q, k);
            var ids = state.nearestIds(k, q);

            var prev = LAST.computeIfAbsent(p.getUuid(), u -> new HashSet<>());
            var current = new HashSet<>(ids);

            var addIds  = new ArrayList<String>();
            var addVecs = new ArrayList<Vec384f>();
            var remIds  = new ArrayList<String>();

            for (String id : current) if (!prev.contains(id)) {
                addIds.add(id);
                addVecs.add(state.getIndex().get(id)); // require a getter; add it if missing
            }
            for (String id : prev) if (!current.contains(id)) remIds.add(id);

            if (!addIds.isEmpty() || !remIds.isEmpty()) {
                var added = new ArrayList<SpiritDeltaPayload.Added>(addIds.size());
                for (int i = 0; i < addIds.size(); i++) {
                    added.add(SpiritDeltaPayload.Added.of(addIds.get(i), addVecs.get(i)));
                }
                ServerPlayNetworking.send(p, new SpiritDeltaPayload(added, remIds));
                prev.clear(); prev.addAll(current);
            }
        }
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            tick(server, 1643);
        });
    }
}
