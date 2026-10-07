package io.github.mysticism.net;

import io.github.mysticism.vector.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.codec.PacketCodecs;
import com.mojang.authlib.GameProfile;
import java.util.*;

/** Per-observer semantic scene. Epoch authenticates transport, never geometry or a world origin. */
public record SpiritScenePayload(SpiritFramePayload.Session session,long sequence,boolean deep,boolean sourceObserved,Binding binding,
        List<Peer> peers,List<Ghost> ghosts,List<Mesh> meshes,List<Drop> drops) implements CustomPayload {
    public static final Id<SpiritScenePayload> ID=new Id<>(Identifier.of("mysticism","spirit/scene_v1"));
    public static final int MAX_PEERS=16,MAX_GHOSTS=32,MAX_MESHES=4,MAX_CELLS=4096,MAX_DROPS=32;
    public SpiritScenePayload {
        Objects.requireNonNull(session);if(sequence<1)throw new IllegalArgumentException("Scene sequence");
        peers=List.copyOf(peers);ghosts=List.copyOf(ghosts);meshes=List.copyOf(meshes);drops=List.copyOf(drops);
        count(peers.size(),MAX_PEERS);count(ghosts.size(),MAX_GHOSTS);count(meshes.size(),MAX_MESHES);count(drops.size(),MAX_DROPS);
        count(meshes.stream().mapToInt(m->m.cells().size()).sum(),MAX_CELLS);
    }
    private static int count(int n,int max){if(n<0||n>max)throw new IllegalArgumentException("Scene resource budget");return n;}
    private static String id(String s){SpiritDeltaPayload.validateId(s);return s;}
    private static Vec3d point(Vec3d v){Objects.requireNonNull(v);if(!Double.isFinite(v.x)||!Double.isFinite(v.y)||!Double.isFinite(v.z)||Math.abs(v.x)>30_000_000||Math.abs(v.z)>30_000_000||Math.abs(v.y)>4096)throw new IllegalArgumentException("Scene point");return v;}
    private static float finite(float v){if(!Float.isFinite(v))throw new IllegalArgumentException("Scene float");return v;}
    public record Binding(String dimension,String landmarkId,Vec3d sourcePosition,Vec384f captured){
        public Binding{id(dimension);if(!landmarkId.isEmpty())id(landmarkId);point(sourcePosition);EmbeddingSpace.requireCurrent(captured);captured=captured.clone();}
        @Override public Vec384f captured(){return captured.clone();}
    }
    public record Peer(UUID id,Vec384f q,Basis384f basis,boolean deep,Binding binding,float yaw,float pitch,Ghost appearance){
        public Peer{Objects.requireNonNull(id);EmbeddingSpace.requireCurrent(q);q=q.clone();basis=basis.clone();finite(yaw);finite(pitch);Objects.requireNonNull(appearance);if(!appearance.id().equals(id))throw new IllegalArgumentException("Peer appearance identity");}
        @Override public Vec384f q(){return q.clone();}@Override public Basis384f basis(){return basis.clone();}
    }
    public record Equipment(EquipmentSlot slot,ItemStack stack){public Equipment{Objects.requireNonNull(slot);stack=SpiritItemAppearance.copy(stack);}@Override public ItemStack stack(){return stack.copy();}}
    private static <T> DataTracker.SerializedEntry<T> copyEntry(DataTracker.SerializedEntry<T> e){T value=e.value();if(value instanceof ItemStack stack){@SuppressWarnings("unchecked") T visual=(T)SpiritItemAppearance.copy(stack);value=visual;}return new DataTracker.SerializedEntry<>(e.id(),e.handler(),e.handler().copy(value));}
    public record Ghost(UUID id,int entityId,String type,String landmarkId,Vec3d sourcePosition,String pose,float yaw,float pitch,float bodyYaw,float headYaw,float width,float height,GameProfile profile,List<DataTracker.SerializedEntry<?>> tracked,List<Equipment> equipment){
        public Ghost{Objects.requireNonNull(id);SpiritScenePayload.id(type);if(!landmarkId.isEmpty())SpiritScenePayload.id(landmarkId);point(sourcePosition);net.minecraft.entity.EntityPose.valueOf(pose);finite(yaw);finite(pitch);finite(bodyYaw);finite(headYaw);if(finite(width)<0||finite(height)<0||width>32||height>32)throw new IllegalArgumentException("Ghost size");profile=SpiritItemAppearance.profile(profile);count(tracked.size(),96);count(equipment.size(),8);var copied=new ArrayList<DataTracker.SerializedEntry<?>>();for(var entry:tracked)copied.add(copyEntry(entry));tracked=List.copyOf(copied);equipment=List.copyOf(equipment);}
        @Override public GameProfile profile(){return SpiritItemAppearance.profile(profile);}
        public static final PacketCodec<RegistryByteBuf,Ghost> CODEC=PacketCodec.of((v,b)->{b.writeUuid(v.id());b.writeVarInt(v.entityId());b.writeString(v.type(),256);b.writeString(v.landmarkId(),256);point(b,v.sourcePosition());b.writeString(v.pose(),64);b.writeFloat(v.yaw());b.writeFloat(v.pitch());b.writeFloat(v.bodyYaw());b.writeFloat(v.headYaw());b.writeFloat(v.width());b.writeFloat(v.height());b.writeBoolean(v.profile()!=null);if(v.profile()!=null)PacketCodecs.GAME_PROFILE.encode(b,v.profile());b.writeVarInt(v.tracked().size());v.tracked().forEach(e->e.write(b));b.writeVarInt(v.equipment().size());for(var e:v.equipment()){b.writeVarInt(e.slot().ordinal());ItemStack.OPTIONAL_PACKET_CODEC.encode(b,e.stack());}},b->{UUID id=b.readUuid();int entityId=b.readVarInt();String type=b.readString(256),landmark=b.readString(256);Vec3d pos=point(b);String pose=b.readString(64);float yaw=b.readFloat(),pitch=b.readFloat(),bodyYaw=b.readFloat(),headYaw=b.readFloat(),width=b.readFloat(),height=b.readFloat();GameProfile profile=b.readBoolean()?PacketCodecs.GAME_PROFILE.decode(b):null;int n=count(b.readVarInt(),96);var tracked=new ArrayList<DataTracker.SerializedEntry<?>>();for(int i=0;i<n;i++)tracked.add(DataTracker.SerializedEntry.fromBuf(b,b.readUnsignedByte()));n=count(b.readVarInt(),8);var equipment=new ArrayList<Equipment>();for(int i=0;i<n;i++){int slot=count(b.readVarInt(),EquipmentSlot.values().length-1);equipment.add(new Equipment(EquipmentSlot.values()[slot],ItemStack.OPTIONAL_PACKET_CODEC.decode(b)));}return new Ghost(id,entityId,type,landmark,pos,pose,yaw,pitch,bodyYaw,headYaw,width,height,profile,tracked,equipment);});
    }
    public record Cell(int x,int y,int z,int sx,int sy,int sz,String block){
        public Cell{if(sx<1||sy<1||sz<1||sx>128||sy>128||sz>128||Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||Math.abs((long)y)>4096)throw new IllegalArgumentException("Mesh cell");id(block);}
    }
    public record Mesh(String id,String dimension,Vec3d sourceAnchor,Vec384f embedding,List<Cell> cells,double alpha){
        public Mesh{SpiritScenePayload.id(id);SpiritScenePayload.id(dimension);point(sourceAnchor);EmbeddingSpace.requireCurrent(embedding);embedding=embedding.clone();cells=List.copyOf(cells);count(cells.size(),MAX_CELLS);if(!Double.isFinite(alpha)||alpha<0||alpha>1)throw new IllegalArgumentException("Mesh fade");}
        @Override public Vec384f embedding(){return embedding.clone();}
    }
    public record Drop(UUID id,int entityId,Vec384f q,Vec384f target,Vec3d proxy,ItemStack stack){
        public Drop{Objects.requireNonNull(id);EmbeddingSpace.requireCurrent(q);if(target!=null)EmbeddingSpace.requireCurrent(target);q=q.clone();target=target==null?null:target.clone();point(proxy);Objects.requireNonNull(stack);if(stack.isEmpty()||stack.getCount()>999)throw new IllegalArgumentException("Item stack");stack=SpiritItemAppearance.copy(stack);}
        @Override public Vec384f q(){return q.clone();}@Override public Vec384f target(){return target==null?null:target.clone();}public boolean semantic(){return target!=null;}@Override public ItemStack stack(){return stack.copy();}
        public String item(){return Registries.ITEM.getId(stack.getItem()).toString();}public int count(){return stack.getCount();}
    }
    private static void vector(RegistryByteBuf b,Vec384f v){EmbeddingSpace.requireCurrent(v);for(int bits:v.toBits())b.writeInt(bits);}
    private static Vec384f vector(RegistryByteBuf b){int[] bits=new int[EmbeddingSpace.DIMENSIONS];for(int i=0;i<bits.length;i++)bits[i]=b.readInt();return Vec384f.fromBits(bits);}
    private static void point(RegistryByteBuf b,Vec3d v){b.writeDouble(v.x);b.writeDouble(v.y);b.writeDouble(v.z);}
    private static Vec3d point(RegistryByteBuf b){return point(new Vec3d(b.readDouble(),b.readDouble(),b.readDouble()));}
    private static void binding(RegistryByteBuf b,Binding v){b.writeBoolean(v!=null);if(v!=null){b.writeString(v.dimension(),256);b.writeString(v.landmarkId(),256);point(b,v.sourcePosition());vector(b,v.captured());}}
    private static Binding binding(RegistryByteBuf b){return b.readBoolean()?new Binding(b.readString(256),b.readString(256),point(b),vector(b)):null;}
    public static final PacketCodec<RegistryByteBuf,SpiritScenePayload> CODEC=PacketCodec.of((p,b)->{
        b.writeVarInt(EmbeddingSpace.DIMENSIONS);b.writeString(EmbeddingSpace.FINGERPRINT,64);SpiritFramePayload.Session.encode(b,p.session());b.writeLong(p.sequence());b.writeBoolean(p.deep());b.writeBoolean(p.sourceObserved());binding(b,p.binding());
        b.writeVarInt(p.peers().size());for(Peer v:p.peers()){b.writeUuid(v.id());vector(b,v.q());vector(b,v.basis().i);vector(b,v.basis().j);vector(b,v.basis().k);b.writeBoolean(v.deep());binding(b,v.binding());b.writeFloat(v.yaw());b.writeFloat(v.pitch());Ghost.CODEC.encode(b,v.appearance());}
        b.writeVarInt(p.ghosts().size());for(Ghost v:p.ghosts())Ghost.CODEC.encode(b,v);
        b.writeVarInt(p.meshes().size());for(Mesh v:p.meshes()){b.writeString(v.id(),256);b.writeString(v.dimension(),256);point(b,v.sourceAnchor());vector(b,v.embedding());b.writeDouble(v.alpha());b.writeVarInt(v.cells().size());for(Cell c:v.cells()){b.writeInt(c.x());b.writeInt(c.y());b.writeInt(c.z());b.writeVarInt(c.sx());b.writeVarInt(c.sy());b.writeVarInt(c.sz());b.writeString(c.block(),256);}}
        b.writeVarInt(p.drops().size());for(Drop v:p.drops()){b.writeUuid(v.id());b.writeVarInt(v.entityId());vector(b,v.q());b.writeBoolean(v.target()!=null);if(v.target()!=null)vector(b,v.target());point(b,v.proxy());ItemStack.PACKET_CODEC.encode(b,v.stack());}
    },b->{
        if(b.readableBytes()>950_000||b.readVarInt()!=EmbeddingSpace.DIMENSIONS||!EmbeddingSpace.FINGERPRINT.equals(b.readString(64)))throw new IllegalArgumentException("Incompatible semantic scene");
        var session=SpiritFramePayload.Session.decode(b);long sequence=b.readLong();boolean deep=b.readBoolean(),sourceObserved=b.readBoolean();Binding binding=binding(b);
        int n=count(b.readVarInt(),MAX_PEERS);var peers=new ArrayList<Peer>(n);for(int i=0;i<n;i++)peers.add(new Peer(b.readUuid(),vector(b),new Basis384f(vector(b),vector(b),vector(b)),b.readBoolean(),binding(b),b.readFloat(),b.readFloat(),Ghost.CODEC.decode(b)));
        n=count(b.readVarInt(),MAX_GHOSTS);var ghosts=new ArrayList<Ghost>(n);for(int i=0;i<n;i++)ghosts.add(Ghost.CODEC.decode(b));
        n=count(b.readVarInt(),MAX_MESHES);var meshes=new ArrayList<Mesh>(n);int total=0;for(int i=0;i<n;i++){String id=b.readString(256),dimension=b.readString(256);Vec3d anchor=point(b);Vec384f embedding=vector(b);double alpha=b.readDouble();int size=count(b.readVarInt(),MAX_CELLS);total+=size;count(total,MAX_CELLS);var cells=new ArrayList<Cell>(size);for(int j=0;j<size;j++)cells.add(new Cell(b.readInt(),b.readInt(),b.readInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readString(256)));meshes.add(new Mesh(id,dimension,anchor,embedding,cells,alpha));}
        n=count(b.readVarInt(),MAX_DROPS);var drops=new ArrayList<Drop>(n);for(int i=0;i<n;i++)drops.add(new Drop(b.readUuid(),b.readVarInt(),vector(b),b.readBoolean()?vector(b):null,point(b),ItemStack.PACKET_CODEC.decode(b)));
        return new SpiritScenePayload(session,sequence,deep,sourceObserved,binding,peers,ghosts,meshes,drops);
    });
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
