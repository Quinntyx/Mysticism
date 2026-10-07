package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import java.util.*;

/** Pure worker-side global connectivity. Persisted observations are not invented live air.
 * Bounds are resource caps, never spatial partitions: exceeding them defers publication. */
public final class BoundaryCaves {
    public static final int MAX_PAGES=512, MAX_CELLS=131072, MAX_FRAGMENTS=256;
    private static final long[][] FACES={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final Comparator<BlockPoint> POINTS=Comparator.comparingLong(BlockPoint::x).thenComparingLong(BlockPoint::y).thenComparingLong(BlockPoint::z);
    private record Cell(BlockPalette.State material,boolean air,String biome,Set<String> parents) {}
    public record Union(List<LandmarkRepository.RevisionRef> refs,SourceGeometry geometry) {
        public LandmarkRepository.VerifiedConnectivity proof(){return new LandmarkRepository.VerifiedConnectivity(refs,"actual six-neighbour persisted/loaded air faces; exact dimension/biome/algorithm");}
    }
    private BoundaryCaves(){}
    private static BlockPoint adjacent(BlockPoint p,long[] f){return new BlockPoint(p.x()+f[0],p.y()+f[1],p.z()+f[2]);}
    private static void put(Map<BlockPoint,Cell> cells,SourceGeometry geometry,String biome,Set<String> parents,Bounds exclude){
        for(var page:geometry.pages())for(var leaf:page.knownCells()){
            Bounds b=leaf.bounds();long volume=(b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ());
            if(volume>MAX_CELLS)throw new IllegalStateException("global cave cell budget");
            for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++){
                if(exclude!=null && exclude.contains(x,y,z))continue;
                BlockPoint p=new BlockPoint(x,y,z);Cell c=new Cell(page.palette().state(leaf.value().paletteIndex()),leaf.value().occupancy()==BlockSample.Occupancy.AIR,biome,parents);
                Cell old=cells.putIfAbsent(p,c);if(old!=null && !old.equals(c))throw new IllegalStateException("conflicting global cave ownership");
                if(cells.size()>MAX_CELLS)throw new IllegalStateException("global cave cell budget");
            }
        }
    }
    /** Return one real connected union; callers repeat bounded pair/group transactions to grow it.
     * Original pages and unknown frontiers survive, avoiding disconnected averaged geometry. */
    public static Optional<Union> stitch(List<Landmark> input){
        if(input.size()>MAX_FRAGMENTS)throw new IllegalStateException("global fragment budget");
        List<Landmark> caves=input.stream().filter(l->l.kind()==Landmark.Kind.CAVE).sorted(Comparator.comparing(Landmark::id)).toList();
        int pages=caves.stream().mapToInt(l->l.geometry().pages().size()).sum();if(pages>MAX_PAGES)throw new IllegalStateException("global page budget");
        Map<BlockPoint,String> air=new HashMap<>();Map<String,Landmark> byId=new TreeMap<>();int count=0;
        for(var l:caves){byId.put(l.id(),l);for(var page:l.geometry().pages())for(var leaf:page.knownCells()){
            Bounds b=leaf.bounds();for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++){
                if(++count>MAX_CELLS)throw new IllegalStateException("global cave cell budget");
                if(leaf.value().occupancy()==BlockSample.Occupancy.AIR && air.putIfAbsent(new BlockPoint(x,y,z),l.id())!=null)throw new IllegalStateException("overlapping air ownership");
            }
        }}
        Map<String,Set<String>> edges=new TreeMap<>();for(var p:air.entrySet())for(var f:FACES){String other=air.get(adjacent(p.getKey(),f));if(other==null||other.equals(p.getValue()))continue;
            Landmark a=byId.get(p.getValue()),b=byId.get(other);
            if(!a.dimension().equals(b.dimension())||!a.biome().equals(b.biome())||!a.algorithmVersion().equals(b.algorithmVersion()))continue;
            a.baseEmbedding().profile().requireCompatible(b.baseEmbedding().profile());edges.computeIfAbsent(a.id(),k->new TreeSet<>()).add(b.id());
        }
        for(String seed:edges.keySet()){
            TreeSet<String> group=new TreeSet<>();ArrayDeque<String> todo=new ArrayDeque<>();todo.add(seed);
            while(!todo.isEmpty() && group.size()<8){String id=todo.removeFirst();if(group.add(id))for(String n:edges.getOrDefault(id,Set.of()))if(!group.contains(n))todo.addLast(n);}
            if(group.size()<2)continue;
            ArrayList<GeometryPage> merged=new ArrayList<>();ArrayList<FrontierFace> frontiers=new ArrayList<>();ArrayList<LandmarkRepository.RevisionRef> refs=new ArrayList<>();
            for(String id:group){var l=byId.get(id);refs.add(new LandmarkRepository.RevisionRef(id,l.revision()));merged.addAll(l.geometry().pages());frontiers.addAll(l.geometry().frontiers());}
            return Optional.of(new Union(List.copyOf(refs),new SourceGeometry(merged,frontiers)));
        }
        return Optional.empty();
    }
    /** Recompute a previously stitched parent's whole observed graph after editing ONE domain.
     * Remote persisted masks are retained, not replaced by the local domain's truncated mask. */
    public static List<ExtractionGraph.Feature> revise(String dimension,Bounds edited,ExtractionGraph.Observation[] observations,
            List<ExtractionGraph.Feature> local,List<ExtractionGraph.Seed> prior,long version,Set<String> retired){
        List<ExtractionGraph.Seed> caves=prior.stream().filter(s->s.kind()==Landmark.Kind.CAVE).toList();
        if(caves.stream().noneMatch(s->s.geometry().pages().stream().anyMatch(p->p.knownCells().stream().anyMatch(c->!edited.contains(c.bounds())))))return local;
        if(prior.stream().mapToInt(s->s.geometry().pages().size()).sum()>MAX_PAGES)throw new IllegalStateException("global page budget");
        // Missing chunks are not evidence of removal: retain the prior provisional catalog
        // and retry on reload rather than manufacturing a global split through unknown air.
        for(var s:caves)for(var page:s.geometry().pages())for(var leaf:page.knownCells())if(leaf.value().occupancy()==BlockSample.Occupancy.AIR){
            Bounds b=leaf.bounds();for(long x=Math.max(b.minX(),edited.minX());x<Math.min(b.maxX(),edited.maxX());x++)for(long y=Math.max(b.minY(),edited.minY());y<Math.min(b.maxY(),edited.maxY());y++)for(long z=Math.max(b.minZ(),edited.minZ());z<Math.min(b.maxZ(),edited.maxZ());z++)
                if(observations[ExtractionGraph.index((int)(x-edited.minX()),(int)(y-edited.minY()),(int)(z-edited.minZ()))]==null)throw new IllegalStateException("global revision awaiting loaded frontier");
        }
        Map<BlockPoint,Cell> cells=new HashMap<>();Map<String,ExtractionGraph.Seed> seeds=new TreeMap<>();ArrayList<FrontierFace> frontiers=new ArrayList<>();
        for(var s:caves){seeds.put(s.id(),s);put(cells,s.geometry(),s.biome(),Set.of(s.id()),edited);frontiers.addAll(s.geometry().frontiers());}
        for(var f:local)if(f.kind()==Landmark.Kind.CAVE){put(cells,f.geometry(),f.biome(),Set.copyOf(f.parents()),null);frontiers.addAll(f.geometry().frontiers());}
        // Propagate proven outside through ALL biome keys before semantic partitioning.
        Set<BlockPoint> outside=new HashSet<>(),physicalSeen=new HashSet<>();
        for(var start:cells.entrySet())if(start.getValue().air && physicalSeen.add(start.getKey())){
            List<BlockPoint> component=new ArrayList<>();ArrayDeque<BlockPoint> q=new ArrayDeque<>();q.add(start.getKey());boolean open=false;
            while(!q.isEmpty()){var p=q.removeFirst();component.add(p);for(var face:FACES){var n=adjacent(p,face);Cell c=cells.get(n);
                if(c!=null && c.air){if(physicalSeen.add(n))q.addLast(n);}else if(edited.contains(n.x(),n.y(),n.z())){
                    int i=ExtractionGraph.index((int)(n.x()-edited.minX()),(int)(n.y()-edited.minY()),(int)(n.z()-edited.minZ()));var o=observations[i];if(o!=null && o.air() && c==null)open=true;
                }
            }}if(open)outside.addAll(component);
        }
        Map<BlockPoint,Integer> owner=new HashMap<>();List<List<BlockPoint>> groups=new ArrayList<>();List<String> biomes=new ArrayList<>();List<Set<String>> parents=new ArrayList<>();
        for(BlockPoint start:cells.keySet().stream().sorted(POINTS).toList()){
            Cell cell=cells.get(start);if(!cell.air||outside.contains(start)||owner.containsKey(start))continue;
            int index=groups.size();if(index==ExtractionGraph.MAX_FEATURES)throw new IllegalStateException("global component budget");
            List<BlockPoint> mask=new ArrayList<>();Set<String> ids=new TreeSet<>();ArrayDeque<BlockPoint> q=new ArrayDeque<>();q.add(start);owner.put(start,index);
            while(!q.isEmpty()){var p=q.removeFirst();mask.add(p);ids.addAll(cells.get(p).parents);for(var face:FACES){var n=adjacent(p,face);Cell c=cells.get(n);if(c!=null&&c.air&&c.biome.equals(cell.biome)&&!outside.contains(n)&&owner.putIfAbsent(n,index)==null)q.addLast(n);}}
            groups.add(mask);biomes.add(cell.biome);parents.add(ids);
        }
        for(var e:cells.entrySet())if(!e.getValue().air){int best=Integer.MAX_VALUE;for(var face:FACES){Integer o=owner.get(adjacent(e.getKey(),face));if(o!=null)best=Math.min(best,o);}if(best!=Integer.MAX_VALUE)groups.get(best).add(e.getKey());}
        ArrayList<ExtractionGraph.Feature> result=new ArrayList<>(local.stream().filter(f->f.kind()!=Landmark.Kind.CAVE).toList());Set<String> allocated=new HashSet<>();
        for(int i=0;i<groups.size();i++){
            List<BlockPoint> mask=groups.get(i);Set<String> ids=parents.get(i);String biome=biomes.get(i),algorithm=ExtractionGraph.ALGORITHM;
            BlockPoint anchor=mask.stream().filter(p->cells.get(p).air).min(POINTS).orElseThrow();
            if(!ids.isEmpty()){
                var seed=seeds.get(ids.iterator().next());algorithm=seed.algorithmVersion();long uses=parents.stream().filter(s->s.contains(seed.id())).count();
                if(ids.size()>1||uses==1||mask.contains(seed.anchor()))anchor=seed.anchor();
            }
            String id=LandmarkIds.seed(dimension,algorithm,Landmark.Kind.CAVE,biome,anchor);
            if((retired.contains(id)&&!ids.contains(id))||allocated.contains(id)){
                final String alg=algorithm;anchor=mask.stream().filter(p->cells.get(p).air).sorted(POINTS).filter(p->{String key=LandmarkIds.seed(dimension,alg,Landmark.Kind.CAVE,biome,p);return !retired.contains(key)&&!allocated.contains(key);}).findFirst().orElseThrow();id=LandmarkIds.seed(dimension,algorithm,Landmark.Kind.CAVE,biome,anchor);
            }allocated.add(id);
            Map<BlockPoint,List<BlockPoint>> pages=new TreeMap<>(POINTS);Map<String,Integer> blocks=new TreeMap<>(),items=new TreeMap<>();int airCount=0;
            for(var p:mask){pages.computeIfAbsent(new BlockPoint(Math.floorDiv(p.x(),16)*16,Math.floorDiv(p.y(),16)*16,Math.floorDiv(p.z(),16)*16),k->new ArrayList<>()).add(p);Cell c=cells.get(p);if(c.air)airCount++;else{blocks.merge(c.material.blockId()+" "+new TreeMap<>(c.material.properties()),1,Integer::sum);items.merge(c.material.blockId(),1,Integer::sum);}}
            List<GeometryPage> geometry=new ArrayList<>();for(var page:pages.entrySet()){
                var base=page.getKey();Bounds b=Bounds.cube(base.x(),base.y(),base.z(),16);Map<BlockPalette.State,Integer> palette=new LinkedHashMap<>();SparseOctree<BlockSample> tree=SparseOctree.empty(b,1,16);
                for(var p:page.getValue()){Cell c=cells.get(p);int pi=palette.computeIfAbsent(c.material,k->palette.size());tree=tree.with(Bounds.cube(p.x(),p.y(),p.z(),1),new BlockSample(c.air?BlockSample.Occupancy.AIR:BlockSample.Occupancy.SOLID,pi),128);}
                geometry.add(new GeometryPage(LandmarkIds.geometryPage(dimension,id+":global:"+edited.minX()+":"+edited.minY()+":"+edited.minZ(),base,16),version,b,new BlockPalette(new ArrayList<>(palette.keySet())),tree));
            }
            result.add(new ExtractionGraph.Feature(id,algorithm,Landmark.Kind.CAVE,biome,anchor,Math.min(.8,.25+airCount/65536.0),new SourceGeometry(geometry,frontiers),Map.copyOf(items),Map.copyOf(blocks),List.copyOf(ids),airCount,mask.size()-airCount));
        }
        return List.copyOf(result);
    }
}
