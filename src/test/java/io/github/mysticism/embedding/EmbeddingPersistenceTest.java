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
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.handler.SizePrepender;
import java.io.DataInputStream;
import java.io.IOException;
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
    /** Dependency-free classfile inspection: protect the completed-chunk API contract.
     * This is not a simulation of a live ticketed chunk; it rejects blocking API regressions.
     */
    private static Set<String> methodReferences(String resource){
        try(var stream=EmbeddingPersistenceTest.class.getClassLoader().getResourceAsStream(resource)){
            if(stream==null)throw new AssertionError("Missing class resource "+resource);
            var in=new DataInputStream(stream);
            if(in.readInt()!=0xcafebabe)throw new AssertionError("Classfile magic");
            in.readUnsignedShort();in.readUnsignedShort();int count=in.readUnsignedShort();
            int[] tags=new int[count],a=new int[count],b=new int[count];String[] text=new String[count];
            for(int i=1;i<count;i++){
                tags[i]=in.readUnsignedByte();
                switch(tags[i]){
                    case 1->text[i]=in.readUTF();
                    case 3,4->in.readInt();
                    case 5,6->{in.readLong();i++;}
                    case 7,8,16,19,20->a[i]=in.readUnsignedShort();
                    case 9,10,11,12,17,18->{a[i]=in.readUnsignedShort();b[i]=in.readUnsignedShort();}
                    case 15->{in.readUnsignedByte();in.readUnsignedShort();}
                    default->throw new AssertionError("Unknown classfile tag "+tags[i]);
                }
            }
            Set<String> refs=new HashSet<>();
            for(int i=1;i<count;i++)if(tags[i]==10||tags[i]==11)refs.add(text[a[a[i]]]+"#"+text[a[b[i]]]);
            return refs;
        }catch(IOException error){throw new AssertionError(error);}
    }
    private static void chunkAccessContract(){
        for(String name:List.of("HorizonSeeder","impl/BiomeSpiritualRegion")){
            var refs=methodReferences("io/github/mysticism/world/region/"+name+".class");
            check(refs.contains("net/minecraft/server/world/ServerChunkManager#getWorldChunk"),"Completed-chunk lookup required");
            check(refs.stream().noneMatch(ref->ref.endsWith("#getChunk")||ref.endsWith("#isChunkLoaded")||ref.endsWith("#join")),"No ticket-eligibility/blocking chunk API or future join");
        }
        var spawn=methodReferences("io/github/mysticism/world/region/impl/BiomeSpiritualRegion.class");
        check(spawn.contains("net/minecraft/world/chunk/WorldChunk#sampleHeightmap"),"Heightmap read from completed chunk");
        check(!spawn.contains("net/minecraft/server/world/ServerWorld#getTopY")&&!spawn.contains("net/minecraft/server/world/ServerWorld#getBlockState"),"No implicit world chunk fetch in surface discovery");
    }
    private static void geometry(){
        rejects(()->new ChunkBox(0,0,Integer.MAX_VALUE,0));
        rejects(()->new ChunkBox(-1,0,1,0));rejects(()->new ChunkBox(1,0,0,0));
        rejects(()->new ChunkBox(0,2,0,1));rejects(()->new ChunkBox(0,0,0,32));
        var box=new ChunkBox(0,0,31,31);
        check(box.area()==1024&&box.width()==32,"Bounded box arithmetic");
        var boxNbt=ChunkBox.CODEC.encodeStart(NbtOps.INSTANCE,box).getOrThrow();
        check(ChunkBox.CODEC.parse(NbtOps.INSTANCE,boxNbt).getOrThrow().equals(box),"Box codec roundtrip");
        for(int invalid:List.of(-1,32,Integer.MAX_VALUE,Integer.MIN_VALUE)){
            var bad=((NbtCompound)boxNbt).copy();bad.putInt("x1",invalid);
            check(ChunkBox.CODEC.parse(NbtOps.INSTANCE,bad).error().isPresent(),"Out-of-range bounds are codec errors");
        }
        var inverted=((NbtCompound)boxNbt).copy();inverted.putInt("x0",31);inverted.putInt("x1",0);
        check(ChunkBox.CODEC.parse(NbtOps.INSTANCE,inverted).error().isPresent(),"Ordered bounds required by codec");
        var boxes=new ArrayList<ChunkBox>();
        for(int z=0;z<32;z++)for(int x=0;x<32;x++)boxes.add(new ChunkBox(x,z,31,31));
        var region=new BiomeSpiritualRegion(-1,-1,Identifier.of("minecraft:plains"),boxes);
        var candidates=region.spawnCandidates(Integer.MAX_VALUE);
        check(candidates.size()==1024&&new HashSet<>(candidates).size()==1024,"Overlaps allocate at most 1024 unique candidates");
        check(candidates.stream().allMatch(p->p.x>=-32&&p.x<=-1&&p.z>=-32&&p.z<=-1),"Candidates remain inside their region");
        Collections.reverse(boxes);var reordered=new BiomeSpiritualRegion(-1,-1,Identifier.of("minecraft:plains"),boxes);
        check(candidates.equals(reordered.spawnCandidates(Integer.MAX_VALUE)),"Stable center and coordinate ties independent of box order");
        check(region.spawnCandidates(1).size()==1&&region.spawnCandidates(7).equals(candidates.subList(0,7)),"Budget includes center, no extra candidate");
        check(region.spawnCandidates(0).isEmpty()&&region.spawnCandidates(-1).isEmpty(),"Zero/negative scan budgets");
        var duplicate=new BiomeSpiritualRegion(0,0,Identifier.of("minecraft:plains"),Collections.nCopies(1024,box));
        check(duplicate.boxes().size()==1&&duplicate.spawnCandidates(Integer.MAX_VALUE).size()==1024,"Exact boxes deduplicated");
        rejects(()->new BiomeSpiritualRegion(0,0,Identifier.of("minecraft:plains"),Collections.nCopies(1025,box)));
        var regionNbt=(NbtCompound)BiomeSpiritualRegion.CODEC.codec().encodeStart(NbtOps.INSTANCE,duplicate).getOrThrow();
        var tooMany=regionNbt.copy();var boxList=new NbtList();for(int i=0;i<1025;i++)boxList.add(boxNbt.copy());tooMany.put("boxes",boxList);
        check(BiomeSpiritualRegion.CODEC.codec().parse(NbtOps.INSTANCE,tooMany).error().isPresent(),"Oversized box list rejected at decode");
        String id="minecraft:overworld|vregion|0,0|minecraft:plains";
        var state=new SpatialEmbeddingIndexState();state.observeBiome(id,duplicate,CanonicalDescriptors.region("minecraft:overworld","minecraft:plains"),vector());
        var malformed=state.writeNbt(new NbtCompound(),null);var badRegion=regionNbt.copy();
        badRegion.getList("boxes",NbtElement.COMPOUND_TYPE).getCompound(0).putInt("x1",Integer.MAX_VALUE);
        check(BiomeSpiritualRegion.CODEC.codec().parse(NbtOps.INSTANCE,badRegion).error().isPresent(),"Review reproduction rejected without constructor exception");
        String badId="minecraft:overworld|vregion|0,0|minecraft:forest";
        malformed.getCompound("regions").put(badId,badRegion);
        var recovered=SpatialEmbeddingIndexState.fromNbt(malformed,null);
        check(recovered.needsRebuild()&&recovered.getIndex().size()==0,"Malformed geometry never activates an index");
        check(recovered.regionsView().size()==1&&recovered.getRegion(id)!=null&&recovered.getRegion(badId)==null,"Valid siblings survive; malformed entry rejected");
        var saved=recovered.writeNbt(new NbtCompound(),null);check(saved.getCompound("archive").equals(malformed),"Malformed raw geometry archived exactly");
        var resaved=SpatialEmbeddingIndexState.fromNbt(saved,null).writeNbt(new NbtCompound(),null);
        check(resaved.getCompound("archive").equals(malformed),"Archive stable across interrupted migration");
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
        var wrong=buffer();try{wrong.writeBoolean(false);wrong.writeLong(0);wrong.writeVarInt(EmbeddingSpace.SCHEMA);wrong.writeString("wrong");wrong.writeVarInt(EmbeddingSpace.DIMENSIONS);rejects(()->SpiritDeltaPayload.CODEC.decode(wrong));}finally{wrong.release();}
        var malformed=buffer();try{malformed.writeString("id");malformed.writeVarInt(Integer.MAX_VALUE);rejects(()->SpiritDeltaPayload.Added.CODEC.decode(malformed));}finally{malformed.release();}
        var counts=buffer();try{counts.writeBoolean(false);counts.writeLong(0);counts.writeVarInt(EmbeddingSpace.SCHEMA);counts.writeString(EmbeddingSpace.FINGERPRINT);counts.writeVarInt(EmbeddingSpace.DIMENSIONS);counts.writeVarInt(-1);rejects(()->SpiritDeltaPayload.CODEC.decode(counts));}finally{counts.release();}
        int[] bad=vector().toBits();bad[0]=Float.floatToIntBits(Float.NaN);rejects(()->new SpiritDeltaPayload.Added("id",bad));
    }
    private static void framed(ByteBuf body){
        var channel=new EmbeddedChannel(new SizePrepender());
        try{
            check(channel.writeOutbound(body.copy()),"Actual Minecraft frame encoder accepts batch");
            ByteBuf framed=channel.readOutbound();try{check(framed.readableBytes()<=1_048_576,"Including wrappers/frame stays below 1 MiB");}finally{framed.release();}
        }finally{channel.finishAndReleaseAll();}
    }
    private static void transport(){
        var shortId=new SpiritDeltaPayload.Added("a",vector().toBits());
        var additions=Collections.nCopies(2048,shortId);
        rejects(()->new SpiritDeltaPayload(additions,List.of()));
        rejects(()->new SpiritDeltaPayload.Added("a".repeat(257),vector().toBits()));
        rejects(()->new SpiritDeltaPayload.Added("",vector().toBits()));
        rejects(()->new SpiritDeltaPayload.Added("\uD800x",vector().toBits()));
        rejects(()->new SpiritDeltaPayload(List.of(),List.of("x".repeat(257))));
        var raw=buffer();
        try{
            raw.writeBoolean(false);raw.writeLong(0);raw.writeVarInt(EmbeddingSpace.SCHEMA);raw.writeString(EmbeddingSpace.FINGERPRINT);raw.writeVarInt(EmbeddingSpace.DIMENSIONS);raw.writeVarInt(2048);
            for(var a:additions)SpiritDeltaPayload.Added.CODEC.encode(raw,a);raw.writeVarInt(0);
            check(raw.readableBytes()==2_107_472,"Exact protocol-3 transport reproduction, including absent-session/placement tags");
            var channel=new EmbeddedChannel(new SizePrepender());try{rejects(()->channel.writeOutbound(raw.copy()));}finally{channel.finishAndReleaseAll();}
            rejects(()->SpiritDeltaPayload.CODEC.decode(raw));
        }finally{raw.release();}
        var removals=new ArrayList<String>();for(int i=0;i<5000;i++)removals.add("gone:"+i);
        var batches=SpiritDeltaPayload.batches(additions,removals);
        check(batches.size()>1,"Large delta split");
        check(batches.stream().flatMap(p->p.add().stream()).toList().equals(additions),"All additions preserved in order");
        check(batches.stream().flatMap(p->p.remove().stream()).toList().equals(removals),"All removals preserved in order");
        rejects(()->batches.clear());check(SpiritDeltaPayload.batches(List.of(),List.of()).isEmpty(),"No empty traffic");
        for(var batch:batches){
            var buf=buffer();try{
                SpiritDeltaPayload.CODEC.encode(buf,batch);
                check(buf.readableBytes()==batch.encodedBytes()&&batch.encodedBytes()<=SpiritDeltaPayload.MAX_ENCODED_BYTES,"Exact encoded byte budget");
                var decoded=SpiritDeltaPayload.CODEC.decode(buf);check(decoded.add().size()==batch.add().size()&&decoded.remove().equals(batch.remove()),"Batch wire roundtrip");
                buf.readerIndex(0);buf.writeZero(512);framed(buf);
            }finally{buf.release();}
        }
        for(String id:List.of("中".repeat(256),"😀".repeat(128))){
            var packet=new SpiritDeltaPayload(List.of(new SpiritDeltaPayload.Added(id,vector().toBits())),List.of(id));var buf=buffer();
            try{SpiritDeltaPayload.CODEC.encode(buf,packet);check(buf.readableBytes()==packet.encodedBytes(),"UTF-8 wire accounting, including surrogate pairs");check(SpiritDeltaPayload.CODEC.decode(buf).remove().get(0).equals(id),"Bounded Unicode IDs roundtrip");}finally{buf.release();}
        }
        var removalOnly=SpiritDeltaPayload.batches(List.of(),removals);
        check(removalOnly.size()==2&&removalOnly.get(0).remove().size()==SpiritDeltaPayload.MAX_ENTRIES,"Removal count boundary batches even below byte cap");
    }
    public static void main(String[] args){items();spatial();geometry();chunkAccessContract();components();network();transport();System.out.println("PASS embedding persistence/network: "+assertions+" assertions");}
}
