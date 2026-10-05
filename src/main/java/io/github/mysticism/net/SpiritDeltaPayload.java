package io.github.mysticism.net;

import io.github.mysticism.vector.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.*;

/** Protocol 2: profile fingerprint and dimensions precede any vector-bearing data. */
public record SpiritDeltaPayload(List<Added> add,List<String> remove) implements CustomPayload {
    private static final int MAX_ENTRIES=4096;
    public SpiritDeltaPayload {add=List.copyOf(add);remove=List.copyOf(remove);count(add.size());count(remove.size());}
    public static final Id<SpiritDeltaPayload> ID=new Id<>(Identifier.of("mysticism","spirit/visible_delta_v2"));
    private static int count(int count){if(count<0||count>MAX_ENTRIES)throw new IllegalArgumentException("Invalid delta count");return count;}
    public static final PacketCodec<RegistryByteBuf,SpiritDeltaPayload> CODEC=PacketCodec.of((payload,buf)->{
        buf.writeVarInt(EmbeddingSpace.SCHEMA);buf.writeString(EmbeddingSpace.FINGERPRINT);buf.writeVarInt(EmbeddingSpace.DIMENSIONS);
        buf.writeVarInt(payload.add.size());for(Added a:payload.add)Added.CODEC.encode(buf,a);
        buf.writeVarInt(payload.remove.size());for(String id:payload.remove)buf.writeString(id);
    },buf->{
        if(buf.readVarInt()!=EmbeddingSpace.SCHEMA||!EmbeddingSpace.FINGERPRINT.equals(buf.readString(64))||buf.readVarInt()!=EmbeddingSpace.DIMENSIONS)throw new IllegalArgumentException("Incompatible spirit embedding protocol/profile");
        int n=count(buf.readVarInt());List<Added> additions=new ArrayList<>(n);for(int i=0;i<n;i++)additions.add(Added.CODEC.decode(buf));
        n=count(buf.readVarInt());List<String> removals=new ArrayList<>(n);for(int i=0;i<n;i++)removals.add(buf.readString());
        return new SpiritDeltaPayload(additions,removals);
    });
    @Override public Id<? extends CustomPayload> getId(){return ID;}
    public record Added(String id,int[] bits){
        public Added{Objects.requireNonNull(id);Vec384f.fromBits(bits);bits=bits.clone();}
        @Override public int[] bits(){return bits.clone();}
        public static final PacketCodec<RegistryByteBuf,Added> CODEC=PacketCodec.of((added,buf)->{
            buf.writeString(added.id);buf.writeVarInt(EmbeddingSpace.DIMENSIONS);for(int v:added.bits)buf.writeInt(v);
        },buf->{
            String id=buf.readString();if(buf.readVarInt()!=EmbeddingSpace.DIMENSIONS)throw new IllegalArgumentException("Invalid vector dimension");
            int[] bits=new int[EmbeddingSpace.DIMENSIONS];for(int i=0;i<bits.length;i++)bits[i]=buf.readInt();return new Added(id,bits);
        });
        public static Added of(String id,Vec384f vector){EmbeddingSpace.requireCurrent(vector);return new Added(id,vector.toBits());}
    }
}
