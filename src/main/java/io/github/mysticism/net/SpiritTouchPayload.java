package io.github.mysticism.net;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;
/** Target only: actor always comes from the authenticated play connection. */
public record SpiritTouchPayload(UUID target) implements CustomPayload {
    public static final Id<SpiritTouchPayload> ID=new Id<>(Identifier.of("mysticism","spirit/touch_v1"));
    public static final PacketCodec<RegistryByteBuf,SpiritTouchPayload> CODEC=PacketCodec.of((p,b)->b.writeUuid(p.target()),b->new SpiritTouchPayload(b.readUuid()));
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
