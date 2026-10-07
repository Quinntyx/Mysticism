package io.github.mysticism.client.spiritworld;

import io.github.mysticism.client.net.SpiritNetworkingClient;
import io.github.mysticism.net.*;
import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.SharedConstants;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Production persistence/codec/cache/queued-lifetime tests; no client/server/GPU boot. */
public final class SpiritProjectionSelfTest {
    private static int checks;
    private static void check(boolean c,String message){checks++;if(!c)throw new AssertionError(message);}
    private static void fails(Runnable r){try{r.run();throw new AssertionError("accepted invalid input");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    private static Vec384f axis(int i){float[] v=new float[EmbeddingSpace.DIMENSIONS];v[i]=1;return new Vec384f(v);}
    private static ProjectionFrame frame(){return new ProjectionFrame(1,987654321,new LandmarkEmbedding(new EmbeddingProfile(EmbeddingSpace.MODEL,EmbeddingSpace.REVISION,"pinned-tokenizer","search_document",EmbeddingSpace.DIMENSIONS,EmbeddingProfile.Normalization.NONE,"test-schema"),axis(4)),new Point3(0,128,0),axis(0),axis(1),axis(2),96);}
    private static RegistryWrapper.WrapperLookup lookup(){return RegistryWrapper.WrapperLookup.of(Stream.empty());}
    private static RegistryByteBuf buffer(){return new RegistryByteBuf(Unpooled.buffer(),net.minecraft.registry.DynamicRegistryManager.EMPTY);}
    private static PersistentStateManager manager(Path path){
        DataFixer identity=new DataFixer(){public <T> Dynamic<T> update(DSL.TypeReference t,Dynamic<T> v,int a,int b){return v;}public Schema getSchema(int version){throw new UnsupportedOperationException();}};
        return new PersistentStateManager(path.toFile(),identity,lookup());
    }
    private static void persistence()throws Exception{
        SharedConstants.createGameVersion();var path=Files.createTempDirectory("mysticism-glyph-state-");UUID player=UUID.randomUUID();String key=SpiritProjectionState.key(player);
        var state=new SpiritProjectionState();state.initialize(frame());Point3 position=state.position("minecraft:stone",axis(0));check(position.equals(new Point3(96,128,0)),"actual terrain scale and absolute anchor");
        var manager=manager(path);manager.set(key,state);manager.save();check(Files.exists(path.resolve(key+".dat")),"real compressed PersistentState disk save");
        var loaded=manager(path).get(SpiritProjectionState.TYPE,key);check(loaded!=null,"real manager reload");loaded.initialize(frame());
        check(loaded.position("minecraft:stone",axis(1)).equals(position),"same stable ID retains saved placement even after input change");
        check(loaded.frame().seed()==987654321 && loaded.frame().epoch()==1,"persisted seed and epoch");
        var encoded=loaded.writeNbt(new net.minecraft.nbt.NbtCompound(),lookup());encoded.getCompound("frame").putString("fingerprint","wrong");fails(()->SpiritProjectionState.fromNbt(encoded,lookup()));
        var other=new ProjectionFrame(2,987654321,frame().semanticOrigin(),frame().realmOrigin(),axis(0),axis(1),axis(2),96);fails(()->loaded.initialize(other));
        for(int i=loaded.size();i<SpiritProjectionState.MAX_PLACEMENTS;i++)loaded.position("id-"+i,axis(0));check(loaded.size()==4096,"bounded persisted catalog");fails(()->loaded.position("overflow",axis(0)));
        check(loaded.position("minecraft:stone",axis(2)).equals(position),"budget never evicts old stable position");
    }
    private static SpiritFramePayload.Session session(UUID nonce,UUID player,long generation){return new SpiritFramePayload.Session(nonce,player,"mysticism:spirit",generation,1);}
    private static SpiritDeltaPayload delta(SpiritFramePayload.Session s,long seq,List<SpiritDeltaPayload.Added> add,List<String> remove){return new SpiritDeltaPayload(s,seq,add,remove);}
    private static SpiritDeltaPayload.Added placed(String id,Point3 p){return new SpiritDeltaPayload.Added(id,axis(0).toBits(),p);}
    private static void protocolAndCache(){
        var connection=new Object();var world=new Object();var playerObject=new Object();UUID player=UUID.randomUUID(),nonce=UUID.randomUUID();var s=session(nonce,player,1);var framePacket=new SpiritFramePayload(s,frame());
        ClientSpiritCache.observe(connection,world,playerObject,"mysticism:spirit",player);
        check(!ClientSpiritCache.accept(new SpiritDeltaPayload(List.of(SpiritDeltaPayload.Added.of("legacy",axis(0))),List.of())),"legacy unauthenticated delta fails closed");
        var first=delta(s,1,List.of(placed("stone",new Point3(96,128,0))),List.of());check(!ClientSpiritCache.accept(first),"delta waits for bootstrap, not initial CCA defaults");
        var b=buffer();try{SpiritFramePayload.CODEC.encode(b,framePacket);var decoded=SpiritFramePayload.CODEC.decode(b);check(SpiritProjectionState.encodeFrame(decoded.frame()).equals(SpiritProjectionState.encodeFrame(frame())),"complete pinned frame wire roundtrip");check(ClientSpiritCache.accept(decoded),"authenticated bootstrap");}finally{b.release();}
        b=buffer();try{SpiritDeltaPayload.CODEC.encode(b,first);check(b.readableBytes()==first.encodedBytes(),"exact authoritative wire byte budget");check(ClientSpiritCache.accept(SpiritDeltaPayload.CODEC.decode(b)),"placed delta wire roundtrip");}finally{b.release();}
        check(ClientSpiritCache.frame().position("stone",axis(0)).x==96,"renderer consumes exact server placement");check(!ClientSpiritCache.accept(first),"sequence replay rejected");
        check(ClientSpiritCache.accept(delta(s,2,List.of(),List.of("stone"))),"removal accepted");check(ClientSpiritCache.VEC.isEmpty() && ClientSpiritCache.VISIBLE.isEmpty() && ClientSpiritCache.frame().size()==0,"removal releases vector and position");
        ClientSpiritCache.observe(connection,new Object(),playerObject,"minecraft:overworld",player);check(!ClientSpiritCache.accept(framePacket),"old dimension bootstrap rejected");check(ClientSpiritCache.VEC.isEmpty(),"dimension transition clears vectors");
        ClientSpiritCache.observe(connection,new Object(),playerObject,"mysticism:spirit",player);check(!ClientSpiritCache.accept(framePacket),"queued prior-entry bootstrap watermark rejected");
        var next=session(nonce,player,2);check(ClientSpiritCache.accept(new SpiritFramePayload(next,frame())),"new entry generation bootstrap");check(!ClientSpiritCache.accept(first),"old-session delta rejected");
        ClientSpiritCache.observe(connection,world,new Object(),"mysticism:spirit",player);check(!ClientSpiritCache.accept(new SpiritFramePayload(next,frame())),"respawn invalidation and watermark");
        check(!SpiritNetworkingClient.sameLifetime(connection,world,playerObject,connection,world,new Object()),"queued old respawn closure blocked");check(!SpiritNetworkingClient.sameLifetime(connection,world,playerObject,new Object(),world,playerObject),"queued old connection closure blocked");check(!SpiritNetworkingClient.sameLifetime(connection,world,playerObject,connection,new Object(),playerObject),"queued old world closure blocked");
        ClientSpiritCache.observe(new Object(),world,playerObject,"mysticism:spirit",player);check(ClientSpiritCache.accept(framePacket),"new connection resets watermark");
        var add=new ArrayList<SpiritDeltaPayload.Added>();for(int i=0;i<128;i++)add.add(placed("item-"+i,new Point3(i,128,0)));
        check(ClientSpiritCache.accept(delta(s,1,add,List.of())) && ClientSpiritCache.VEC.size()==128,"active cache bound 128");
        check(!ClientSpiritCache.accept(delta(s,2,List.of(placed("overflow",new Point3(0,128,0))),List.of())) && ClientSpiritCache.VEC.isEmpty(),"oversized aggregate fails closed atomically");
        fails(()->new SpiritDeltaPayload(s,1,List.of(SpiritDeltaPayload.Added.of("missing",axis(0))),List.of()));
        fails(()->placed("nan",new Point3(Double.NaN,0,0)));
        ClientSpiritCache.observe(null,null,null,null,null);
    }
    public static void main(String[] args)throws Exception{persistence();protocolAndCache();System.out.println("Spirit authoritative projection: "+checks+" checks passed (disk/codec/cache; no live client)");}
}
