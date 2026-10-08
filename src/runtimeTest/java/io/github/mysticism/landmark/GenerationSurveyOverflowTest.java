package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.LandmarkProfiles;
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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** More distinct generation probes than the recency budget arrive before readiness.
 * Drain the real generation queue through prepare + paged store publication, then cold
 * reload and inspect exact octree ownership of the EARLIEST non-center biome patch.
 * No player hints, cave retries, live model/service, or game bootstrap can rescue it. */
public final class GenerationSurveyOverflowTest {
    private static int checks;
    private static final String DIM="minecraft:overworld";
    private static final BlockPalette.State STONE=new BlockPalette.State("minecraft:stone",Map.of());
    private static final BlockPalette.State AIR=new BlockPalette.State("minecraft:air",Map.of());
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static String biome(int x){return x<8?"minecraft:forest":"minecraft:desert";}
    private static List<BlockPos> probes(int chunkX){
        return SourceLandmarks.surveyHints(GenerationSurvey.survey(chunkX*16,0,-64,320,new GenerationSurvey.ColumnView(){
            public int height(int x,int z){return 64;}
            public String biome(int x,int z,int y){return GenerationSurveyOverflowTest.biome(x);}
        }));
    }
    private static SourceLandmarks.Region terrain(int chunkX){
        int start=chunkX*16;var cells=new ArrayList<SourceLandmarks.Cell>();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=64;y<=65;y++)
            cells.add(new SourceLandmarks.Cell(new BlockPoint(start+x,y,z),y==64?STONE:AIR,biome(x),y==65));
        return new SourceLandmarks.Region(DIM,new Bounds(start,64,0,start+16,66,16),cells,true);
    }
    private static PersistentStateManager manager(Path path){
        DataFixer identity=new DataFixer(){
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
            public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    private static SourceOwnership.Region owners(LandmarkStore store,Bounds area){
        var future=new CompletableFuture<SourceOwnership.Region>();
        var request=new SourceOwnership.Request(store,DIM,area,future);int steps=0;
        while(!request.done){request.advance();check(++steps<20000,"bounded ownership lookup converges");}
        return future.join();
    }
    private static void delayedReadinessPublishesEarlierNonCenterTerrain()throws Exception{
        var work=new GenerationSurveyQueue();var hints=new LinkedHashSet<BlockPos>();
        int chunkCount=SourceLandmarks.HINT_BUDGET/2+17;
        for(int i=0;i<chunkCount;i++){
            int chunkX=i*4;var surveyed=probes(chunkX);
            check(surveyed.size()==2,"two actual surface biome components per source chunk");
            work.loaded(DIM,chunkX,0,surveyed);
            // Reproduce the former admission loss: both survey probes and the center share
            // the recency budget. The new generation path does NOT depend on that set.
            SourceLandmarks.admit(hints,new BlockPos(chunkX*16+8,65,8),SourceLandmarks.HINT_BUDGET);
            for(var p:surveyed)SourceLandmarks.admit(hints,p,SourceLandmarks.HINT_BUDGET);
            check(work.next(false).isEmpty(),"no dispatch before embedding/index readiness");
        }
        BlockPos earliestForest=probes(0).getFirst();
        check(!hints.contains(earliestForest),"fixture really evicts the earlier non-center probe");
        check(work.pending()==2*chunkCount&&work.pending()>SourceLandmarks.HINT_BUDGET,"all unsatisfied probes survive delayed readiness beyond 256");
        check(work.chunks()==chunkCount,"storage bounded to the still-loaded chunks, not historical generation");
        var center=SourceLandmarks.prepare(terrain(0),List.of(),new BlockPos(8,65,8),null,false,0,63,Map.of());
        check(center!=null&&center.geometry().sample(earliestForest.getX(),65,earliestForest.getZ())==null,"seed-biome center preparation cannot own the forest patch");
        SharedConstants.createGameVersion();Path path=Files.createTempDirectory("generation-overflow-");
        var states=manager(path);var store=LandmarkStore.open(states,path);store.clearGeneratedIfIncompatible(LandmarkProfiles.current());
        var before=owners(store,new Bounds(0,64,0,16,67,16));
        check(before.owners().isEmpty(),"no synthetic ownership while readiness is blocked");
        float[] unit=new float[EmbeddingSpace.DIMENSIONS];unit[0]=1;
        var embedding=LandmarkProfiles.wrap(new Vec384f(unit));
        Map<BlockPos,String> publishedIds=new LinkedHashMap<>();int attempts=0;
        while(work.pending()>0){
            var attempt=work.next(true).orElseThrow();
            var p=SourceLandmarks.prepare(terrain(attempt.chunk().x()),List.of(),attempt.position(),null,false,0,63,Map.of());
            check(p!=null,"retained generation probe prepares actual terrain");
            var landmark=new Landmark(p.id(),DIM,SourceLandmarks.ALGORITHM,p.kind(),p.biome(),p.anchor(),OwnershipGeometry.bounds(p.anchor(),p.geometry()),embedding,.08,new ActivityMetadata(0,0),new Ownership(List.of()),p.geometry(),0,"generation overflow regression");
            var mutation=store.stagePut(landmark,-1);int steps=0;
            while(!mutation.complete()){mutation.advance(1,512);check(++steps<200,"paged generation publication converges");}
            var pos=attempt.position();boolean ownsProbe=landmark.geometry().sample(pos.getX(),pos.getY(),pos.getZ())!=null;
            check(ownsProbe,"publication must contain exact known AIR/SOLID probe, not only an AABB");
            work.completed(attempt,ownsProbe);publishedIds.put(pos,landmark.id());
            check(++attempts<=2*chunkCount,"one successful attempt per surveyed component");
        }
        check(work.chunks()==0&&attempts==2*chunkCount,"generation debt drains without any entry/player hints");
        states.save();var cold=LandmarkStore.open(manager(path),path);
        check(cold.usesNativeSourceProfile(LandmarkProfiles.current()),"fresh native v2 profile survives cold reload");
        for(var entry:publishedIds.entrySet()){
            var read=cold.beginGeometryRead(entry.getValue());var pages=new ArrayList<GeometryPage>();
            while(!read.complete()){read.advance(1,512);pages.addAll(read.drain());}
            var pos=entry.getKey();var geometry=new SourceGeometry(pages,List.of());
            check(geometry.sample(pos.getX(),pos.getY(),pos.getZ())!=null,"every retained component has persisted exact ownership after cold reload");
        }
        var persisted=owners(cold,new Bounds(0,64,0,16,67,16));
        String forestId=publishedIds.get(earliestForest);
        check(persisted.ownerAt(earliestForest).orElseThrow().equals(forestId),"earliest untouched non-center terrain receives persisted ownership");
        check(persisted.ownerAt(earliestForest.down()).orElseThrow().equals(forestId),"real solid floor and surveyed air have the same persisted source owner");
        check(persisted.ownerAt(earliestForest.up()).isEmpty(),"unobserved cells inside page AABBs remain unknown/unowned");
        System.out.println("Generation overflow persisted fixture: "+path);
    }
    private static void failuresRotateAndUnloadsBoundRetention(){
        var work=new GenerationSurveyQueue();work.loaded(DIM,0,0,probes(0));work.loaded(DIM,4,0,probes(4));
        var first=work.next(true).orElseThrow();work.completed(first,false);
        check(work.pending()==4,"failed or empty publication retains both component probes");
        var other=work.next(true).orElseThrow();
        check(other.chunk().x()==4,"failed earliest patch cannot starve other chunks");
        work.completed(other,true);work.completed(other,true);
        check(work.pending()==3,"duplicate completion cannot acknowledge the next component");
        var sibling=work.next(true).orElseThrow();
        check(!sibling.position().equals(first.position())&&sibling.chunk().equals(first.chunk()),"failed component cannot starve a different biome in the same chunk");
        work.completed(sibling,true);
        var otherSibling=work.next(true).orElseThrow();work.completed(otherSibling,true);
        var retry=work.next(true).orElseThrow();
        check(retry.position().equals(first.position()),"failed component is retried at its actual terrain column");
        work.unloaded(DIM,0,0);work.loaded(DIM,0,0,probes(0));work.completed(retry,true);
        check(work.pending()==2,"late completion before unload/reload cannot erase new survey work");
        work.loaded("minecraft:the_nether",0,0,probes(0));work.unloaded(DIM);
        check(work.pending()==2&&work.chunks()==1,"dimension unload removes only its own loaded-chunk debt");
        work.unloaded("minecraft:the_nether",0,0);
        check(work.pending()==0&&work.next(true).isEmpty(),"chunk unload removes retained work without requiring readiness");
        work.loaded(DIM,0,0,probes(0));work.loaded(DIM,0,0,List.of());
        check(work.pending()==0,"empty replacement survey retires old chunk generation");
        var mutable=new ArrayList<>(probes(0));work.loaded(DIM,0,0,mutable);mutable.clear();
        check(work.pending()==2,"queue owns an immutable bounded copy, not caller storage");
        try{work.loaded(DIM,0,0,Collections.nCopies(GenerationSurvey.MAX_PROBES+1,new BlockPos(0,65,0)));throw new AssertionError("over-budget chunk survey accepted");}
        catch(IllegalArgumentException expected){checks++;}
        try{work.loaded(DIM,0,0,probes(4));throw new AssertionError("foreign chunk probe accepted");}
        catch(IllegalArgumentException expected){checks++;}
        work.clear();check(work.pending()==0&&work.chunks()==0,"server stop clears all retained generation work");
    }
    public static void main(String[] args)throws Exception{
        failuresRotateAndUnloadsBoundRetention();delayedReadinessPublishesEarlierNonCenterTerrain();
        System.out.println("GenerationSurveyOverflowTest passed: "+checks+" checks");
    }
}
