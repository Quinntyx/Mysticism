package io.github.mysticism.embedding;

import com.mojang.serialization.Codec;
import io.github.mysticism.Codecs;
import io.github.mysticism.component.*;
import io.github.mysticism.net.SpiritDeltaPayload;
import io.github.mysticism.vector.*;
import io.github.mysticism.world.region.*;
import io.github.mysticism.world.region.impl.BiomeSpiritualRegion;
import io.github.mysticism.world.state.*;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import java.util.*;

/** Real Minecraft NBT/packet regression suite. Requires the existing Loom runtime classpath. */
public final class EmbeddingPersistenceTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action){assertions++;try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError("Expected rejection");}
    private static Vec384f vector(){float[] values=new float[EmbeddingSpace.DIMENSIONS];values[0]=1;return new Vec384f(values);}
    private static NbtCompound entries(String id,int[] bits){var entries=new NbtCompound();entries.putIntArray(id,bits);return entries;}
    private static NbtCompound item(String fingerprint){
        var nbt=new NbtCompound();EmbeddingNbt.stamp(nbt);nbt.putString("embeddingFingerprint",fingerprint);
        nbt.put("entries",entries("minecraft:stone",vector().toBits()));var descriptions=new NbtCompound();descriptions.putString("minecraft:stone",CanonicalDescriptors.item("minecraft:stone",List.of()));
        nbt.put("descriptors",descriptions);nbt.putBoolean("populated",true);return nbt;
    }
    private static void items(){
        var missingVersion=item(EmbeddingSpace.FINGERPRINT);missingVersion.putInt("embeddingDescriptorVersion",0);
        check(!EmbeddingNbt.compatible(missingVersion),"Descriptor version required even when fingerprint matches");
        var current=ItemEmbeddingIndexState.fromNbt(item(EmbeddingSpace.FINGERPRINT),null);
        check(current.isPopulated()&&current.getIndex().size()==1,"Current generation loads");current.getVec("minecraft:stone").mul(0);check(current.getVec("minecraft:stone").length()==1,"Loaded state owns copies");
        var save=current.writeNbt(new NbtCompound(),null);check(EmbeddingNbt.compatible(save),"Persistence profile header");check(ItemEmbeddingIndexState.fromNbt(save,null).getIndex().size()==1,"Current item roundtrip");
        var legacy=item("minilm");legacy.put("entries",entries("minecraft:stone",new int[384]));
        var migrated=ItemEmbeddingIndexState.fromNbt(legacy,null);check(!migrated.isPopulated()&&migrated.getIndex().size()==0,"MiniLM never padded/truncated");
        var archive=migrated.writeNbt(new NbtCompound(),null).getCompound("archive");check(archive.getCompound("entries").getIntArray("minecraft:stone").length==384,"Exact legacy archive");
        var sameDimensions=ItemEmbeddingIndexState.fromNbt(item("different-nomic-revision"),null);check(sameDimensions.getIndex().size()==0,"Equal dimensions do not imply compatibility");
        var malformed=item(EmbeddingSpace.FINGERPRINT);int[] bits=vector().toBits();bits[0]=Float.floatToIntBits(Float.NaN);malformed.put("entries",entries("minecraft:stone",bits));check(ItemEmbeddingIndexState.fromNbt(malformed,null).getIndex().size()==0,"Malformed persisted vector rejected");
        check(Codecs.VEC384F.parse(NbtOps.INSTANCE,new NbtIntArray(new int[384])).error().isPresent(),"Codec returns error, not thrown decoder exception");
    }
    private static void spatial(){
        String id="minecraft:overworld|vregion|0,0|minecraft:plains";
        var region=new BiomeSpiritualRegion(0,0,Identifier.of("minecraft:plains"),List.of(new ChunkBox(0,0,0,0)));
        var geometry=Codec.unboundedMap(Codec.STRING,BiomeSpiritualRegion.CODEC.codec()).encodeStart(NbtOps.INSTANCE,Map.of(id,region)).getOrThrow();
        var legacy=new NbtCompound();legacy.put("regions",geometry);legacy.put("embedding",entries(id,new int[384]));
        var migrated=SpatialEmbeddingIndexState.fromNbt(legacy,null);check(migrated.needsRebuild()&&migrated.getIndex().size()==0,"Spatial vectors unavailable until rebuilt");check(migrated.regionsView().size()==1,"Geometry survives incompatible vector decoding");
        var save=migrated.writeNbt(new NbtCompound(),null);var reload=SpatialEmbeddingIndexState.fromNbt(save,null);var resave=reload.writeNbt(new NbtCompound(),null);
        check(reload.needsRebuild()&&reload.regionsView().size()==1,"Interrupted migration resumes");check(resave.getCompound("archive").equals(legacy),"Repeated unavailable startup does not nest/grow archive");
        var current=new SpatialEmbeddingIndexState();current.observeBiome(id,region,CanonicalDescriptors.region("minecraft:overworld","minecraft:plains"),vector());
        current.observeBiome(id,new BiomeSpiritualRegion(0,0,Identifier.of("minecraft:plains"),List.of(new ChunkBox(5,5,5,5))),CanonicalDescriptors.region("minecraft:overworld","minecraft:plains"),vector());
        var observed=(BiomeSpiritualRegion)current.regionsView().get(id);check(observed.boxes().size()==2,"Disjoint observed chunks, no fabricated bounding coverage");
        var roundtrip=SpatialEmbeddingIndexState.fromNbt(current.writeNbt(new NbtCompound(),null),null);check(roundtrip.getIndex().size()==1&&!roundtrip.needsRebuild(),"Spatial current roundtrip");
    }
    private static void components(){
        var legacy=new NbtCompound();legacy.putIntArray("v",new int[384]);var position=new LatentPos();position.readFromNbt(legacy,null);var save=new NbtCompound();position.writeToNbt(save,null);
        check(position.get().length()==0&&EmbeddingNbt.compatible(save),"Legacy position resets in new space");check(save.getCompound("embeddingArchive").equals(legacy),"Legacy CCA position archived");
        var attunement=new LatentAttunement(vector());var current=new NbtCompound();attunement.writeToNbt(current,null);var other=new LatentAttunement();other.readFromNbt(current,null);check(other.get().length()==1,"Current attunement roundtrip");
        var basis=new LatentBasis();var old=new NbtCompound();old.putIntArray("b",new int[1152]);basis.readFromNbt(old,null);var savedBasis=new NbtCompound();basis.writeToNbt(savedBasis,null);check(savedBasis.getIntArray("b").length==3*EmbeddingSpace.DIMENSIONS&&savedBasis.getCompound("embeddingArchive").equals(old),"Basis schema migration");
        check(basis.get().i.data()[0]==1&&basis.get().j.data()[1]==1&&basis.get().k.data()[2]==1,"Migrated basis remains deterministic and nondegenerate");
        rejects(()->position.set(new Vec384f(vector().data(),"foreign")));
    }
    private static RegistryByteBuf buffer(){return new RegistryByteBuf(Unpooled.buffer(),net.minecraft.registry.DynamicRegistryManager.EMPTY);}
    private static void network(){
        int[] submitted=vector().toBits();var added=new SpiritDeltaPayload.Added("minecraft:stone",submitted);submitted[0]=0;added.bits()[0]=0;check(added.bits()[0]!=0,"Payload arrays copied on both edges");
        var message=new SpiritDeltaPayload(List.of(added),List.of("gone"));var buf=buffer();try{SpiritDeltaPayload.CODEC.encode(buf,message);var decoded=SpiritDeltaPayload.CODEC.decode(buf);check(decoded.add().get(0).id().equals("minecraft:stone")&&Arrays.equals(decoded.add().get(0).bits(),vector().toBits()),"Packet roundtrip");}finally{buf.release();}
        var wrong=buffer();try{wrong.writeVarInt(EmbeddingSpace.SCHEMA);wrong.writeString("wrong");wrong.writeVarInt(EmbeddingSpace.DIMENSIONS);rejects(()->SpiritDeltaPayload.CODEC.decode(wrong));}finally{wrong.release();}
        var malformed=buffer();try{malformed.writeString("id");malformed.writeVarInt(Integer.MAX_VALUE);rejects(()->SpiritDeltaPayload.Added.CODEC.decode(malformed));}finally{malformed.release();}
        var counts=buffer();try{counts.writeVarInt(EmbeddingSpace.SCHEMA);counts.writeString(EmbeddingSpace.FINGERPRINT);counts.writeVarInt(EmbeddingSpace.DIMENSIONS);counts.writeVarInt(-1);rejects(()->SpiritDeltaPayload.CODEC.decode(counts));}finally{counts.release();}
        int[] bad=vector().toBits();bad[0]=Float.floatToIntBits(Float.NaN);rejects(()->new SpiritDeltaPayload.Added("id",bad));
    }
    public static void main(String[] args){items();spatial();components();network();System.out.println("PASS embedding persistence/network: "+assertions+" assertions");}
}
