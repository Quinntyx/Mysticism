package io.github.mysticism.net;

import io.github.mysticism.dimension.spiritworld.SpiritGlyphSelection;
import io.github.mysticism.landmark.Point3;
import io.github.mysticism.vector.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.*;

/** Semantic membership/slots ONLY. Every observer projects through CURRENT q/basis/head. */
public record SpiritDeltaPayload(SpiritFramePayload.Session session,long sequence,List<Added> add,List<String> remove) implements CustomPayload {
    public static final int MAX_ENTRIES=128,MAX_ID_CHARS=256,MAX_ENCODED_BYTES=950_000;
    public static final Id<SpiritDeltaPayload> ID=new Id<>(Identifier.of("mysticism","spirit/glyphs_v4"));
    public SpiritDeltaPayload(List<Added> add,List<String> remove){this(null,0,add,remove);}
    public SpiritDeltaPayload{if(sequence<0||session!=null&&sequence==0)throw new IllegalArgumentException("Glyph sequence");add=List.copyOf(add);remove=List.copyOf(remove);count(add.size());count(remove.size());remove.forEach(SpiritDeltaPayload::validateId);}
    private static int count(int n){if(n<0||n>MAX_ENTRIES)throw new IllegalArgumentException("Glyph budget");return n;}
    static void validateId(String id){Objects.requireNonNull(id);if(id.isBlank()||id.length()>MAX_ID_CHARS)throw new IllegalArgumentException("Semantic ID");for(int i=0;i<id.length();i++){char c=id.charAt(i);if(Character.isHighSurrogate(c)){if(++i==id.length()||!Character.isLowSurrogate(id.charAt(i)))throw new IllegalArgumentException("Semantic ID Unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Semantic ID Unicode");}}
    public int encodedBytes(){return 1024+add.size()*(EmbeddingSpace.DIMENSIONS*4+1600)+remove.size()*800;}
    public static List<SpiritDeltaPayload> batches(List<Added> add,List<String> remove){return List.of(new SpiritDeltaPayload(add,remove));}
    public static List<SpiritDeltaPayload> batches(SpiritFramePayload.Session session,long sequence,List<Added> add,List<String> remove){return List.of(new SpiritDeltaPayload(session,sequence,add,remove));}
    public record Added(String id,int[] bits,String clusterId,int clusterSlot,int slot){
        public Added{validateId(id);validateId(clusterId);Vec384f.fromBits(bits);bits=bits.clone();if(clusterSlot<0||clusterSlot>=32||slot<0||slot>=16)throw new IllegalArgumentException("Glyph slot");}
        public Added(String id,int[] bits){this(id,bits,id,0,0);}
        /** Obsolete source callers compile, but coordinates are discarded, never transmitted. */
        @Deprecated public Added(String id,int[] bits,Point3 ignored){this(id,bits);}
        @Deprecated public Point3 position(){return null;}
        @Override public int[] bits(){return bits.clone();}
        public static Added of(String id,Vec384f v){return new Added(id,v.toBits());}
        public static Added glyph(SpiritGlyphSelection.Glyph g){return new Added(g.id(),g.embedding().toBits(),g.clusterId(),g.clusterSlot(),g.slot());}
        public SpiritGlyphSelection.Glyph glyph(){return new SpiritGlyphSelection.Glyph(id,clusterId,clusterSlot,slot,Vec384f.fromBits(bits));}
        public static final PacketCodec<RegistryByteBuf,Added> CODEC=PacketCodec.of((p,b)->{b.writeString(p.id(),256);for(int v:p.bits)b.writeInt(v);b.writeString(p.clusterId(),256);b.writeVarInt(p.clusterSlot());b.writeVarInt(p.slot());},b->{String id=b.readString(256);int[] bits=new int[EmbeddingSpace.DIMENSIONS];for(int i=0;i<bits.length;i++)bits[i]=b.readInt();return new Added(id,bits,b.readString(256),b.readVarInt(),b.readVarInt());});
    }
    public static final PacketCodec<RegistryByteBuf,SpiritDeltaPayload> CODEC=PacketCodec.of((p,b)->{b.writeBoolean(p.session()!=null);if(p.session()!=null)SpiritFramePayload.Session.encode(b,p.session());b.writeLong(p.sequence());b.writeVarInt(EmbeddingSpace.DIMENSIONS);b.writeString(EmbeddingSpace.FINGERPRINT,64);b.writeVarInt(p.add().size());for(Added a:p.add())Added.CODEC.encode(b,a);b.writeVarInt(p.remove().size());for(String id:p.remove())b.writeString(id,256);},b->{
        if(b.readableBytes()>MAX_ENCODED_BYTES)throw new IllegalArgumentException("Glyph packet budget");var session=b.readBoolean()?SpiritFramePayload.Session.decode(b):null;long sequence=b.readLong();if(b.readVarInt()!=EmbeddingSpace.DIMENSIONS||!EmbeddingSpace.FINGERPRINT.equals(b.readString(64)))throw new IllegalArgumentException("Glyph profile");int n=count(b.readVarInt());var add=new ArrayList<Added>(n);for(int i=0;i<n;i++)add.add(Added.CODEC.decode(b));n=count(b.readVarInt());var remove=new ArrayList<String>(n);for(int i=0;i<n;i++)remove.add(b.readString(256));return new SpiritDeltaPayload(session,sequence,add,remove);
    });
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
