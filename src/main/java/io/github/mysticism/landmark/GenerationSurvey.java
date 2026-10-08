package io.github.mysticism.landmark;

import java.util.*;

/** Generation-time terrain survey for one source chunk. Instead of landmarking only the
 * chunk-center heightmap probe (a tiny locality), this derives representative probes from
 * the chunk's actual generated surface: one per major terrain component (same biome and
 * connected slope band) plus the true peak, so landmarks follow real terrain across the
 * render distance as chunks stream in. Pure and deterministic: no Minecraft imports,
 * no world access, no allocation beyond the returned probes. */
public final class GenerationSurvey {
    public static final int CHUNK_SIDE=16,MAX_PROBES=4,SURFACE_PROBES=3,HEIGHT_TOLERANCE=4,PEAK_MIN_CHEBYSHEV=8;
    public static final int MISSING=Integer.MIN_VALUE;

    public enum Kind{SURFACE,PEAK}
    /** World-space probe at the first air block above an actual surface column. */
    public record Probe(int x,int y,int z,String biome,Kind kind){}
    /** Per-column chunk data; heights use the same convention as Chunk#sampleHeightmap
     * (y of the highest motion-blocking block, or MISSING when the column has none). */
    public interface ColumnView{
        int height(int localX,int localZ);
        /** Biome id at the column's surface; may be null when unknown. */
        String biome(int localX,int localZ,int surfaceY);
    }

    private GenerationSurvey(){}

    /** Representative surface and peak probes for one generated chunk. Never null;
     * empty when the chunk reports no surface columns at all. */
    public static List<Probe> survey(int startX,int startZ,int bottomY,int topY,ColumnView view){
        if(view==null)throw new IllegalArgumentException("column view");
        if(bottomY>=topY)throw new IllegalArgumentException("world height limits");
        int[] heights=new int[CHUNK_SIDE*CHUNK_SIDE];String[] biomes=new String[CHUNK_SIDE*CHUNK_SIDE];
        boolean[] valid=new boolean[CHUNK_SIDE*CHUNK_SIDE];
        for(int lz=0;lz<CHUNK_SIDE;lz++)for(int lx=0;lx<CHUNK_SIDE;lx++){
            int index=lz*CHUNK_SIDE+lx,column=view.height(lx,lz);
            if(column==MISSING||column<bottomY||column>topY-2)continue;
            valid[index]=true;heights[index]=column;biomes[index]=view.biome(lx,lz,column);
        }
        int[] component=labelComponents(valid,heights,biomes);
        int count=componentCount(component);
        if(count==0)return List.of();
        record Group(int size,int first,int id){}
        List<Group> groups=new ArrayList<>();
        for(int id=1;id<=count;id++){int size=0,first=Integer.MAX_VALUE;
            for(int index=0;index<component.length;index++)if(component[index]==id){size++;first=Math.min(first,index);}
            groups.add(new Group(size,first,id));}
        groups.sort(Comparator.comparingInt(Group::size).reversed().thenComparingInt(g->g.first));
        List<Probe> probes=new ArrayList<>();
        int included=0;
        for(var group:groups){
            if(included>=SURFACE_PROBES)break;
            probes.add(representative(group.id,component,valid,heights,biomes,startX,startZ,bottomY,topY));
            included++;
        }
        var peak=peakProbe(valid,heights,biomes,startX,startZ,bottomY,topY);
        if(peak!=null&&probes.stream().noneMatch(p->chebyshev(p,peak)<PEAK_MIN_CHEBYSHEV))probes.add(peak);
        return List.copyOf(probes);
    }

    /** Keeps only probes that are not within minChebyshev of a reference position. Used to
     * avoid re-observing columns already covered by an existing chunk-center probe window. */
    public static List<Probe> spread(List<Probe> probes,long x,long y,long z,int minChebyshev){
        if(minChebyshev<0)throw new IllegalArgumentException("minimum chebyshev distance");
        Objects.requireNonNull(probes);
        List<Probe> result=new ArrayList<>();
        for(var probe:probes)if(chebyshev(probe,new Probe(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z),probe.biome(),probe.kind()))>minChebyshev)result.add(probe);
        return List.copyOf(result);
    }

    private static long chebyshev(Probe a,Probe b){
        return Math.max(Math.max(Math.abs((long)a.x()-b.x()),Math.abs((long)a.y()-b.y())),Math.abs((long)a.z()-b.z()));
    }

    private static Probe representative(int id,int[] component,boolean[] valid,int[] heights,String[] biomes,int startX,int startZ,int bottomY,int topY){
        long sumX=0,sumZ=0,members=0;
        for(int index=0;index<component.length;index++)if(component[index]==id){sumX+=index%CHUNK_SIDE;sumZ+=index/CHUNK_SIDE;members++;}
        double centerX=(double)sumX/members,centerZ=(double)sumZ/members;
        int best=-1;double bestDistance=Double.MAX_VALUE;
        for(int index=0;index<component.length;index++){
            if(component[index]!=id)continue;
            double dx=index%CHUNK_SIDE-centerX,dz=index/CHUNK_SIDE-centerZ,distance=dx*dx+dz*dz;
            if(distance<bestDistance){bestDistance=distance;best=index;}
        }
        int lx=best%CHUNK_SIDE,lz=best/CHUNK_SIDE;
        return new Probe(startX+lx,clampY(heights[best]+1,bottomY,topY),startZ+lz,biomes[best],Kind.SURFACE);
    }

    private static Probe peakProbe(boolean[] valid,int[] heights,String[] biomes,int startX,int startZ,int bottomY,int topY){
        int best=-1;
        for(int index=0;index<heights.length;index++)if(valid[index]&&(best<0||heights[index]>heights[best]))best=index;
        if(best<0)return null;
        int lx=best%CHUNK_SIDE,lz=best/CHUNK_SIDE;
        return new Probe(startX+lx,clampY(heights[best]+1,bottomY,topY),startZ+lz,biomes[best],Kind.PEAK);
    }

    private static int clampY(int y,int bottomY,int topY){return Math.max(bottomY+1,Math.min(topY-1,y));}

    /** Connected 4-neighbour components of valid columns sharing a biome and slope band. */
    private static int[] labelComponents(boolean[] valid,int[] heights,String[] biomes){
        int[] component=new int[valid.length];int next=0;
        int[] queue=new int[valid.length];
        for(int seed=0;seed<valid.length;seed++){
            if(!valid[seed]||component[seed]!=0)continue;
            component[seed]=++next;int head=0,tail=0;queue[tail++]=seed;
            while(head<tail){
                int index=queue[head++],lx=index%CHUNK_SIDE,lz=index/CHUNK_SIDE;
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                    int nx=lx+d[0],nz=lz+d[1];
                    if(nx<0||nx>=CHUNK_SIDE||nz<0||nz>=CHUNK_SIDE)continue;
                    int neighbour=nz*CHUNK_SIDE+nx;
                    if(!valid[neighbour]||component[neighbour]!=0)continue;
                    if(!Objects.equals(biomes[index],biomes[neighbour]))continue;
                    if(Math.abs(heights[index]-heights[neighbour])>HEIGHT_TOLERANCE)continue;
                    component[neighbour]=next;queue[tail++]=neighbour;
                }
            }
        }
        return component;
    }

    private static int componentCount(int[] component){
        int count=0;for(int id:component)count=Math.max(count,id);return count;
    }
}
