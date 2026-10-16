package io.github.mysticism.landmark;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentStateManager;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Real paged store geometry and real SourceOwnership.Request, without a server/model fixture.
 * Synthetic native-width embeddings are test-only. Tick budgets are NOT wall-clock guarantees.
 * Two interleaved cold cursors must preserve masks, cache bounds, exclusivity and lifecycle. */
public final class SourceOwnershipCursorTest {
    private static int checks;
    private static final String DIMENSION="minecraft:overworld";
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejects(Runnable operation,String message){
        boolean rejected=false;
        try{operation.run();}catch(IllegalStateException expected){rejected=true;}
        check(rejected,message);
    }
    private static LandmarkStore store() throws Exception {
        var directory=Files.createTempDirectory("source-ownership-cursor-");
        var identity=new DataFixer(){
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to){return input;}
            @Override public Schema getSchema(int version){throw new UnsupportedOperationException("current-version fixture");}
        };
        return LandmarkStore.open(new PersistentStateManager(directory.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty())),directory);
    }
    private static Landmark landmark(){
        var palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:stone",Map.of()),new BlockPalette.State("minecraft:air",Map.of())));
        var denseBounds=new Bounds(0,0,0,32,1,32);
        var cells=SparseOctree.<BlockSample>empty(Bounds.cube(0,0,0,32),1,32);
        // 1023 leaves: a partial 512-leaf proof cannot finish this page in one advance.
        // Keep one unknown hole INSIDE the AABB next to both known AIR and SOLID.
        for(int x=0;x<32;x++)for(int z=0;z<32;z++)if(x!=31||z!=31){
            boolean air=((x+z)&1)!=0;
            cells=cells.with(Bounds.cube(x,0,z,1),new BlockSample(air?BlockSample.Occupancy.AIR:BlockSample.Occupancy.SOLID,air?1:0),64);
        }
        // Valid, deterministic fixture IDs put the dense page before the distinct tail page.
        var dense=new GeometryPage("lm-"+"0".repeat(64),0,denseBounds,palette,cells);
        var tailBounds=Bounds.cube(32,0,0,1);
        var tail=new GeometryPage("lm-"+"f".repeat(64),0,tailBounds,palette,SparseOctree.<BlockSample>empty(tailBounds,1,1).with(tailBounds,new BlockSample(BlockSample.Occupancy.SOLID,0),1));
        var anchor=new BlockPoint(0,0,0);
        var profile=new EmbeddingProfile("test-native","pinned-test-revision","test-tokenizer","none",Vec384f.ZERO().data().length,EmbeddingProfile.Normalization.NONE,"test-source-v2");
        return new Landmark(LandmarkIds.seed(DIMENSION,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",anchor),DIMENSION,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",anchor,new Bounds(0,0,0,33,1,32),new LandmarkEmbedding(profile,Vec384f.ZERO()),.08,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(dense,tail),List.of()),0,"two-cursor fixture");
    }
    private static void put(LandmarkStore store,Landmark landmark,long expected){
        var mutation=store.stagePut(landmark,expected);while(!mutation.complete())mutation.advance(1);
    }
    /** The same failure/cancellation boundary as Session.tick, with per-phase work accounting. */
    private static void step(SourceOwnership.Request request){
        var before=request.store.cacheStats();int owned=request.owners.size();
        var read=request.read;int cursor=read==null?0:read.nextPageIndex();
        try{request.advance();}catch(RuntimeException failure){request.cancel(failure);}
        var after=request.store.cacheStats();
        check(after.reconstructedLeaves()-before.reconstructedLeaves()<=512,"proof decodes at most 512 leaves per tick");
        check(request.owners.size()-owned<=256,"proof expands at most 256 known voxels per tick");
        if(read!=null)check(read.nextPageIndex()-cursor<=1,"proof scans at most one geometry header/page per tick, including cache hits");
        check(after.pages()<=LandmarkStore.DECODED_PAGE_LIMIT&&after.leaves()<=LandmarkStore.DECODED_LEAF_LIMIT,"shared decoded cache stays bounded");
    }
    private static SourceOwnership.Request request(LandmarkStore store,Bounds bounds){
        return new SourceOwnership.Request(store,DIMENSION,bounds,new CompletableFuture<>());
    }
    private static void acquire(SourceOwnership.Request request){
        for(int ticks=0;ticks<10&&request.read==null&&!request.done;ticks++)step(request);
        check(request.read!=null&&!request.done,"real request acquires its independent proof cursor");
    }
    private static SourceOwnership.Region finish(SourceOwnership.Request request){
        int ticks=0;while(!request.done&&ticks++<200)step(request);
        check(request.done&&!request.future.isCompletedExceptionally(),"proof completes inside walk deadline: "+ticks);
        return request.future.join(); // Already complete; never a runtime wait.
    }
    private static void sharedCacheAndMasks() throws Exception {
        var store=store();var landmark=landmark();put(store,landmark,-1);
        var discovery=store.beginGeometryRead(landmark.id());
        check(discovery.advance(1,1)==1&&discovery.nextPageIndex()==0&&discovery.drain().isEmpty(),"discovery parks a genuinely partial dense-page decoder");
        var request=request(store,landmark.bounds());acquire(request);var proofCursor=request.read;
        rejects(()->store.beginGeometryRead(landmark.id()),"independent proof cursor does not admit competing discovery");
        rejects(()->store.beginOwnershipRead(landmark.id(),landmark.bounds()),"a second proof decoder is not admitted");
        step(request);
        check(proofCursor.nextPageIndex()==0&&!proofCursor.complete(),"512-leaf proof budget leaves its decoder genuinely partial");
        check(!store.geometryReadAvailable()&&!store.ownershipReadAvailable(),"both independent cursor slots are occupied");
        var result=finish(request);
        check(result.owners().size()==1024,"all and only known leaves own source voxels");
        check(result.ownerAt(new BlockPos(30,0,30)).orElseThrow().equals(landmark.id()),"known SOLID retains source-coordinate owner");
        check(result.ownerAt(new BlockPos(30,0,31)).orElseThrow().equals(landmark.id()),"known AIR also retains source-coordinate owner");
        check(result.ownerAt(new BlockPos(31,0,31)).isEmpty(),"unknown hole inside the page AABB is not owned");
        check(result.ownerAt(new BlockPos(32,0,1)).isEmpty(),"unknown gap inside the landmark AABB is not owned");
        check(result.ownerAt(new BlockPos(32,0,0)).orElseThrow().equals(landmark.id()),"tail page owns its distinct source voxel");
        check(result.dimension().equals(DIMENSION)&&result.bounds().equals(landmark.bounds())&&result.ownershipRevision()==store.ownershipRevision(),"completed snapshot retains dimension, exact bounds and source revision");
        check(!store.geometryReadAvailable()&&store.ownershipReadAvailable(),"completed proof releases only the proof cursor");
        var successor=store.beginOwnershipRead(landmark.id(),landmark.bounds());
        proofCursor.cancel();
        check(!store.ownershipReadAvailable(),"a completed old cursor cannot release the next proof cursor");
        check(discovery.advance(1,512)==0,"discovery adopts the immutable page already completed by the proof");
        check(discovery.drain().equals(List.of(landmark.geometry().pages().getFirst())),"adopted dense page preserves all AIR/SOLID/UNKNOWN geometry");
        check(discovery.advance(1,512)==0,"tail page is also resident after the proof");
        check(discovery.drain().equals(List.of(landmark.geometry().pages().getLast()))&&discovery.complete(),"discovery reaches its own correct tail page and completes");
        check(store.geometryReadAvailable()&&!store.ownershipReadAvailable(),"discovery completion does not release the proof successor");
        successor.cancel();
        check(store.ownershipReadAvailable(),"successor releases its own slot");
        // Repeat with a clipped query to prove AIR/SOLID/UNKNOWN and range gating, not just
        // full-page ownership. The tail page is outside the query and must be skipped.
        var clipped=finish(request(store,new Bounds(30,0,30,33,1,32)));
        check(clipped.owners().keySet().equals(Set.of(new BlockPos(30,0,30),new BlockPos(30,0,31),new BlockPos(31,0,30))),"clipped proof publishes exact known source mask, not page/landmark AABBs");
    }
    /** Other-cursor cache hit must clear a suspended decoder, even when the NEXT key is cold. */
    private static void cachedPageFollowedByColdPage() throws Exception {
        var store=store();var landmark=landmark();put(store,landmark,-1);
        var discovery=store.beginGeometryRead(landmark.id());discovery.advance(1,1);discovery.drain();
        var denseBounds=landmark.geometry().pages().getFirst().bounds();
        finish(request(store,denseBounds));
        check(store.cacheStats().pages()==1,"proof skips the out-of-range tail without hydrating it");
        check(discovery.advance(1,512)==0&&discovery.drain().equals(List.of(landmark.geometry().pages().getFirst())),"cache hit supersedes discovery's partial decoder");
        check(discovery.advance(1,512)==1&&discovery.drain().equals(List.of(landmark.geometry().pages().getLast())),"next cold page decodes its own NBT, never the stale prior-page decoder");
        check(discovery.complete()&&store.geometryReadAvailable()&&store.ownershipReadAvailable(),"both slots free after interleaved cold/cache/cold completion");
    }
    private static void proofAdoptsDiscoveryCache() throws Exception {
        var store=store();var landmark=landmark();put(store,landmark,-1);
        var discovery=store.beginGeometryRead(landmark.id());
        var request=request(store,landmark.bounds());acquire(request);step(request);
        check(request.read.nextPageIndex()==0,"proof has a partial decoder before discovery fills the shared cache");
        check(discovery.advance(1,512)==512&&discovery.drain().isEmpty(),"discovery also budgets its independent dense decoder");
        check(discovery.advance(1,512)==511&&discovery.drain().equals(List.of(landmark.geometry().pages().getFirst())),"discovery completes the shared dense page while proof is suspended");
        check(store.cacheStats().pages()==1,"next page stays cold for the proof-adoption regression");
        discovery.cancel();step(request);
        check(request.read!=null&&request.read.nextPageIndex()==1,"partial proof adopts discovery's cached immutable page");
        var result=finish(request);
        check(result.owners().size()==1024&&result.ownerAt(new BlockPos(32,0,0)).orElseThrow().equals(landmark.id()),"proof subsequently decodes the distinct cold tail, not its stale dense-page decoder");
        check(store.geometryReadAvailable()&&store.ownershipReadAvailable(),"both slots released after inverse cache-adoption path");
    }
    private static void cancellationAndThreadConfinement() throws Exception {
        var store=store();var landmark=landmark();put(store,landmark,-1);
        var discovery=store.beginGeometryRead(landmark.id());discovery.advance(1,1);discovery.drain();
        var first=request(store,landmark.bounds());acquire(first);step(first);
        first.future.cancel(false);step(first);
        check(first.done&&first.owners.isEmpty()&&store.ownershipReadAvailable()&&!store.geometryReadAvailable(),"cancelled walk proof releases its partial decoder, never discovery's");
        var second=request(store,landmark.bounds());acquire(second);step(second);var proof=second.read;
        discovery.cancel();
        check(store.geometryReadAvailable()&&!store.ownershipReadAvailable(),"discovery cancellation does not release a partial proof");
        var replacement=store.beginGeometryRead(landmark.id());discovery.cancel();
        check(!store.geometryReadAvailable(),"repeated old discovery cancellation cannot release its replacement");
        try(var worker=Executors.newSingleThreadExecutor()){
            worker.submit(()->{
                rejects(store::ownershipReadAvailable,"proof admission is server-thread confined");
                rejects(()->store.beginOwnershipRead(landmark.id(),landmark.bounds()),"proof creation rejects off-thread access");
                rejects(()->proof.advance(1,512),"proof decoding rejects off-thread access");
                rejects(proof::cancel,"proof cancellation rejects off-thread access");
            }).get(5,TimeUnit.SECONDS);
        }
        check(!store.ownershipReadAvailable()&&!store.geometryReadAvailable(),"off-thread failures leave both cursor slots intact");
        second.cancel(new CancellationException("source dimension unloaded/server stopping"));
        check(second.done&&second.future.isCompletedExceptionally()&&store.ownershipReadAvailable()&&!store.geometryReadAvailable(),"lifecycle failure releases only the real Request's cursor");
        replacement.cancel();finish(request(store,landmark.bounds()));
        check(store.geometryReadAvailable()&&store.ownershipReadAvailable(),"fresh proofs still work after both cancellation paths");
    }
    private static void sourceRevisionInvalidation() throws Exception {
        var store=store();var landmark=landmark();put(store,landmark,-1);
        var discovery=store.beginGeometryRead(landmark.id());discovery.advance(1,1);discovery.drain();
        var request=request(store,landmark.bounds());acquire(request);step(request);
        var changed=new Landmark(landmark.id(),landmark.dimension(),landmark.algorithmVersion(),landmark.kind(),landmark.biome(),landmark.anchor(),landmark.bounds(),landmark.baseEmbedding(),landmark.baseImportance(),landmark.activity(),landmark.ownership(),new SourceGeometry(List.of(landmark.geometry().pages().getFirst()),List.of()),landmark.revision()+1,"tail removed");
        put(store,changed,landmark.revision());step(request);
        check(request.done&&request.future.isCompletedExceptionally()&&request.owners.isEmpty(),"concurrent source topology change fails proof, never publishes mixed revisions");
        check(!discovery.isCurrent()&&!store.geometryReadAvailable()&&store.ownershipReadAvailable(),"invalidated proof releases only its cursor; discovery remains visibly stale");
        discovery.cancel();var fresh=finish(request(store,changed.bounds()));
        check(fresh.ownershipRevision()==store.ownershipRevision()&&fresh.ownerAt(new BlockPos(32,0,0)).isEmpty()&&fresh.owners().size()==1023,"retry proves fresh native source revision with removed tail unowned");
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        sharedCacheAndMasks();cachedPageFollowedByColdPage();proofAdoptsDiscoveryCache();cancellationAndThreadConfinement();sourceRevisionInvalidation();
        System.out.println("SourceOwnershipCursorTest: "+checks+" checks passed");
    }
}
