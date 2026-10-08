package io.github.mysticism.landmark;

import io.github.mysticism.landmark.GenerationSurvey.ColumnView;
import io.github.mysticism.landmark.GenerationSurvey.Kind;
import io.github.mysticism.landmark.GenerationSurvey.Probe;
import java.util.*;

/** Survey-to-hint integration regression: distinct surveyed terrain components must become
 * pending landmark hints. Distance to the chunk-center probe is never a coverage proof —
 * in the reported two-biome fixture both representatives sit within Chebyshev 10 of center
 * (8,65,8), and prepare() restricts ownership to the seed biome, so distance-filtering them
 * left the non-center biome patch permanently unlandmarked. */
public final class SourceSurveyHintIntegrationTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}

    private static final int BOTTOM=-64,TOP=320;

    private static ColumnView twoBiomeView(){
        int[] heights=new int[256];String[] biomes=new String[256];
        Arrays.fill(heights,64);Arrays.fill(biomes,"minecraft:desert");
        for(int lz=0;lz<16;lz++)for(int lx=0;lx<8;lx++){heights[lz*16+lx]=70;biomes[lz*16+lx]="minecraft:forest";}
        return new ColumnView(){
            public int height(int localX,int localZ){return heights[localZ*16+localX];}
            public String biome(int localX,int localZ,int surfaceY){return biomes[localZ*16+localX];}
        };
    }

    private static long chebyshev(Probe probe,int x,int y,int z){
        return Math.max(Math.max(Math.abs(probe.x()-x),Math.abs(probe.y()-y)),Math.abs(probe.z()-z));
    }

    private static void twoBiomeRepresentativesSurviveCenterProximity(){
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,twoBiomeView());
        check(probes.size()==2,"Fixture must produce one representative per biome component, got "+probes.size());
        // The exact reported failure shape: both representatives are within the previously
        // distance-filtered window around the chunk-center probe (8,65,8).
        for(var probe:probes)check(chebyshev(probe,8,65,8)<=10,"Fixture premise: representative must be within distance 10 of center");
        var hints=SourceLandmarks.surveyHints(probes);
        check(hints.size()==2,"Both distinct-component probes must be retained as hints despite center proximity, got "+hints.size());
        Probe forest=probes.stream().filter(p->p.biome().equals("minecraft:forest")).findFirst().orElseThrow();
        Probe desert=probes.stream().filter(p->p.biome().equals("minecraft:desert")).findFirst().orElseThrow();
        check(hints.stream().anyMatch(pos->pos.getX()==forest.x()&&pos.getY()==forest.y()&&pos.getZ()==forest.z()),"The forest representative must be hinted");
        check(hints.stream().anyMatch(pos->pos.getX()==desert.x()&&pos.getY()==desert.y()&&pos.getZ()==desert.z()),"The desert representative must be hinted so the seed-biome center window cannot strand it");
    }

    private static void everySurveyProbeBecomesAHint(){
        int[] heights=new int[256];Arrays.fill(heights,64);
        for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)heights[lz*16+lx]=Math.max(64,120-4*Math.max(lx,lz)); // cone: component + real peak
        String[] biomes=new String[256];Arrays.fill(biomes,"minecraft:plains");
        List<Probe> probes=GenerationSurvey.survey(4000,-4000,BOTTOM,TOP,new ColumnView(){
            public int height(int localX,int localZ){return heights[localZ*16+localX];}
            public String biome(int localX,int localZ,int surfaceY){return biomes[localZ*16+localX];}
        });
        check(probes.stream().anyMatch(p->p.kind()==Kind.PEAK),"Fixture must include a real peak probe");
        var hints=SourceLandmarks.surveyHints(probes);
        check(hints.size()==probes.size(),"Every surveyed probe (surface components and peak) must become a hint");
        for(var probe:probes)check(hints.stream().anyMatch(pos->pos.getX()==probe.x()&&pos.getY()==probe.y()&&pos.getZ()==probe.z()),"Hint must sit exactly on its probe column");
    }

    private static void hintsAreWorldPositionsInsideTheSurveyedChunk(){
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,twoBiomeView());
        for(var position:SourceLandmarks.surveyHints(probes)){
            check(position.getX()>=0&&position.getX()<16&&position.getZ()>=0&&position.getZ()<16,"Hints must be absolute world positions inside the surveyed chunk");
            check(position.getY()>BOTTOM&&position.getY()<TOP,"Hints must stay strictly inside world height limits");
        }
    }

    private static void emptySurveyYieldsNoHints(){
        int[] heights=new int[256];Arrays.fill(heights,GenerationSurvey.MISSING);
        String[] biomes=new String[256];Arrays.fill(biomes,"minecraft:plains");
        var probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,new ColumnView(){
            public int height(int localX,int localZ){return heights[localZ*16+localX];}
            public String biome(int localX,int localZ,int surfaceY){return biomes[localZ*16+localX];}
        });
        check(probes.isEmpty()&&SourceLandmarks.surveyHints(probes).isEmpty(),"A chunk without surface columns must yield no hints");
    }

    public static void main(String[] args){
        twoBiomeRepresentativesSurviveCenterProximity();
        everySurveyProbeBecomesAHint();
        hintsAreWorldPositionsInsideTheSurveyedChunk();
        emptySurveyYieldsNoHints();
        System.out.println("SourceSurveyHintIntegrationTest passed: "+assertions+" assertions");
    }
}
