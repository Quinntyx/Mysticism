package io.github.mysticism.landmark;

import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Real compressed catalogue and production bounded ownership traversal; no game or model startup. */
public final class DimensionOwnershipChecks {
    public static final String LOCAL="minecraft:overworld", FOREIGN="minecraft:the_nether";
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static Landmark landmark(String dimension,long x) {
        var anchor=new BlockPoint(x,0,0);var bounds=Bounds.cube(x,0,0,4);
        float[] values=new float[EmbeddingSpace.DIMENSIONS];values[0]=1;
        var embedding=new LandmarkEmbedding(new EmbeddingProfile("dimension-fixture","v2","fixture-tokenizer","fixture",EmbeddingSpace.DIMENSIONS,EmbeddingProfile.Normalization.UNIT,"dimension-fixture-v2"),new Vec384f(values));
        var palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));
        var cells=SparseOctree.<BlockSample>empty(bounds,1,4)
                .with(Bounds.cube(x,0,0,1),new BlockSample(BlockSample.Occupancy.SOLID,1),32)
                .with(Bounds.cube(x+1,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,0),32);
        var page=new GeometryPage(LandmarkIds.geometryPage(dimension,anchor,4),0,bounds,palette,cells);
        return new Landmark(LandmarkIds.seed(dimension,"dimension-regression",Landmark.Kind.CAVE,"minecraft:plains",anchor),
                dimension,"dimension-regression",Landmark.Kind.CAVE,"minecraft:plains",anchor,bounds,embedding,.1,
                new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(page),List.of()),0,"dimension regression fixture");
    }
    public static LandmarkMetadata metadata(Landmark l) {
        var header=new Landmark(l.id(),l.dimension(),l.algorithmVersion(),l.kind(),l.biome(),l.anchor(),l.bounds(),l.baseEmbedding(),
                l.baseImportance(),l.activity(),l.ownership(),new SourceGeometry(List.of(),List.of()),l.revision(),l.provenance());
        return new LandmarkMetadata(header,List.of("mysticism.landmark.geometry."+l.geometry().pages().getFirst().id()+".0"));
    }
    private static PersistentStateManager manager(Path path) {
        DataFixer identity=new DataFixer(){
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
            public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    private static SourceOwnership.Region owners(LandmarkStore store,String dimension) {
        var result=new CompletableFuture<SourceOwnership.Region>();
        var request=new SourceOwnership.Request(store,dimension,Bounds.cube(0,0,0,4),result);
        for(int tick=0;!result.isDone() && tick<2000;tick++)request.advance();
        check(result.isDone(),"bounded ownership traversal terminates");
        return result.join();
    }
    public static int run()throws Exception {
        SharedConstants.createGameVersion();Path path=Files.createTempDirectory("dimension-discovery-");
        var manager=manager(path);var store=LandmarkStore.open(manager,path);
        var local=landmark(LOCAL,0);var foreign=landmark(FOREIGN,0);
        for(var l:List.of(local,foreign)){var put=store.stagePut(l,-1);while(!put.complete())put.advance(8);}
        for(int i=1;i<=160;i++){var put=store.stagePut(landmark(FOREIGN,i*4L),-1);while(!put.complete())put.advance(8);}
        manager.save();store=LandmarkStore.open(manager(path),path); // force cold metadata/geometry reads
        check(store.metadata(local.id(),LOCAL).isPresent(),"local binding survives disk reload");
        check(store.metadata(foreign.id(),LOCAL).isEmpty(),"foreign target/restored binding rejected at identical source coordinates");
        check(store.metadata(foreign.id(),FOREIGN).isPresent(),"explicit correctly namespaced foreign target remains valid");
        Set<String> seen=new HashSet<>();int scanned=0,evaluated=0,pages=0;String cursor=null;boolean end=false;
        int[] evaluations={0};
        while(!end){
            var page=store.semanticRangePage(LOCAL,local.baseEmbedding(),1.5,cursor,2,4,m->{
                check(m.header().dimension().equals(LOCAL),"foreign metadata never invokes activity embedding callback");
                evaluations[0]++;return m.header().baseEmbedding();
            });
            check(page.scanned()<=4 && page.landmarks().size()<=2,"semantic per-step budgets");
            check(page.nextId()!=null && (cursor==null || page.nextId().compareTo(cursor)>0),"exclusive seek advances even over foreign-only pages");
            for(var m:page.landmarks())check(seen.add(m.id()),"no duplicate semantic admission");
            scanned+=page.scanned();cursor=page.nextId();end=page.end();check(++pages<200,"finite semantic scan");
        }
        evaluated=evaluations[0];
        check(scanned==162 && evaluated==1 && seen.equals(Set.of(local.id())),"catalogue >128 cannot leak identical foreign vectors or starve local result");
        check(store.cacheStats().reconstructedLeaves()==0,"semantic admission never hydrates geometry");
        var absent=store.semanticRangePage("minecraft:the_end",local.baseEmbedding(),1.5,null,2,4,m->{throw new AssertionError("foreign evaluation");});
        check(absent.scanned()==4 && absent.landmarks().isEmpty() && !absent.end(),"empty namespace still makes bounded progress");
        var localOwners=owners(store,LOCAL);var foreignOwners=owners(store,FOREIGN);
        check(localOwners.ownerAt(LOCAL,BlockPos.ORIGIN).orElseThrow().equals(local.id()),"local SOLID mask owns support");
        check(localOwners.ownerAt(LOCAL,new BlockPos(1,0,0)).orElseThrow().equals(local.id()),"local AIR belongs to same exact owner");
        check(localOwners.ownerAt(LOCAL,new BlockPos(2,0,0)).isEmpty(),"unobserved source mask never becomes support");
        check(localOwners.ownerAt(FOREIGN,BlockPos.ORIGIN).isEmpty(),"foreign window cannot borrow local snapshot");
        check(foreignOwners.ownerAt(FOREIGN,BlockPos.ORIGIN).orElseThrow().equals(foreign.id()),"identical source coordinates have independent dimension owners");
        check(foreignOwners.ownerAt(LOCAL,BlockPos.ORIGIN).isEmpty(),"foreign snapshot cannot grant local collision/exit authority");
        check(store.geometryReadAvailable(),"ownership traversal releases geometry reader");
        System.out.println("DimensionOwnershipChecks: "+checks+" checks; compressed fixture retained at "+path);
        return checks;
    }
}
