package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import java.util.*;

/** Production global graph and actual repository CAS/alias/split transactions, no model fixture IO. */
public final class BoundaryCavesSelfTest {
    static int checks;static final String DIM="minecraft:overworld",BIOME="minecraft:lush_caves";
    static final ExtractionGraph.Observation ROCK=new ExtractionGraph.Observation(new BlockPalette.State("minecraft:stone",Map.of()),BIOME,"minecraft:stone",false,false,100,63);
    static final ExtractionGraph.Observation AIR=new ExtractionGraph.Observation(new BlockPalette.State("minecraft:cave_air",Map.of()),BIOME,"minecraft:air",true,false,100,63);
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static Bounds root(int domain){return Bounds.cube(8+32L*domain,-32,8,32);}
    static ExtractionGraph.Observation[] cells(){var c=new ExtractionGraph.Observation[ExtractionGraph.MAX_CELLS];Arrays.fill(c,ROCK);for(int x=0;x<32;x++)c[ExtractionGraph.index(x,10,10)]=AIR;return c;}
    static List<ExtractionGraph.Feature> graph(int domain,ExtractionGraph.Observation[] c,List<ExtractionGraph.Seed> seeds,long version,Set<String> retired){var g=new ExtractionGraph(DIM,root(domain),c,seeds,version,retired);while(!g.complete())check(g.advance(127)<=127,"resumable bounded local graph");return g.finish();}
    static Landmark value(ExtractionGraph.Feature f,long revision){float[] v=new float[EmbeddingSpace.DIMENSIONS];v[0]=1;Bounds b=Bounds.cube(f.anchor().x(),f.anchor().y(),f.anchor().z(),1);for(var p:f.geometry().pages())b=b.union(p.bounds());return new Landmark(f.id(),DIM,f.algorithmVersion(),f.kind(),f.biome(),f.anchor(),b,LandmarkProfiles.wrap(new Vec384f(v)),f.importance(),new ActivityMetadata(0,0),new Ownership(List.of()),f.geometry(),revision,"production boundary fixture");}
    static List<Landmark> domains(){List<Landmark> values=new ArrayList<>();for(int i=0;i<3;i++)values.add(value(graph(i,cells(),List.of(),1,Set.of()).getFirst(),0));return values;}
    static Landmark merge(int[] order,List<Landmark> input){var repo=new LandmarkRepository();for(int i:order){repo.put(input.get(i),-1);while(true){var union=BoundaryCaves.stitch(repo.landmarks());if(union.isEmpty())break;var u=union.get();repo.mergeVerified(u.proof(),20,new ImportancePolicy(1,12000,.001,.5),u.geometry());}}check(repo.landmarks().size()==1,"three arbitrary adjacent domains become ONE cave");for(var source:input)check(repo.resolve(source.id()).equals(repo.landmarks().getFirst().id()),"persistable canonical merge aliases");return repo.landmarks().getFirst();}
    static List<ExtractionGraph.Seed> seeds(Landmark l){return List.of(new ExtractionGraph.Seed(l.id(),l.kind(),l.biome(),l.anchor(),l.geometry(),l.algorithmVersion()));}
    public static void main(String[] args){
        var input=domains();var a=merge(new int[]{0,1,2},input);var b=merge(new int[]{2,0,1},input);
        check(a.id().equals(b.id())&&a.anchor().equals(b.anchor()),"discovery order canonical seed/position determinism with aliases");
        check(a.provisional(),"unresolved domain/unknown frontier never falsely closes");
        for(int x=8;x<104;x++)check(a.geometry().sample(x,-22,18).occupancy()==BlockSample.Occupancy.AIR,"actual contiguous air across at least THREE domains");
        var middle=input.get(1);var foreign=new Landmark(middle.id(),middle.dimension(),middle.algorithmVersion(),middle.kind(),middle.biome(),middle.anchor(),middle.bounds(),middle.baseEmbedding(),middle.baseImportance(),middle.activity(),middle.ownership(),middle.geometry(),middle.revision(),middle.provenance());
        // Construct different-biome/dimension source identities correctly, not an inconsistent header.
        String biome="minecraft:dripstone_caves";String id=LandmarkIds.seed(DIM,foreign.algorithmVersion(),foreign.kind(),biome,foreign.anchor());
        foreign=new Landmark(id,DIM,foreign.algorithmVersion(),foreign.kind(),biome,foreign.anchor(),foreign.bounds(),foreign.baseEmbedding(),foreign.baseImportance(),foreign.activity(),foreign.ownership(),foreign.geometry(),0,"biome cutoff");
        check(BoundaryCaves.stitch(List.of(input.getFirst(),foreign,input.getLast())).isEmpty(),"lush/adjoining other cave is NOT merged through foreign biome");
        String nether="minecraft:the_nether";id=LandmarkIds.seed(nether,middle.algorithmVersion(),middle.kind(),middle.biome(),middle.anchor());
        var differentDimension=new Landmark(id,nether,middle.algorithmVersion(),middle.kind(),middle.biome(),middle.anchor(),middle.bounds(),middle.baseEmbedding(),middle.baseImportance(),middle.activity(),middle.ownership(),new SourceGeometry(middle.geometry().pages(),middle.geometry().frontiers().stream().map(f->new FrontierFace(nether,f.missingBounds(),f.direction(),f.sourceRevision(),f.cursor())).toList()),0,"dimension cutoff");
        check(BoundaryCaves.stitch(List.of(input.getFirst(),differentDimension)).isEmpty(),"dimension cutoff");
        check(BoundaryCaves.stitch(List.of(input.getFirst(),input.getLast())).isEmpty(),"disconnected masks/AABB proximity never proof");
        var c=cells();var prior=seeds(a);Set<String> retired=new HashSet<>();for(var l:input)retired.add(l.id());
        var unchanged=BoundaryCaves.revise(DIM,root(1),c,graph(1,c,prior,2,retired),prior,2,retired);check(unchanged.size()==1,"local rescan retains remote whole cave");
        for(int x=8;x<104;x++)check(unchanged.getFirst().geometry().sample(x,-22,18).occupancy()==BlockSample.Occupancy.AIR,"no truncation to edited domain");
        c[ExtractionGraph.index(16,10,10)]=ROCK;
        var split=BoundaryCaves.revise(DIM,root(1),c,graph(1,c,prior,3,retired),prior,3,retired);check(split.size()==2,"middle-domain cut splits GLOBAL graph");
        var repo=new LandmarkRepository();repo.put(a,-1);List<Landmark> children=split.stream().map(f->value(f,a.revision()+1)).toList();repo.split(new LandmarkRepository.RevisionRef(a.id(),a.revision()),children);
        check(repo.snapshot().lineage().get(a.id()).size()==2,"actual repository split lineage");check(BoundaryCaves.stitch(children).isEmpty(),"removed air is not restitched");
        for(var child:children){var sample=child.geometry().sample(56,-22,18);check(sample==null||sample.occupancy()!=BlockSample.Occupancy.AIR,"removed bridge absent");}
        var reloaded=LandmarkRepository.restore(repo.snapshot());check(reloaded.landmarks().stream().map(Landmark::id).toList().equals(repo.landmarks().stream().map(Landmark::id).toList()),"snapshot reload IDs/lineage survive");
        Arrays.fill(c,ROCK);var removed=BoundaryCaves.revise(DIM,root(1),c,graph(1,c,prior,4,retired),prior,4,retired);check(removed.size()==2,"domain air removal retains separated remote observations");
        c=cells();c[ExtractionGraph.index(10,10,10)]=null;boolean deferred=false;try{BoundaryCaves.revise(DIM,root(1),c,graph(1,c,prior,5,retired),prior,5,retired);}catch(IllegalStateException expected){deferred=true;}check(deferred,"unloaded known bridge defers rather than false split/closure");
        c=cells();var restored=BoundaryCaves.revise(DIM,root(1),c,graph(1,c,prior,6,retired),prior,6,retired);check(restored.size()==1&&restored.getFirst().id().equals(a.id()),"reload/retry preserves canonical seed");
        List<Landmark> excessive=new ArrayList<>();for(int i=0;i<=BoundaryCaves.MAX_FRAGMENTS;i++)excessive.add(input.getFirst());deferred=false;try{BoundaryCaves.stitch(excessive);}catch(IllegalStateException expected){deferred=true;}check(deferred,"explicit global resource rejection");
        System.out.println("BoundaryCavesSelfTest: "+checks+" checks passed");
    }
}
