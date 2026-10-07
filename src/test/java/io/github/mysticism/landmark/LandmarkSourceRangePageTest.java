package io.github.mysticism.landmark;

import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Real paged store, including cold compressed reload; no synthetic catalogue API. */
public final class LandmarkSourceRangePageTest {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public record Fixture(LandmarkStore store,Path path,Set<String> localIds){}
    private static PersistentStateManager manager(Path path){
        DataFixer identity=new DataFixer(){
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
            public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    public static Fixture fixture(EmbeddingProfile profile)throws Exception{
        SharedConstants.createGameVersion();Path path=Files.createTempDirectory("landmark-source-page-");var manager=manager(path);
        var store=LandmarkStore.open(manager,path);Set<String> locals=new TreeSet<>();
        float[] data=new float[EmbeddingSpace.DIMENSIONS];data[0]=1;var embedding=new LandmarkEmbedding(profile,new Vec384f(data));
        for(int i=0;i<320;i++){
            // 12 overlaps, a strict-radius boundary, a cube-corner false positive, and a foreign dimension.
            String dimension=i==14?"minecraft:the_nether":"minecraft:overworld";
            long x=i<12?0:i==12?24:i==13?23:i==14?0:1000L+i*8;
            long z=i==13?23:0;var anchor=new BlockPoint(i<12?i%8:x,i<12?i/8:0,z);var bounds=Bounds.cube(x,0,z,8);
            String id=LandmarkIds.seed(dimension,"page-test",Landmark.Kind.CAVE,"minecraft:plains",anchor);
            var landmark=new Landmark(id,dimension,"page-test",Landmark.Kind.CAVE,"minecraft:plains",anchor,bounds,embedding,.1,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),0,"source page fixture");
            var mutation=store.stagePut(landmark,-1);while(!mutation.complete())mutation.advance(8);
            if(i<12)locals.add(id);
        }
        manager.save();return new Fixture(LandmarkStore.open(manager(path),path),path,Set.copyOf(locals));
    }
    public static void main(String[] args)throws Exception{
        var profile=new EmbeddingProfile("test","1","test","none",EmbeddingSpace.DIMENSIONS,EmbeddingProfile.Normalization.UNIT,"page-test");
        var fixture=fixture(profile);var store=fixture.store();Bounds range=new Bounds(-24,-24,-24,25,25,25);
        String cursor=null;Set<String> seen=new HashSet<>();int scanned=0,pages=0;boolean end=false;
        while(!end){
            var page=store.sourceRangePage("minecraft:overworld",range,cursor,2,4);pages++;
            check(page.scanned()<=4&&page.landmarks().size()<=2,"per-page read/result budget");
            for(var m:page.landmarks()){check(seen.add(m.id()),"exclusive continuation never repeats a result");check(m.header().dimension().equals("minecraft:overworld"),"dimension gate");}
            scanned+=page.scanned();cursor=page.nextId();end=page.end();check(pages<400,"finite catalogue progress");
        }
        check(scanned==320,"every catalogue record eventually scanned despite catalogue >128");
        check(seen.size()==14&&seen.containsAll(fixture.localIds()),">8 source overlaps retained across pages");
        check(store.cacheStats().reconstructedLeaves()==0,"cold metadata paging never hydrates geometry");
        var empty=store.sourceRangePage("minecraft:the_end",range,null,1,4);check(empty.scanned()==4&&!empty.end(),"empty local page still advances");
        String deleted=store.ids().get(100);var metadata=store.metadata(deleted).orElseThrow();var deletion=store.stageDelete(new LandmarkRepository.RevisionRef(deleted,metadata.revision()));while(!deletion.complete())deletion.advance(8);
        var resumed=store.sourceRangePage("minecraft:overworld",range,deleted,1,4);check(resumed.scanned()>0&&resumed.nextId().compareTo(deleted)>0,"deleted seek cursor still progresses");
        for(int budget:new int[]{0,129}){
            try{store.sourceRangePage("minecraft:overworld",range,null,1,budget);throw new AssertionError("bad scan budget accepted");}catch(IllegalArgumentException expected){checks++;}
            try{store.sourceRangePage("minecraft:overworld",range,null,budget,4);throw new AssertionError("bad result budget accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        try{store.sourceRange("minecraft:overworld",range,8,128);throw new AssertionError("legacy range contract changed");}catch(IllegalArgumentException expected){checks++;}
        Path emptyPath=Files.createTempDirectory("landmark-empty-page-");var emptyStore=LandmarkStore.open(manager(emptyPath),emptyPath);
        var emptyPage=emptyStore.sourceRangePage("minecraft:overworld",range,null,1,4);check(emptyPage.end()&&emptyPage.scanned()==0&&emptyPage.landmarks().isEmpty(),"empty catalogue terminates immediately");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{store.sourceRangePage("minecraft:overworld",range,null,1,4);return false;}catch(IllegalStateException expected){return true;}}).get(5,java.util.concurrent.TimeUnit.SECONDS),"thread confinement retained");
        System.out.println("LandmarkSourceRangePageTest: "+checks+" checks passed; compressed fixture retained at "+fixture.path());
    }
}
