package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import java.util.*;

/** Exercises the actual production graph and profile bridge, not a substitute flood-fill. */
public final class ExtractionSelfTest {
    private static int checks;
    private static final Bounds ROOT=Bounds.cube(8,-32,8,32);
    private static final String DIM="minecraft:overworld", LUSH="minecraft:lush_caves", OTHER="minecraft:dripstone_caves";
    private static final BlockPalette.State STONE=new BlockPalette.State("minecraft:stone",Map.of());
    private static final BlockPalette.State AIR=new BlockPalette.State("minecraft:cave_air",Map.of());
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ExtractionGraph.Observation solid(){return new ExtractionGraph.Observation(STONE,LUSH,"minecraft:stone",false,false,100,63);}
    private static ExtractionGraph.Observation air(String biome,boolean sky){return new ExtractionGraph.Observation(AIR,biome,"minecraft:air",true,sky,100,63);}
    private static ExtractionGraph.Observation[] rock(){var cells=new ExtractionGraph.Observation[ExtractionGraph.MAX_CELLS];Arrays.fill(cells,solid());return cells;}
    private static void set(ExtractionGraph.Observation[] cells,int x,int y,int z,ExtractionGraph.Observation value){cells[ExtractionGraph.index(x,y,z)]=value;}
    private static List<ExtractionGraph.Feature> run(ExtractionGraph.Observation[] cells,List<ExtractionGraph.Seed> seeds,long revision,int budget){
        ExtractionGraph graph=new ExtractionGraph(DIM,ROOT,cells,seeds,revision);long work=0;
        while(!graph.complete()){int used=graph.advance(budget);check(used>=0 && used<=budget && (used>0 || graph.complete()),"resumable graph work budget");work+=used;}
        check(work<5L*ExtractionGraph.MAX_CELLS,"bounded total graph expansions");return graph.finish();
    }
    private static ExtractionGraph.Seed seed(ExtractionGraph.Feature feature){return new ExtractionGraph.Seed(feature.id(),feature.kind(),feature.biome(),feature.anchor(),feature.geometry(),feature.algorithmVersion());}
    public static void main(String[] args){
        // Absolute x=15 and x=16 lie in different vanilla source chunks. The production region is offset,
        // and flood fill has no chunk-local identity or adjacency shortcuts.
        var cells=rock();for(int x=6;x<=12;x++)set(cells,x,10,10,air(LUSH,false));
        var initial=run(cells,List.of(),1,257);check(initial.size()==1,"cave crosses vanilla chunk boundary");
        var cave=initial.getFirst();check(cave.airCells()==7,"entire connected cave air observed");
        check(cave.geometry().frontierClosed(),"observed solid enclosure closes frontier");
        check(cave.anchor().equals(new BlockPoint(14,-22,18)),"deterministic absolute source seed");
        check(cave.geometry().sample(15,-22,18).occupancy()==BlockSample.Occupancy.AIR,"left chunk air");
        check(cave.geometry().sample(16,-22,18).occupancy()==BlockSample.Occupancy.AIR,"right chunk air");
        var reordered=run(cells,List.of(),2,1);check(reordered.getFirst().id().equals(cave.id()),"budget/order independent source ID");
        check(reordered.getFirst().geometry().pages().stream().allMatch(p->p.revision()==2),"revisable immutable page versions");

        // Lush and dripstone can touch physically; semantic cave ownership must stop at the biome key.
        for(int x=10;x<=12;x++)set(cells,x,10,10,air(OTHER,false));
        var cutoff=run(cells,List.of(),3,256);check(cutoff.size()==2,"strict adjoining biome cutoff");
        check(cutoff.stream().filter(f->f.biome().equals(LUSH)).findFirst().orElseThrow().airCells()==4,"lush fragment stops at registry key");
        noOverlap(cutoff);
        // Sky evidence propagates through the other biome, preventing a falsely enclosed lush cave.
        set(cells,12,10,10,air(OTHER,true));check(run(cells,List.of(),4,256).isEmpty(),"outside air crosses biome keys for enclosure proof");
        set(cells,12,10,10,air(OTHER,false));set(cells,13,10,10,null);
        var unknown=run(cells,List.of(),5,256);check(unknown.stream().allMatch(f->!f.geometry().frontierClosed()),"unknown through adjoining biome never sealed");
        check(unknown.stream().allMatch(f->f.airCells()<8),"unknown is not fabricated air");
        set(cells,13,10,10,solid());check(run(cells,List.of(),6,256).stream().allMatch(f->f.geometry().frontierClosed()),"frontier retry closes after actual wall observation");

        // Disconnected cave masks sharing the same observation page are independently versioned and disjoint.
        cells=rock();set(cells,2,10,2,air(LUSH,false));set(cells,5,10,2,air(LUSH,false));
        var disconnected=run(cells,List.of(),7,256);check(disconnected.size()==2,"disconnected components in shared cube");
        var p0=disconnected.get(0).geometry().pages().getFirst();var p1=disconnected.get(1).geometry().pages().getFirst();
        check(p0.bounds().equals(p1.bounds()) && !p0.id().equals(p1.id()),"shared page bounds have fragment-specific IDs");noOverlap(disconnected);
        set(cells,3,10,2,air(LUSH,false));set(cells,4,10,2,air(LUSH,false));
        var merged=run(cells,disconnected.stream().map(ExtractionSelfTest::seed).toList(),8,256);
        check(merged.size()==1 && merged.getFirst().parents().size()==2,"observed graph detects physical merge, not semantic similarity");
        var parent=merged.getFirst();set(cells,3,10,2,solid());
        var split=run(cells,List.of(seed(parent)),9,256);check(split.size()==2 && split.stream().allMatch(f->f.parents().equals(List.of(parent.id()))),"mask cut establishes split lineage");
        check(split.stream().anyMatch(f->f.id().equals(parent.id())),"child containing original anchor keeps ID");

        // Expansion to a lexically earlier cell must not move a previously committed source anchor.
        cells=rock();set(cells,12,10,10,air(LUSH,false));var saved=run(cells,List.of(),10,256).getFirst();
        set(cells,11,10,10,air(LUSH,false));var expanded=run(cells,List.of(seed(saved)),11,256).getFirst();
        check(expanded.id().equals(saved.id()) && expanded.anchor().equals(saved.anchor()),"stable seed and position across source growth/reload seed");
        set(cells,12,10,10,solid());var filledAnchor=run(cells,List.of(seed(expanded)),12,256).getFirst();
        check(filledAnchor.id().equals(saved.id()) && filledAnchor.anchor().equals(saved.anchor()),"one-to-one source edit keeps a now-filled anchor");
        String fingerprint=ObservationFingerprints.of(cells);check(fingerprint.equals(ObservationFingerprints.of(cells.clone())),"snapshot fingerprint stable across copies/reload");
        set(cells,12,10,10,air(LUSH,false));check(!fingerprint.equals(ObservationFingerprints.of(cells)),"source edits change snapshot fingerprint");
        set(cells,12,10,10,null);check(!fingerprint.equals(ObservationFingerprints.of(cells)),"unload/unknown changes retry fingerprint");
        set(cells,12,10,10,air(OTHER,false));check(!fingerprint.equals(ObservationFingerprints.of(cells)),"biome transition changes fingerprint");
        // Whole unknown snapshots never create air or landmarks.
        check(run(new ExtractionGraph.Observation[ExtractionGraph.MAX_CELLS],List.of(),12,256).isEmpty(),"missing chunks are not empty cave terrain");

        // Actual sampled terrain heights, not biome names, distinguish high mountains from surface observations.
        cells=rock();for(int x=0;x<32;x++)for(int z=0;z<32;z++)set(cells,x,31,z,new ExtractionGraph.Observation(STONE,"minecraft:plains","minecraft:stone",false,false,-1,-100));
        var mountain=run(cells,List.of(),13,256);check(mountain.size()==1 && mountain.getFirst().kind()==Landmark.Kind.MOUNTAIN,"actual elevated surface classification");
        check(mountain.getFirst().solidCells()==1024,"important mountain gets block refinement");
        for(int x=0;x<32;x++)for(int z=0;z<32;z++)set(cells,x,31,z,new ExtractionGraph.Observation(STONE,"minecraft:plains","minecraft:stone",false,false,-1,0));
        var plain=run(cells,List.of(),14,256);check(plain.getFirst().kind()==Landmark.Kind.BIOME && plain.getFirst().solidCells()==64,"coarse surface sampling resource bound");

        cells=rock();for(int x=1;x<31;x+=2)for(int y=1;y<31;y+=2)for(int z=1;z<31;z+=2)set(cells,x,y,z,air(LUSH,false));
        var crowded=new ExtractionGraph(DIM,ROOT,cells,List.of(),15);while(!crowded.complete())check(crowded.advance(256)<=256,"overflow remains resumable");check(crowded.overflow(),"component resource overflow detected before service publishes partial graph");

        float[] values=new float[EmbeddingSpace.DIMENSIONS];values[0]=1;Vec384f vector=new Vec384f(values);
        LandmarkEmbedding wrapped=LandmarkProfiles.wrap(vector);vector.mul(0);
        check(wrapped.data()[0]==1,"profile wrapper snapshots mutable vector");
        var profile=LandmarkProfiles.current();check(profile.dimensions()==256 && profile.revision().equals(EmbeddingSpace.REVISION),"pinned real Nomic profile and native reduction");
        check(profile.tokenizer().contains(EmbeddingSpace.REVISION) && profile.descriptorSchema().equals(EmbeddingSpace.FINGERPRINT),"tokenizer/descriptor fingerprint retained");
        try{LandmarkProfiles.wrap(new Vec384f(values,"foreign"));throw new AssertionError("foreign fingerprint accepted");}catch(IllegalArgumentException expected){checks++;}
        try{LandmarkProfiles.wrap(Vec384f.ZERO());throw new AssertionError("fake zero accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("ExtractionSelfTest: "+checks+" checks passed");
    }
    private static void noOverlap(List<ExtractionGraph.Feature> features){
        Set<BlockPoint> known=new HashSet<>();for(var f:features)for(var p:f.geometry().pages())for(var cell:p.cells().cells(32768)){
            var b=cell.bounds();for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++)check(known.add(new BlockPoint(x,y,z)),"conflicting ownership masks");
        }
    }
}
