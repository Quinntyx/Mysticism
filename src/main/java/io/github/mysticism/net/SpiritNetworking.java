package io.github.mysticism.net;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class SpiritNetworking {
    public static void init() {
        PayloadTypeRegistry.playS2C().register(SpiritDeltaPayload.ID, SpiritDeltaPayload.CODEC);
        // (Register C2S payloads here if you add them later)
    }
}
