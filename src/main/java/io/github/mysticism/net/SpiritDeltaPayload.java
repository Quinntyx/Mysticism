package io.github.mysticism.net;

import io.github.mysticism.vector.*;
import io.github.mysticism.landmark.Point3;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Protocol 3: session/sequence + explicit absolute placement. Legacy constructors fail closed client-side. */
public record SpiritDeltaPayload(SpiritFramePayload.Session session,long sequence,List<Added> add,List<String> remove) implements CustomPayload {
    public static final int MAX_ENTRIES=4096, MAX_ID_CHARS=256, MAX_ENCODED_BYTES=1_048_000;
    public static final Id<SpiritDeltaPayload> ID=new Id<>(Identifier.of("mysticism","spirit/visible_delta_v3"));
    public SpiritDeltaPayload(List<Added> add,List<String> remove) { this(null,0,add,remove); }
    public SpiritDeltaPayload {
        if(sequence<0 || session!=null && sequence==0)throw new IllegalArgumentException("Invalid delta sequence");
        add=List.copyOf(add); remove=List.copyOf(remove); count(add.size()); count(remove.size());
        for(String id:remove)validateId(id);
        if(session!=null)for(Added a:add)if(a.position==null)throw new IllegalArgumentException("Missing authoritative placement");
        if(bytes(session,add,remove)>MAX_ENCODED_BYTES)throw new IllegalArgumentException("Spirit delta exceeds transport byte budget; use batches");
    }
    private static int count(int n) { if(n<0 || n>MAX_ENTRIES)throw new IllegalArgumentException("Invalid delta count"); return n; }
    static void validateId(String id) {
        Objects.requireNonNull(id); if(id.isEmpty() || id.length()>MAX_ID_CHARS)throw new IllegalArgumentException("Invalid spirit ID length");
        for(int i=0;i<id.length();i++){char c=id.charAt(i); if(Character.isHighSurrogate(c)){if(++i==id.length() || !Character.isLowSurrogate(id.charAt(i)))throw new IllegalArgumentException("Malformed spirit ID Unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Malformed spirit ID Unicode");}
    }
    private static int varIntBytes(int n) { int size=1; while((n&~0x7f)!=0){n>>>=7;size++;}return size; }
    private static int stringBytes(String s) { int n=s.getBytes(StandardCharsets.UTF_8).length;return varIntBytes(n)+n; }
    private static int additionBytes(Added a) { return stringBytes(a.id)+varIntBytes(EmbeddingSpace.DIMENSIONS)+4*EmbeddingSpace.DIMENSIONS+1+(a.position==null?0:24); }
    private static int header(SpiritFramePayload.Session s) { return 9+(s==null?0:s.bytes())+varIntBytes(EmbeddingSpace.SCHEMA)+stringBytes(EmbeddingSpace.FINGERPRINT)+varIntBytes(EmbeddingSpace.DIMENSIONS); }
    private static int bytes(SpiritFramePayload.Session s,List<Added> add,List<String> remove) { int n=header(s)+varIntBytes(add.size())+varIntBytes(remove.size());for(Added a:add)n+=additionBytes(a);for(String id:remove)n+=stringBytes(id);return n; }
    public int encodedBytes() { return bytes(session,add,remove); }
    /** Legacy batching preserved for source compatibility; not accepted for rendering. */
    public static List<SpiritDeltaPayload> batches(List<Added> additions,List<String> removals) {
        var packets=new ArrayList<SpiritDeltaPayload>(); var ap=new ArrayList<Added>(); var rp=new ArrayList<String>();
        int base=header(null)+2*varIntBytes(MAX_ENTRIES),size=base;
        for(Added a:List.copyOf(additions)) { int n=additionBytes(a); if(ap.size()==MAX_ENTRIES || size+n>MAX_ENCODED_BYTES){packets.add(new SpiritDeltaPayload(ap,rp));ap.clear();rp.clear();size=base;}ap.add(a);size+=n; }
        for(String id:List.copyOf(removals)) {validateId(id);int n=stringBytes(id);if(rp.size()==MAX_ENTRIES || size+n>MAX_ENCODED_BYTES){packets.add(new SpiritDeltaPayload(ap,rp));ap.clear();rp.clear();size=base;}rp.add(id);size+=n;}
        if(!ap.isEmpty() || !rp.isEmpty())packets.add(new SpiritDeltaPayload(ap,rp));return List.copyOf(packets);
    }
    public static List<SpiritDeltaPayload> batches(SpiritFramePayload.Session session,long sequence,List<Added> add,List<String> remove) {
        if(add.size()>128 || remove.size()>128)throw new IllegalArgumentException("Active glyph delta budget");
        return List.of(new SpiritDeltaPayload(Objects.requireNonNull(session),sequence,add,remove));
    }
    public static final PacketCodec<RegistryByteBuf,SpiritDeltaPayload> CODEC=PacketCodec.of((p,b)->{
        b.writeBoolean(p.session!=null);if(p.session!=null)SpiritFramePayload.Session.encode(b,p.session);b.writeLong(p.sequence);
        b.writeVarInt(EmbeddingSpace.SCHEMA);b.writeString(EmbeddingSpace.FINGERPRINT);b.writeVarInt(EmbeddingSpace.DIMENSIONS);
        b.writeVarInt(p.add.size());for(Added a:p.add)Added.CODEC.encode(b,a);b.writeVarInt(p.remove.size());for(String id:p.remove)b.writeString(id,MAX_ID_CHARS);
    },b->{
        int start=b.readerIndex();if(b.readableBytes()>MAX_ENCODED_BYTES)throw new IllegalArgumentException("Oversized spirit delta");
        var session=b.readBoolean()?SpiritFramePayload.Session.decode(b):null;long sequence=b.readLong();
        if(b.readVarInt()!=EmbeddingSpace.SCHEMA || !EmbeddingSpace.FINGERPRINT.equals(b.readString(64)) || b.readVarInt()!=EmbeddingSpace.DIMENSIONS)throw new IllegalArgumentException("Incompatible spirit embedding protocol/profile");
        int n=count(b.readVarInt());var add=new ArrayList<Added>(n);for(int i=0;i<n;i++)add.add(Added.CODEC.decode(b));
        n=count(b.readVarInt());var remove=new ArrayList<String>(n);for(int i=0;i<n;i++)remove.add(b.readString(MAX_ID_CHARS));
        if(b.readerIndex()-start>MAX_ENCODED_BYTES)throw new IllegalArgumentException("Oversized spirit delta");return new SpiritDeltaPayload(session,sequence,add,remove);
    });
    @Override public Id<? extends CustomPayload> getId() { return ID; }
    public record Added(String id,int[] bits,Point3 position) {
        public Added(String id,int[] bits){this(id,bits,null);}
        public Added { validateId(id);Vec384f.fromBits(bits);bits=bits.clone();if(position!=null)SpiritProjectionState.validatePosition(position); }
        @Override public int[] bits(){return bits.clone();}
        public static Added of(String id,Vec384f vector){EmbeddingSpace.requireCurrent(vector);return new Added(id,vector.toBits());}
        public static final PacketCodec<RegistryByteBuf,Added> CODEC=PacketCodec.of((a,b)->{
            b.writeString(a.id,MAX_ID_CHARS);b.writeVarInt(EmbeddingSpace.DIMENSIONS);for(int v:a.bits)b.writeInt(v);b.writeBoolean(a.position!=null);if(a.position!=null){b.writeDouble(a.position.x());b.writeDouble(a.position.y());b.writeDouble(a.position.z());}
        },b->{String id=b.readString(MAX_ID_CHARS);if(b.readVarInt()!=EmbeddingSpace.DIMENSIONS)throw new IllegalArgumentException("Invalid vector dimension");int[] bits=new int[EmbeddingSpace.DIMENSIONS];for(int i=0;i<bits.length;i++)bits[i]=b.readInt();return new Added(id,bits,b.readBoolean()?new Point3(b.readDouble(),b.readDouble(),b.readDouble()):null);});
    }
}
