package io.github.mysticism.landmark;

import io.github.mysticism.landmark.GenerationSurvey.ColumnView;
import io.github.mysticism.landmark.GenerationSurvey.Kind;
import io.github.mysticism.landmark.GenerationSurvey.Probe;
import java.util.*;

/** Regression: chunk generation must landmark actual source terrain (representative
 * surface components plus the real peak), not only a single chunk-center locality. */
public final class GenerationSurveyTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}

    private static final int BOTTOM=-64,TOP=320;

    private static ColumnView view(int[] heights,String[] biomes){
        return new ColumnView(){
            public int height(int localX,int localZ){return heights[localZ*16+localX];}
            public String biome(int localX,int localZ,int surfaceY){return biomes[localZ*16+localX];}
        };
    }

    private static int[] flat(int height){int[] heights=new int[256];Arrays.fill(heights,height);return heights;}
    private static String[] uniform(String biome){String[] biomes=new String[256];Arrays.fill(biomes,biome);return biomes;}

    private static void flatChunkHasOneRepresentativeProbe(){
        List<Probe> probes=GenerationSurvey.survey(1000,2000,BOTTOM,TOP,view(flat(64),uniform("minecraft:plains")));
        check(probes.size()==1,"Flat chunk must landmark one representative surface probe, got "+probes.size());
        Probe probe=probes.get(0);
        check(probe.kind()==Kind.SURFACE,"Flat chunk probe must be a surface probe");
        check(probe.y()==65,"Probe must sit at the first air block above the real surface, got "+probe.y());
        check(probe.x()>=1000&&probe.x()<1016&&probe.z()>=2000&&probe.z()<2016,"Probe must stay inside the surveyed chunk");
        check(probe.biome().equals("minecraft:plains"),"Probe must carry the surveyed column biome");
    }

    private static void biomeBoundaryLandmarksBothSides(){
        int[] heights=flat(64);String[] biomes=uniform("minecraft:desert");
        for(int lz=0;lz<16;lz++)for(int lx=0;lx<8;lx++){heights[lz*16+lx]=70;biomes[lz*16+lx]="minecraft:forest";}
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,view(heights,biomes));
        check(probes.size()==2,"A chunk with two distinct terrain components must landmark both, got "+probes.size());
        Probe forest=probes.stream().filter(p->p.biome().equals("minecraft:forest")).findFirst().orElse(null);
        Probe desert=probes.stream().filter(p->p.biome().equals("minecraft:desert")).findFirst().orElse(null);
        check(forest!=null&&desert!=null,"Both biome sides must be represented");
        check(forest.x()<8&&desert.x()>=8,"Each probe must lie inside its own biome half");
        check(forest.y()==71&&desert.y()==65,"Probe heights must follow the actual surface per side");
        check(forest.kind()==Kind.SURFACE&&desert.kind()==Kind.SURFACE,"Both probes are surface components");
    }

    private static void realPeakIsLandmarked(){
        int[] heights=new int[256];String[] biomes=uniform("minecraft:plains");
        for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)heights[lz*16+lx]=Math.max(64,120-4*Math.max(lx,lz)); // connected cone sloping from a real summit
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,view(heights,biomes));
        check(probes.size()<=GenerationSurvey.MAX_PROBES,"Survey must stay within the per-chunk probe budget");
        Probe peak=probes.stream().filter(p->p.kind()==Kind.PEAK).findFirst().orElse(null);
        check(peak!=null,"A real summit far from the component representative must be landmarked as a peak probe");
        check(peak.x()==0&&peak.z()==0&&peak.y()==121,"Peak probe must sit on the actual summit column");
        check(probes.stream().anyMatch(p->p.kind()==Kind.SURFACE&&p.y()<121),"The surrounding slopes must also be landmarked");
    }

    private static void summitRepresentativeSuppressesRedundantPeak(){
        int[] heights=flat(64);String[] biomes=uniform("minecraft:plains");
        for(int lz=0;lz<3;lz++)for(int lx=0;lx<3;lx++)heights[lz*16+lx]=90; // small plateau: its representative is the summit
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,view(heights,biomes));
        check(probes.size()==2,"Plateau and plains must both be landmarked, got "+probes.size());
        check(probes.stream().noneMatch(p->p.kind()==Kind.PEAK),"No redundant peak probe when a representative already covers the summit");
    }

    private static void componentBudgetPrefersLargestTerrain(){
        int[] heights=flat(64);String[] biomes=uniform("minecraft:plains");
        String[] islands={"minecraft:desert","minecraft:jungle","minecraft:savanna","minecraft:tundra"};
        for(int i=0;i<islands.length;i++){int lx=2+3*i,lz=2+3*i;heights[lz*16+lx]=64;biomes[lz*16+lx]=islands[i];}
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,view(heights,biomes));
        check(probes.size()==GenerationSurvey.SURFACE_PROBES,"Probe budget must cap surface components, got "+probes.size());
        Set<String> seen=new HashSet<>();probes.forEach(p->seen.add(p.biome()));
        check(seen.contains("minecraft:plains"),"Dominant terrain must always be landmarked");
        check(seen.contains("minecraft:desert")&&seen.contains("minecraft:jungle"),"Deterministic component order must keep the first minor components");
        check(!seen.contains("minecraft:tundra"),"Budget must drop the least significant components");
    }

    private static void missingColumnsAreNeverInvented(){
        int[] heights=new int[256];Arrays.fill(heights,GenerationSurvey.MISSING);
        check(GenerationSurvey.survey(0,0,BOTTOM,TOP,view(heights,uniform("minecraft:plains"))).isEmpty(),"A chunk without any surface column must produce no probes");
        int[] partial=flat(40);for(int lz=0;lz<8;lz++)for(int lx=0;lx<16;lx++)partial[lz*16+lx]=GenerationSurvey.MISSING;
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,view(partial,uniform("minecraft:plains")));
        check(probes.size()==1,"Partially known chunks landmark only the known terrain");
        check(probes.get(0).z()>=8,"The probe must lie in the known half of the chunk");
    }

    private static void probesRespectWorldHeightLimits(){
        int[] heights=flat(64);heights[0]=TOP-2;heights[1]=BOTTOM;
        List<Probe> probes=GenerationSurvey.survey(0,0,BOTTOM,TOP,view(heights,uniform("minecraft:plains")));
        check(probes.stream().allMatch(p->p.y()>BOTTOM&&p.y()<TOP),"Every probe must stay strictly inside world height limits");
        check(probes.stream().anyMatch(p->p.y()==TOP-1),"Top-of-world columns clamp to the last valid air block");
    }

    private static void surveyIsDeterministic(){
        int[] heights=flat(64);for(int lz=0;lz<3;lz++)for(int lx=0;lx<3;lx++)heights[lz*16+lx]=90;
        String[] biomes=uniform("minecraft:plains");
        var first=GenerationSurvey.survey(5,5,BOTTOM,TOP,view(heights,biomes));
        var second=GenerationSurvey.survey(5,5,BOTTOM,TOP,view(heights,biomes));
        check(first.equals(second),"Survey output must be deterministic for identical terrain");
    }

    private static void rejectsBadInput(){
        try{GenerationSurvey.survey(0,0,0,0,view(flat(1),uniform("a")));throw new AssertionError("Empty world limits must be rejected");}
        catch(IllegalArgumentException expected){}
    }

    public static void main(String[] args){
        flatChunkHasOneRepresentativeProbe();
        biomeBoundaryLandmarksBothSides();
        realPeakIsLandmarked();
        summitRepresentativeSuppressesRedundantPeak();
        componentBudgetPrefersLargestTerrain();
        missingColumnsAreNeverInvented();
        probesRespectWorldHeightLimits();
        surveyIsDeterministic();
        rejectsBadInput();
        System.out.println("GenerationSurveyTest passed: "+assertions+" assertions");
    }
}
