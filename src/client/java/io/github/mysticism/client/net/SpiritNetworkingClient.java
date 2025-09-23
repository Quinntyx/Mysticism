package io.github.mysticism.client.net;

import io.github.mysticism.client.spiritworld.ClientSpiritCache;
import io.github.mysticism.net.SpiritDeltaPayload;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public final class SpiritNetworkingClient {
    public static Logger LOGGER = LoggerFactory.getLogger("MysticismClient-SpiritNetworking");

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SpiritDeltaPayload.ID, (payload, context) -> {
            var client = context.client();
            client.execute(() -> {
                // add
                payload.add().forEach(a -> {
                    ClientSpiritCache.VEC.put(a.id(), Vec384f.fromBits(a.bits()));
                    ClientSpiritCache.VISIBLE.add(a.id());
                    LOGGER.info("Length of {}: {}", a.id(), Vec384f.fromBits(a.bits()).length());
                });
                // remove
                payload.remove().forEach(ClientSpiritCache.VISIBLE::remove);
            });
        });
    }
}
