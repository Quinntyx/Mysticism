package io.github.mysticism.client.net;

import io.github.mysticism.client.spiritworld.ClientSpiritCache;
import io.github.mysticism.net.SpiritDeltaPayload;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public final class SpiritNetworkingClient {
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SpiritDeltaPayload.ID, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                // add
                payload.add().forEach(a -> {
                    ClientSpiritCache.VEC.put(a.id(), Vec384f.fromBits(a.bits()));
                    ClientSpiritCache.VISIBLE.add(a.id());
                });
                // remove
                payload.remove().forEach(ClientSpiritCache.VISIBLE::remove);
            });
        });
    }
}
