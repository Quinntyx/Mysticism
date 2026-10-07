package io.github.mysticism.net;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import java.util.*;

/** Exact observer deformed mesh/collision, sent on revisions, not reconstructed from source boxes. */
public record SpiritTerrainPayload(SpiritFramePayload.Session session,long sequence,int part,int parts,boolean full,List<Long> removed,TerrainMeshFrame frame) implements CustomPayload {
    public static final Id<SpiritTerrainPayload> ID=new Id<>(Identifier.of("mysticism","spirit/terrain_v1"));
    public SpiritTerrainPayload {Objects.requireNonNull(session);Objects.requireNonNull(frame);if(sequence<1||part<0||parts<1||parts>8||part>=parts||frame.cells().size()>256)throw new IllegalArgumentException("Terrain fragment");
        removed=List.copyOf(removed);if(removed.size()>2048||part!=0&&!removed.isEmpty())throw new IllegalArgumentException("Terrain removal budget");
        long bytes=1024L+removed.size()*8L;for(var m:frame.materials()){bytes+=m.blockId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+8;for(var e:m.properties().entrySet())bytes+=e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+8;}for(var c:frame.cells())bytes+=256L+c.landmarkId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+48L*c.collision().size();if(bytes>950_000)throw new IllegalArgumentException("Terrain fragment byte budget");}
    public static List<SpiritTerrainPayload> batches(SpiritFramePayload.Session session,long sequence,TerrainMeshFrame f,TerrainMeshFrame previous){
        boolean full=previous==null||!previous.materials().equals(f.materials())||!previous.sourceDimension().equals(f.sourceDimension());var old=new HashMap<Long,TerrainMeshFrame.Cell>();if(!full)previous.cells().forEach(c->old.put(c.key(),c));var changed=new ArrayList<TerrainMeshFrame.Cell>();for(var c:f.cells()){var before=old.remove(c.key());if(full||!c.equals(before))changed.add(c);}var removed=full?List.<Long>of():old.keySet().stream().sorted().toList();
        var packets=new ArrayList<SpiritTerrainPayload>();int parts=Math.max(1,(changed.size()+255)/256);for(int i=0;i<parts;i++){int from=i*256,to=Math.min(from+256,changed.size());var fragment=new TerrainMeshFrame(f.revision(),f.shallow(),f.sourceDimension(),f.sourceOrigin(),f.carrierOrigin(),f.materials(),changed.subList(from,to));packets.add(new SpiritTerrainPayload(session,sequence,i,parts,full,i==0?removed:List.of(),fragment));}return List.copyOf(packets);
    }
    private static int count(int n,int max){if(n<0||n>max)throw new IllegalArgumentException("Terrain wire budget");return n;}
    private static void point(RegistryByteBuf b,Vec3d p){b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);}
    private static Vec3d point(RegistryByteBuf b){return new Vec3d(b.readDouble(),b.readDouble(),b.readDouble());}
    public static final PacketCodec<RegistryByteBuf,SpiritTerrainPayload> CODEC=PacketCodec.of((p,b)->{
        SpiritFramePayload.Session.encode(b,p.session());b.writeLong(p.sequence());b.writeVarInt(p.part());b.writeVarInt(p.parts());b.writeBoolean(p.full());b.writeVarInt(p.removed().size());for(long key:p.removed())b.writeLong(key);var f=p.frame();b.writeLong(f.revision());b.writeBoolean(f.shallow());b.writeString(f.sourceDimension(),256);point(b,f.sourceOrigin());point(b,f.carrierOrigin());
        b.writeVarInt(f.materials().size());for(var m:f.materials()){b.writeString(m.blockId(),256);b.writeVarInt(m.properties().size());m.properties().forEach((k,v)->{b.writeString(k,128);b.writeString(v,128);});}
        b.writeVarInt(f.cells().size());for(var c:f.cells()){b.writeLong(c.key());b.writeVarInt(c.material());b.writeString(c.landmarkId(),256);point(b,c.sourceMin());point(b,c.min());point(b,c.size());point(b,c.axisX());point(b,c.axisY());point(b,c.axisZ());b.writeInt(c.color());b.writeInt(c.light());b.writeFloat(c.opacity());b.writeVarInt(c.collision().size());for(Box box:c.collision()){b.writeDouble(box.minX);b.writeDouble(box.minY);b.writeDouble(box.minZ);b.writeDouble(box.maxX);b.writeDouble(box.maxY);b.writeDouble(box.maxZ);}}
    },b->{
        if(b.readableBytes()>1_000_000)throw new IllegalArgumentException("Oversized terrain frame");var session=SpiritFramePayload.Session.decode(b);long seq=b.readLong();int part=count(b.readVarInt(),7),parts=count(b.readVarInt(),8);boolean full=b.readBoolean();int removalCount=count(b.readVarInt(),2048);var removed=new ArrayList<Long>(removalCount);for(int r=0;r<removalCount;r++)removed.add(b.readLong());long revision=b.readLong();boolean shallow=b.readBoolean();String dimension=b.readString(256);Vec3d source=point(b),carrier=point(b);
        int n=count(b.readVarInt(),256);var materials=new ArrayList<TerrainMeshFrame.Material>(n);for(int i=0;i<n;i++){String block=b.readString(256);int properties=count(b.readVarInt(),64);Map<String,String> values=new TreeMap<>();for(int j=0;j<properties;j++)if(values.put(b.readString(128),b.readString(128))!=null)throw new IllegalArgumentException("Duplicate material property");materials.add(new TerrainMeshFrame.Material(block,values));}
        n=count(b.readVarInt(),256);var cells=new ArrayList<TerrainMeshFrame.Cell>(n);for(int i=0;i<n;i++){long key=b.readLong();int material=b.readVarInt();String landmark=b.readString(256);Vec3d sourceMin=point(b),min=point(b),size=point(b),axisX=point(b),axisY=point(b),axisZ=point(b);int color=b.readInt(),light=b.readInt();float opacity=b.readFloat();int boxes=count(b.readVarInt(),8);var collision=new ArrayList<Box>(boxes);for(int j=0;j<boxes;j++)collision.add(new Box(b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble()));cells.add(new TerrainMeshFrame.Cell(key,material,landmark,sourceMin,min,size,axisX,axisY,axisZ,color,light,opacity,collision));}
        return new SpiritTerrainPayload(session,seq,part,parts,full,removed,new TerrainMeshFrame(revision,shallow,dimension,source,carrier,materials,cells));
    });
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
