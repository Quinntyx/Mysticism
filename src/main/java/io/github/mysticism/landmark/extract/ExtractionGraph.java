package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import java.util.*;

/** Production, Minecraft-free, resumable six-neighbour graph. Null is never air.
 * A physical-air pass deliberately crosses biome boundaries to propagate outside/unknown
 * evidence; the second pass partitions cave identity strictly by registry biome key.
 */
public final class ExtractionGraph {
    public static final String ALGORITHM="source-air-v1";
    public static final int SIDE = 32, MAX_CELLS = SIDE * SIDE * SIDE, MAX_FEATURES = 256;
    public record Observation(BlockPalette.State material, String biome, String item,
                              boolean air, boolean sky, int surfaceY, int seaLevel) {
        public Observation { Objects.requireNonNull(material); Objects.requireNonNull(biome); Objects.requireNonNull(item); }
    }
    public record Seed(String id, Landmark.Kind kind, String biome, BlockPoint anchor, SourceGeometry geometry,String algorithmVersion) {
        public Seed(String id,Landmark.Kind kind,String biome,BlockPoint anchor,SourceGeometry geometry){this(id,kind,biome,anchor,geometry,ALGORITHM);}
    }
    public record Feature(String id,String algorithmVersion, Landmark.Kind kind, String biome, BlockPoint anchor,
                          double importance, SourceGeometry geometry, Map<String,Integer> items, Map<String,Integer> blocks,
                          List<String> parents, int airCells, int solidCells) {}
    private record AirGroup(boolean outside, boolean unknown) {}
    private final String dimension;
    private final Bounds root;
    private final Observation[] observations;
    private final List<Seed> seeds;
    private final long pageRevision;
    private final Set<String> retiredSeeds;
    private final int[] physical = new int[MAX_CELLS], semantic = new int[MAX_CELLS];
    private final ArrayList<AirGroup> groups = new ArrayList<>();
    private final ArrayList<ArrayList<Integer>> masks = new ArrayList<>();
    private final ArrayList<String> biomes = new ArrayList<>();
    private final ArrayList<Landmark.Kind> kinds = new ArrayList<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private int phase, cursor, active = -1;
    private boolean outside, unknown;
    private boolean overflow;
    public ExtractionGraph(String dimension, Bounds root, Observation[] observations, List<Seed> seeds, long pageRevision) {this(dimension,root,observations,seeds,pageRevision,Set.of());}
    public ExtractionGraph(String dimension,Bounds root,Observation[] observations,List<Seed> seeds,long pageRevision,Set<String> retiredSeeds){
        if(retiredSeeds.size()>8192)throw new IllegalArgumentException("Source lineage budget");this.retiredSeeds=Set.copyOf(retiredSeeds);
        if(root.maxX()-root.minX()!=SIDE || root.maxY()-root.minY()!=SIDE || root.maxZ()-root.minZ()!=SIDE || observations.length!=MAX_CELLS || pageRevision<0)
            throw new IllegalArgumentException("Expected bounded 32-cube snapshot");
        this.dimension=dimension; this.root=root; this.observations=observations.clone();
        this.seeds=List.copyOf(seeds); this.pageRevision=pageRevision;
        Arrays.fill(physical,-1); Arrays.fill(semantic,-1);
    }
    public static int index(int x,int y,int z) { return (y*SIDE+z)*SIDE+x; }
    public BlockPoint point(int i) { return new BlockPoint(root.minX()+i%SIDE,root.minY()+i/(SIDE*SIDE),root.minZ()+(i/SIDE)%SIDE); }
    private int neighbour(int i,int face) {
        int x=i%SIDE,y=i/(SIDE*SIDE),z=(i/SIDE)%SIDE;
        return switch(face) { case 0 -> x==0?-1:i-1; case 1 -> x==SIDE-1?-1:i+1;
            case 2 -> y==0?-1:i-SIDE*SIDE; case 3 -> y==SIDE-1?-1:i+SIDE*SIDE;
            case 4 -> z==0?-1:i-SIDE; default -> z==SIDE-1?-1:i+SIDE; };
    }
    /** Each unit is one cursor visit or one BFS expansion (at most six edges). */
    public int advance(int budget) {
        if(budget<1)throw new IllegalArgumentException("Positive budget required");
        int work=0;
        while(work<budget && phase<3) {
            if(phase==0) {
                if(!queue.isEmpty()) {
                    int i=queue.removeFirst(); Observation o=observations[i]; outside|=o.sky();
                    for(int f=0;f<6;f++) { int n=neighbour(i,f);
                        if(n<0 || observations[n]==null){unknown=true;continue;}
                        if(observations[n].air() && physical[n]<0){physical[n]=active;queue.add(n);}
                    }
                } else if(active>=0) { groups.add(new AirGroup(outside,unknown)); active=-1;
                } else if(cursor<MAX_CELLS) {
                    int i=cursor++; if(observations[i]!=null && observations[i].air() && physical[i]<0) {
                        active=groups.size(); outside=false;unknown=false;physical[i]=active;queue.add(i);
                    }
                } else { phase=1;cursor=0; }
            } else if(phase==1) {
                if(!queue.isEmpty()) {
                    int i=queue.removeFirst();masks.get(active).add(i);
                    for(int f=0;f<6;f++){int n=neighbour(i,f);
                        if(n>=0 && observations[n]!=null && observations[n].air() && semantic[n]<0
                                && observations[n].biome().equals(biomes.get(active))) {
                            semantic[n]=active;queue.add(n);
                        }
                    }
                } else if(cursor<MAX_CELLS) {
                    int i=cursor++;Observation o=observations[i];
                    if(o!=null && o.air() && semantic[i]<0 && !groups.get(physical[i]).outside()) {
                        if(masks.size()==MAX_FEATURES){overflow=true;continue;}
                        active=masks.size();masks.add(new ArrayList<>());biomes.add(o.biome());kinds.add(Landmark.Kind.CAVE);
                        semantic[i]=active;queue.add(i);
                    }
                } else {phase=2;cursor=0;}
            } else {
                if(cursor==MAX_CELLS){phase=3;continue;}
                int i=cursor++;Observation o=observations[i]; if(o==null || o.air())continue;
                // A solid observation has exactly ONE mask owner. Surface takes priority;
                // internal walls are assigned deterministically to the first cave mask.
                if(point(i).y()<=o.surfaceY() && point(i).y()>o.surfaceY()-4) {
                    Landmark.Kind kind=o.surfaceY()>o.seaLevel()+64?Landmark.Kind.MOUNTAIN:Landmark.Kind.BIOME;
                    int m=-1;for(int j=0;j<masks.size();j++)if(kinds.get(j)==kind && biomes.get(j).equals(o.biome())){m=j;break;}
                    if(m<0){if(masks.size()==MAX_FEATURES){overflow=true;continue;}m=masks.size();masks.add(new ArrayList<>());biomes.add(o.biome());kinds.add(kind);}
                    // Coarse plains have a 4-block horizontal stride; elevated terrain refines to blocks.
                    if(kind==Landmark.Kind.MOUNTAIN || ((point(i).x()&3)==0 && (point(i).z()&3)==0))masks.get(m).add(i);
                } else {
                    int owner=Integer.MAX_VALUE;
                    for(int f=0;f<6;f++){int n=neighbour(i,f);if(n>=0 && semantic[n]>=0)owner=Math.min(owner,semantic[n]);}
                    if(owner!=Integer.MAX_VALUE)masks.get(owner).add(i);
                }
            }
            work++;
        }
        return work;
    }
    public boolean complete(){return phase==3;}
    public boolean overflow(){return overflow;}
    private static final Comparator<BlockPoint> POINTS=Comparator.comparingLong(BlockPoint::x).thenComparingLong(BlockPoint::y).thenComparingLong(BlockPoint::z);
    /** Called only on the bounded extraction worker, never on the tick. */
    public List<Feature> finish() {
        if(!complete())throw new IllegalStateException("Graph incomplete");
        ArrayList<Feature> result=new ArrayList<>();
        for(int m=0;m<masks.size();m++) {
            var mask=masks.get(m); if(mask.isEmpty())continue;
            Landmark.Kind kind=kinds.get(m);String biome=biomes.get(m);
            BlockPoint anchor=mask.stream().filter(i->kind!=Landmark.Kind.CAVE || observations[i].air()).map(this::point).min(POINTS).orElseThrow();
            ArrayList<Seed> parents=new ArrayList<>();
            for(Seed seed:seeds)if(seed.kind()==kind && seed.biome().equals(biome)) {
                boolean overlap=false;
                for(int i:mask) {
                    if(kind==Landmark.Kind.CAVE && !observations[i].air())continue;
                    BlockPoint p=point(i);BlockSample previous=seed.geometry().sample(p.x(),p.y(),p.z());
                    if(previous!=null && (kind!=Landmark.Kind.CAVE || previous.occupancy()==BlockSample.Occupancy.AIR)){overlap=true;break;}
                }
                if(overlap)parents.add(seed);
            }
            parents.sort(Comparator.comparing(Seed::id));
            Seed retained=null;
            for(Seed parent:parents) {
                BlockPoint p=parent.anchor();
                if(root.contains(p.x(),p.y(),p.z())) {
                    int i=index((int)(p.x()-root.minX()),(int)(p.y()-root.minY()),(int)(p.z()-root.minZ()));
                    if(mask.contains(i)){anchor=p;retained=parent;break;}
                }
            }
            if(parents.size()>1){retained=parents.getFirst();anchor=retained.anchor();}
            String algorithm=retained==null?ALGORITHM:retained.algorithmVersion();
            String id=LandmarkIds.seed(dimension,algorithm,kind,biome,anchor);
            TreeMap<String,Integer> itemCounts=new TreeMap<>(),blockCounts=new TreeMap<>(); TreeMap<String,List<Integer>> pageMasks=new TreeMap<>();
            int air=0,solid=0; boolean frontier=false;
            for(int i:mask) {
                Observation o=observations[i]; if(o.air()){air++;frontier|=groups.get(physical[i]).unknown();}else{solid++;itemCounts.merge(o.item(),1,Integer::sum);blockCounts.merge(o.material().blockId()+" "+new TreeMap<>(o.material().properties()),1,Integer::sum);}
                BlockPoint p=point(i);String key=(Math.floorDiv(p.x(),16))+":"+Math.floorDiv(p.y(),16)+":"+Math.floorDiv(p.z(),16);
                pageMasks.computeIfAbsent(key,k->new ArrayList<>()).add(i);
            }
            ArrayList<GeometryPage> pages=new ArrayList<>();
            for(var pageMask:pageMasks.values()) {
                BlockPoint p=point(pageMask.getFirst());BlockPoint base=new BlockPoint(Math.floorDiv(p.x(),16)*16,Math.floorDiv(p.y(),16)*16,Math.floorDiv(p.z(),16)*16);
                Bounds bounds=Bounds.cube(base.x(),base.y(),base.z(),16);
                LinkedHashMap<BlockPalette.State,Integer> palette=new LinkedHashMap<>();
                SparseOctree<BlockSample> tree=SparseOctree.empty(bounds,1,16);
                for(int i:pageMask) {
                    Observation o=observations[i];int pi=palette.computeIfAbsent(o.material(),k->palette.size());BlockPoint c=point(i);
                    tree=tree.with(Bounds.cube(c.x(),c.y(),c.z(),1),new BlockSample(o.air()?BlockSample.Occupancy.AIR:BlockSample.Occupancy.SOLID,pi),128);
                }
                pages.add(new GeometryPage(LandmarkIds.geometryPage(dimension,id,base,16),pageRevision,bounds,new BlockPalette(new ArrayList<>(palette.keySet())),tree));
            }
            ArrayList<FrontierFace> frontiers=new ArrayList<>();
            if(frontier) {
                // Conservatively retain all six halo faces: unknown can be reached THROUGH another biome.
                // A halo retry may close these only after the physical-air pass no longer reaches unknown.
                for(var direction:FrontierFace.Direction.values()) {
                    Bounds missing=switch(direction) {
                        case WEST -> new Bounds(root.minX()-1,root.minY(),root.minZ(),root.minX(),root.maxY(),root.maxZ());
                        case EAST -> new Bounds(root.maxX(),root.minY(),root.minZ(),root.maxX()+1,root.maxY(),root.maxZ());
                        case DOWN -> new Bounds(root.minX(),root.minY()-1,root.minZ(),root.maxX(),root.minY(),root.maxZ());
                        case UP -> new Bounds(root.minX(),root.maxY(),root.minZ(),root.maxX(),root.maxY()+1,root.maxZ());
                        case NORTH -> new Bounds(root.minX(),root.minY(),root.minZ()-1,root.maxX(),root.maxY(),root.minZ());
                        case SOUTH -> new Bounds(root.minX(),root.minY(),root.maxZ(),root.maxX(),root.maxY(),root.maxZ()+1);
                    };
                    frontiers.add(new FrontierFace(dimension,missing,direction,pageRevision,"resample:"+root.minX()+":"+root.minY()+":"+root.minZ()));
                }
            }
            double importance=kind==Landmark.Kind.MOUNTAIN?.7:kind==Landmark.Kind.CAVE?Math.min(.8,.25+air/65536.0):.2;
            result.add(new Feature(id,algorithm,kind,biome,anchor,importance,new SourceGeometry(pages,frontiers),Map.copyOf(itemCounts),Map.copyOf(blockCounts),parents.stream().map(Seed::id).toList(),air,solid));
        }
        // Retired aliases/tombstones cannot be resurrected. Choose the next deterministic
        // observed source cell instead, within the SAME algorithm/biome domain. Never re-key
        // a surviving parent, and don't copy an unbounded repository catalog on the tick.
        Set<String> allocated=new HashSet<>();
        for(int i=0;i<result.size();i++){
            Feature f=result.get(i);String algorithm=f.algorithmVersion();BlockPoint anchor=f.anchor();
            if(f.parents().size()==1){String parent=f.parents().getFirst();Seed seed=seeds.stream().filter(s->s.id().equals(parent)).findFirst().orElseThrow();
                algorithm=seed.algorithmVersion();if(result.stream().filter(other->other.parents().contains(parent)).count()==1)anchor=seed.anchor();
            }
            String id=LandmarkIds.seed(dimension,algorithm,f.kind(),f.biome(),anchor);
            if((retiredSeeds.contains(id) && !f.parents().contains(id)) || allocated.contains(id)){
                BlockPoint bestAir=null,bestAny=null;
                for(var p:f.geometry().pages())for(var cell:p.knownCells()){
                    Bounds b=cell.bounds();for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++){
                        BlockPoint candidate=new BlockPoint(x,y,z);String key=LandmarkIds.seed(dimension,algorithm,f.kind(),f.biome(),candidate);
                        if(retiredSeeds.contains(key)||allocated.contains(key))continue;
                        if(bestAny==null || POINTS.compare(candidate,bestAny)<0)bestAny=candidate;
                        if(cell.value().occupancy()==BlockSample.Occupancy.AIR && (bestAir==null || POINTS.compare(candidate,bestAir)<0))bestAir=candidate;
                    }
                }
                anchor=bestAir==null?bestAny:bestAir;if(anchor==null)throw new IllegalStateException("All observed source seed positions retired");id=LandmarkIds.seed(dimension,algorithm,f.kind(),f.biome(),anchor);
            }
            if(!id.equals(f.id())){
                final String key=id;List<GeometryPage> pages=f.geometry().pages().stream().map(p->new GeometryPage(LandmarkIds.geometryPage(dimension,key,new BlockPoint(p.bounds().minX(),p.bounds().minY(),p.bounds().minZ()),16),p.revision(),p.bounds(),p.palette(),p.cells())).toList();
                result.set(i,new Feature(id,algorithm,f.kind(),f.biome(),anchor,f.importance(),new SourceGeometry(pages,f.geometry().frontiers()),f.items(),f.blocks(),f.parents(),f.airCells(),f.solidCells()));
            }allocated.add(id);
        }
        result.sort(Comparator.comparing(Feature::id));return List.copyOf(result);
    }

    /** Re-observe edited parent masks before a verified merge. The store intentionally refuses
     * reconciled merges that silently discard old material: publish these source revisions first,
     * without moving anchors or claims, then its atomic merge can preserve every current cell.
     * Called only on the bounded worker. Returned masks partition the new connected component.
     */
    public static Map<String,SourceGeometry> refreshParents(String dimension,Feature feature,List<Seed> parents,long version){
        List<Seed> sorted=parents.stream().filter(s->feature.parents().contains(s.id())).sorted(Comparator.comparing(Seed::id)).toList();
        if(sorted.size()<2)throw new IllegalArgumentException("Merge parents required");
        Map<String,List<GeometryPage>> pages=new TreeMap<>();for(var seed:sorted)pages.put(seed.id(),new ArrayList<>());
        int observed=0;
        for(var source:feature.geometry().pages()){
            Map<String,SparseOctree<BlockSample>> masks=new TreeMap<>();
            for(var cell:source.knownCells()){
                Bounds b=cell.bounds();
                for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++){
                    if(++observed>MAX_CELLS)throw new IllegalArgumentException("Merge observation budget");
                    String owner=sorted.getFirst().id();
                    for(var seed:sorted)if(seed.geometry().sample(x,y,z)!=null){owner=seed.id();break;}
                    SparseOctree<BlockSample> tree=masks.computeIfAbsent(owner,k->SparseOctree.empty(source.bounds(),1,16));
                    masks.put(owner,tree.with(Bounds.cube(x,y,z,1),cell.value(),128));
                }
            }
            for(var mask:masks.entrySet()){
                Bounds b=source.bounds();BlockPoint base=new BlockPoint(b.minX(),b.minY(),b.minZ());
                pages.get(mask.getKey()).add(new GeometryPage(LandmarkIds.geometryPage(dimension,mask.getKey()+":source-edit",base,16),version,b,source.palette(),mask.getValue()));
            }
        }
        Map<String,SourceGeometry> result=new TreeMap<>();
        for(var p:pages.entrySet())result.put(p.getKey(),new SourceGeometry(p.getValue(),feature.geometry().frontiers()));
        return Map.copyOf(result);
    }
}
