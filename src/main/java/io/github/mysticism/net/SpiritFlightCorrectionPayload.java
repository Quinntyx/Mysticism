package io.github.mysticism.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.Objects;
import java.util.UUID;

/** Ordered flight-correction barrier. S2C follows vanilla abilities; C2S echoes only after receipt.
 * Not a flight gesture, destination request or projection-session bootstrap. */
public record SpiritFlightCorrectionPayload(UUID token) implements CustomPayload {
    public SpiritFlightCorrectionPayload { Objects.requireNonNull(token); }
    public static final Id<SpiritFlightCorrectionPayload> ID =
            new Id<>(Identifier.of("mysticism", "spirit/flight_correction_v1"));
    public static final PacketCodec<RegistryByteBuf, SpiritFlightCorrectionPayload> CODEC =
            PacketCodec.of((p, b) -> b.writeUuid(p.token()), b -> new SpiritFlightCorrectionPayload(b.readUuid()));
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
