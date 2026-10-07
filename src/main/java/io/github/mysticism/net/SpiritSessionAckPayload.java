package io.github.mysticism.net;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;
/** Confirms bootstrap survived client world/player replacement before membership publication. */
public record SpiritSessionAckPayload(UUID connectionNonce,long epoch) implements CustomPayload {
    public static final Id<SpiritSessionAckPayload> ID=new Id<>(Identifier.of("mysticism","spirit/session_ack_v1"));
    public static final PacketCodec<RegistryByteBuf,SpiritSessionAckPayload> CODEC=PacketCodec.of((p,b)->{b.writeUuid(p.connectionNonce());b.writeLong(p.epoch());},b->new SpiritSessionAckPayload(b.readUuid(),b.readLong()));
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
