package io.github.mysticism.landmark;

import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentStateManager;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Cold persisted frontiers use the actual reload retry and bounded metadata pager. */
public final class SourceFrontierReloadTest {
    private static int checks;
    private static final String DIM="minecraft:overworld";
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static PersistentStateManager manager(Path path){
        DataFixer identity=new DataFixer(){
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
            public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    private static Landmark fixture(Landmark.Kind kind,String dimension,long x,List<FrontierFace> frontiers,LandmarkEmbedding embedding){
        BlockPoint anchor=new BlockPoint(x,0,0);Bounds bounds=Bounds.cube(x,0,0,4);
        return new Landmark(LandmarkIds.seed(dimension,"reload-test",kind,"minecraft:plains",anchor),dimension,"reload-test",kind,"minecraft:plains",anchor,bounds,embedding,.5,
                new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),frontiers),0,"generated reload regression fixture");
    }
    private static List<FrontierFace> faces(String dimension){
        List<FrontierFace> faces=new ArrayList<>();
        for(int i=0;i<20;i++)faces.add(new FrontierFace(dimension,Bounds.cube(4+i%3,i/3,0,1),FrontierFace.Direction.EAST,0,"retry:"+i));
        faces.add(new FrontierFace(dimension,Bounds.cube(30,0,0,1),FrontierFace.Direction.EAST,0,"other-chunk"));
        // A face can intersect the loaded band while its minimum corner is outside it.
        faces.add(new FrontierFace(dimension,new Bounds(-2,0,0,1,1,1),FrontierFace.Direction.WEST,0,"partial-face"));
        return faces;
    }
    private static void put(LandmarkStore store,Landmark landmark){var mutation=store.stagePut(landmark,-1);while(!mutation.complete())mutation.advance(8);}
    private static List<BlockPos> retry(LandmarkStore store,Bounds loaded){
        var retry=new SourceLandmarks.FrontierRetry(DIM,loaded);List<BlockPos> hints=new ArrayList<>();int turns=0;
        while(true){
            int before=hints.size();boolean done=retry.advance(store,(dimension,pos)->{check(dimension.equals(DIM),"source dimension retained");check(loaded.contains(pos.getX(),pos.getY(),pos.getZ()),"retry seed lies inside newly usable loaded band");hints.add(pos);return true;});
            check(hints.size()-before<=8,"per-turn frontier hint budget");check(++turns<256,"retry completes beyond legacy 128-record cutoff");if(done)break;
        }
        return hints;
    }
    private static void backpressure(LandmarkStore store,Bounds loaded,List<BlockPos> expected){
        var retry=new SourceLandmarks.FrontierRetry(DIM,loaded);List<BlockPos> blocked=new ArrayList<>();int turns=0;
        while(blocked.isEmpty()){check(!retry.advance(store,(dimension,pos)->{blocked.add(pos);return false;}),"full discovery queue does not finish retry");check(++turns<256,"reaches a persisted face under backpressure");}
        retry.advance(store,(dimension,pos)->{blocked.add(pos);return false;});
        check(blocked.size()==2&&blocked.get(0).equals(blocked.get(1)),"rejected reload seed is retained, not silently consumed");
        List<BlockPos> accepted=new ArrayList<>();
        while(!retry.advance(store,(dimension,pos)->{accepted.add(pos);return true;}))check(++turns<512,"backpressure retry resumes bounded progress");
        check(accepted.equals(expected),"all persisted reload frontiers survive discovery queue saturation");
    }
    private static SourceLandmarks.Cell sample(long x,String block){return new SourceLandmarks.Cell(new BlockPoint(x,0,0),new BlockPalette.State(block,Map.of()),"minecraft:plains",false);}
    private static Landmark publish(SourceLandmarks.Prepared value,LandmarkEmbedding embedding,long revision){
        return new Landmark(value.id(),DIM,SourceLandmarks.ALGORITHM,value.kind(),value.biome(),value.anchor(),OwnershipGeometry.bounds(value.anchor(),value.geometry()),embedding,.5,
                new ActivityMetadata(0,0),new Ownership(List.of()),value.geometry(),revision,"actual generated source reload fixture");
    }
    private static Landmark hydrate(LandmarkStore store,String id){
        var read=store.beginGeometryRead(id);List<GeometryPage> pages=new ArrayList<>();
        do{read.advance(1,512);pages.addAll(read.drain());}while(!read.complete());
        return LandmarkNbt.hydrate(read.metadata(),pages);
    }
    private static SourceOwnership.Region owners(LandmarkStore store,Bounds bounds){
        var future=new java.util.concurrent.CompletableFuture<SourceOwnership.Region>();var request=new SourceOwnership.Request(store,DIM,bounds,future);int turns=0;
        while(!request.done){request.advance();check(++turns<256,"ownership cursor remains resumable after reload");}
        return future.join();
    }
    private static void growthAfterReload(LandmarkEmbedding embedding)throws Exception{
        Path path=Files.createTempDirectory("source-growth-reload-");var manager=manager(path);var store=LandmarkStore.open(manager,path);
        Bounds observed=new Bounds(14,0,0,18,1,1);BlockPos seed=new BlockPos(15,0,0);
        var before=new SourceLandmarks.Region(DIM,observed,List.of(sample(14,"minecraft:stone"),sample(15,"minecraft:air")),false);
        var prepared=SourceLandmarks.prepare(before,List.of(),seed,null,false,0,63,Map.of());
        check(prepared!=null&&prepared.kind()==Landmark.Kind.CAVE,"actual partial source observation creates a provisional cave mask");
        var initial=publish(prepared,embedding,0);put(store,initial);manager.save();manager=manager(path);store=LandmarkStore.open(manager,path);
        var retained=hydrate(store,initial.id());
        check(retained.geometry().sample(15,0,0)!=null&&retained.geometry().sample(16,0,0)==null,"persisted discovery retains known air but never invents unloaded neighbor");
        check(SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,observed,List.of(),false),List.of(retained),seed,null,false,1,63,Map.of())==null,"temporarily missing source adapter cannot erase/create an owner");
        var ownership=owners(store,observed);
        check(ownership.ownerAt(seed).orElseThrow().equals(initial.id())&&ownership.ownerAt(new BlockPos(16,0,0)).isEmpty(),"persisted locality ownership is independent of chunk availability, unknown neighbor unowned");
        var after=new SourceLandmarks.Region(DIM,observed,List.of(sample(14,"minecraft:stone"),sample(15,"minecraft:air"),sample(16,"minecraft:air"),sample(17,"minecraft:air")),true);
        var grown=SourceLandmarks.prepare(after,List.of(retained),new BlockPos(16,0,0),null,false,2,63,Map.of(retained.id(),.5));
        check(grown!=null&&grown.id().equals(initial.id())&&grown.anchor().equals(initial.anchor()),"neighbor reload grows the same connected source ID/anchor, not a chunk-shaped replacement");
        check(grown.geometry().sample(14,0,0).occupancy()==BlockSample.Occupancy.SOLID&&grown.geometry().sample(16,0,0).occupancy()==BlockSample.Occupancy.AIR,"source octree retains wall material and newly proven air");
        check(grown.geometry().frontiers().stream().noneMatch(f->f.missingBounds().contains(16,0,0)),"resolved reload frontier no longer advertises a missing proven cell");
        var mutation=store.stagePut(publish(grown,embedding,1),0);while(!mutation.complete())mutation.advance(8);
        manager.save();store=LandmarkStore.open(manager(path),path);
        var restored=hydrate(store,initial.id());check(restored.revision()==1&&restored.baseEmbedding().equals(embedding),"native vector/revision and grown octree survive a second compressed reload");
        check(owners(store,observed).ownerAt(new BlockPos(16,0,0)).orElseThrow().equals(initial.id()),"newly usable locality exposes canonical exact owner after reload");
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.createGameVersion();Path path=Files.createTempDirectory("source-frontier-reload-");var manager=manager(path);var store=LandmarkStore.open(manager,path);
        // Full native-size vectors are only deterministic metadata fixtures; no model invocation.
        var profile=new EmbeddingProfile("reload-fixture","2","fixture","none",EmbeddingSpace.DIMENSIONS,EmbeddingProfile.Normalization.UNIT,"source-octree-v2");
        float[] data=new float[EmbeddingSpace.DIMENSIONS];data[0]=1;var embedding=new LandmarkEmbedding(profile,new Vec384f(data));
        List<String> localIds=new ArrayList<>();int index=0;
        for(var kind:Landmark.Kind.values()){var landmark=fixture(kind,DIM,index++,faces(DIM),embedding);put(store,landmark);localIds.add(landmark.id());}
        put(store,fixture(Landmark.Kind.BIOME,"minecraft:the_nether",0,faces("minecraft:the_nether"),embedding));
        for(int i=0;i<140;i++)put(store,fixture(Landmark.Kind.CAVE,DIM,1000L+i*8,List.of(),embedding));
        Map<String,Long> revisions=new TreeMap<>();for(String id:localIds)revisions.put(id,store.metadata(id).orElseThrow().revision());
        manager.save();store=LandmarkStore.open(manager(path),path);
        Bounds loaded=new Bounds(-1,-1,-1,8,8,4);List<BlockPos> hints=retry(store,loaded);
        check(hints.size()==63,"all BIOME/CAVE/MOUNTAIN frontiers resume after compressed reload, foreign/outside faces excluded");
        check(Collections.frequency(hints,new BlockPos(-1,0,0))==3,"partially intersecting face resumes from loaded intersection");
        check(store.cacheStats().reconstructedLeaves()==0,"retry is metadata-only, never hydrates source terrain");
        check(store.ids().size()==144,"reload discovery does not delete source owners or rebuild catalog");
        for(String id:localIds){var metadata=store.metadata(id).orElseThrow();check(metadata.revision()==revisions.get(id),"persisted source revision retained");check(metadata.header().geometry().frontiers().size()==22,"retry does not claim unknown frontiers closed");}
        check(retry(store,loaded).equals(hints),"repeated chunk reload remains usable and deterministic");
        backpressure(store,loaded,hints);
        growthAfterReload(embedding);
        System.out.println("SourceFrontierReloadTest: "+checks+" checks passed; compressed fixture retained at "+path);
    }
}
