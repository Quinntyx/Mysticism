package io.github.mysticism.net;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;
import java.util.Objects;
/** Client declaration that its committed terrain no longer matches the delta stream base.
 * The server must republish the pending revision as a full frame instead of continuing deltas. */
public record SpiritTerrainSyncPayload(UUID connectionNonce,long epoch) implements CustomPayload {
    public SpiritTerrainSyncPayload {Objects.requireNonNull(connectionNonce);if(epoch<=0)throw new IllegalArgumentException("Terrain session epoch");}
    public static final Id<SpiritTerrainSyncPayload> ID=new Id<>(Identifier.of("mysticism","spirit/terrain_sync_v1"));
    public static final PacketCodec<RegistryByteBuf,SpiritTerrainSyncPayload> CODEC=PacketCodec.of((p,b)->{b.writeUuid(p.connectionNonce());b.writeLong(p.epoch());},b->new SpiritTerrainSyncPayload(b.readUuid(),b.readLong()));
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
