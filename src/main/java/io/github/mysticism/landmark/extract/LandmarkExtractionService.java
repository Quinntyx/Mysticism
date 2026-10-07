package io.github.mysticism.landmark.extract;

import io.github.mysticism.embedding.*;
import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.block.BlockState;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.*;
import java.util.*;
import java.util.concurrent.*;

/** One bounded extraction pipeline per server. World/registry/store access is server-thread only.
 * No joins, forced loads, descriptor inference, graph scans or model IO on the server tick.
 */
public final class LandmarkExtractionService {
    public static final String ALGORITHM=ExtractionGraph.ALGORITHM;
    public static final int CELLS_PER_TICK=1024, PENDING_REGIONS=256, TRACKED_CHUNKS=256;
    private static final long NANOS_PER_TICK=1_500_000;
    private static final Logger LOG=LoggerFactory.getLogger("Mysticism-Extraction");
    /** Notification AFTER atomic source merge/split publication. Listeners migrate influence state,
     * not repository geometry. Old IDs remain explicit even after aliases resolve to the canonical ID. */
    @FunctionalInterface public interface TopologyListener {void committed(MinecraftServer server,List<String> previousIds,List<String> replacementIds);}
    public static final Event<TopologyListener> COMMITTED_TOPOLOGY=EventFactory.createArrayBacked(TopologyListener.class,listeners->(server,previous,next)->{
        for(var listener:listeners)try{listener.committed(server,previous,next);}catch(RuntimeException error){LOG.error("Committed extraction topology influence listener failed",error);}
    });
    private static final Map<MinecraftServer,Controller> SERVERS=new IdentityHashMap<>();
    private static boolean initialized;
    private LandmarkExtractionService(){}
    public static synchronized void init(){
        if(initialized)return;initialized=true;
        ServerLifecycleEvents.SERVER_STARTED.register(server->SERVERS.put(server,new Controller(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{Controller c=SERVERS.remove(server);if(c!=null)c.close();});
        ServerTickEvents.END_SERVER_TICK.register(server->{Controller c=SERVERS.get(server);if(c!=null)c.tick();});
        ServerChunkEvents.CHUNK_LOAD.register((world,chunk)->{Controller c=SERVERS.get(world.getServer());if(c!=null)c.chunk(world,chunk,true);});
        ServerChunkEvents.CHUNK_UNLOAD.register((world,chunk)->{Controller c=SERVERS.get(world.getServer());if(c!=null)c.chunk(world,chunk,false);});
        ServerWorldEvents.UNLOAD.register((server,world)->{Controller c=SERVERS.get(server);if(c!=null)c.worldUnloaded(world);});
    }
    /** Call from the supplied World.setBlockState mixin, including before model readiness. */
    public static void changed(ServerWorld world,BlockPos pos){
        Controller c=SERVERS.get(world.getServer());if(c!=null)c.dirty(region(world,pos.getX(),pos.getY(),pos.getZ()));
    }
    private static ExtractionJournal.Region region(ServerWorld world,int x,int y,int z){return new ExtractionJournal.Region(world.getRegistryKey().getValue().toString(),Math.floorDiv(x-8,32)*32+8,Math.floorDiv(y,32)*32,Math.floorDiv(z-8,32)*32+8);}
    private record ChunkKey(String dimension,int x,int z){}
    private record Material(BlockPalette.State state,String item){}
    public record Stats(int pendingRegions,int trackedChunks,int journalRegions,int lastSampledCells,String status){}
    public static Stats stats(MinecraftServer server){Controller c=SERVERS.get(server);return c==null?new Stats(0,0,0,0,"Stopped"):new Stats(c.pending.size(),c.loaded.size(),c.journal.entries().size(),c.lastSampled,c.status);}

    private static final class Controller implements AutoCloseable {
        final MinecraftServer server;
        final LandmarkStore store;
        final ExtractionJournal journal;
        final ItemEmbeddingIndexState itemIndex;
        final LinkedHashSet<ExtractionJournal.Region> pending=new LinkedHashSet<>();
        final LinkedHashSet<ChunkKey> loaded=new LinkedHashSet<>();
        final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),Thread.ofPlatform().daemon().name("mysticism-extraction").factory(),new ThreadPoolExecutor.AbortPolicy());
        volatile boolean closed;
        Job job;long ticks,nextWarningTick;int lastSampled,playerCursor;String status="Starting";
        Controller(MinecraftServer server){this.server=server;store=LandmarkStore.get(server);journal=ExtractionJournal.get(server);itemIndex=ItemEmbeddingIndexState.get(server);for(var e:journal.entries())dirty(e.region);}
        void dirty(ExtractionJournal.Region r){
            if(job!=null && job.entry.region.equals(r))job.invalid=true;
            enqueue(r);
        }
        void enqueue(ExtractionJournal.Region r){
            if(job!=null && job.entry.region.equals(r) && !job.invalid)return;
            if(pending.size()<PENDING_REGIONS || pending.contains(r))pending.add(r);
            else status="Deferred region queue full; tracked chunks retry";
        }
        void chunk(ServerWorld world,WorldChunk chunk,boolean load){
            if(skip(world))return;ChunkPos p=chunk.getPos();ChunkKey key=new ChunkKey(world.getRegistryKey().getValue().toString(),p.x,p.z);
            boolean changed=!load || !loaded.contains(key);
            if(load){if(loaded.size()<TRACKED_CHUNKS)loaded.add(key);}else loaded.remove(key);
            if(changed && job!=null && job.world==world && job.root.intersects(new Bounds((long)p.x*16,world.getBottomY(),(long)p.z*16,(long)p.x*16+16,world.getTopY(),(long)p.z*16+16)))job.invalid=true;
            schedule(world,p.x,p.z); // unload invalidates snapshots, never turns missing cells into air
        }
        void worldUnloaded(ServerWorld world){
            String dimension=world.getRegistryKey().getValue().toString();if(job!=null && job.world==world)job.invalid=true;
            loaded.removeIf(chunk->chunk.dimension().equals(dimension));pending.removeIf(region->region.dimension().equals(dimension));
        }
        boolean skip(ServerWorld world){return world.getRegistryKey().getValue().toString().equals("mysticism:spirit");}
        void schedule(ServerWorld world,int cx,int cz){
            for(int x:new int[]{cx*16,cx*16+15})for(int z:new int[]{cz*16,cz*16+15})
                for(int y=Math.floorDiv(world.getBottomY(),32)*32;y<world.getTopY();y+=32)enqueue(region(world,x,y,z));
        }
        ServerWorld world(String dimension){return server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(dimension)));}
        void tick(){
            long deadline=System.nanoTime()+NANOS_PER_TICK;ticks++;lastSampled=0;
            try {
                // Bootstrap already-loaded spawn/player chunks without enumerating or loading the world.
                if(ticks==1){ServerWorld w=server.getOverworld();BlockPos spawn=w.getSpawnPos();WorldChunk chunk=w.getChunkManager().getWorldChunk(spawn.getX()>>4,spawn.getZ()>>4);if(chunk!=null)chunk(w,chunk,true);}
                if(ticks%200==1){var players=server.getPlayerManager().getPlayerList();for(int i=0;i<Math.min(4,players.size());i++){
                    var player=players.get(Math.floorMod(playerCursor++,players.size()));ServerWorld w=player.getServerWorld();if(skip(w))continue;ChunkPos p=player.getChunkPos();
                    WorldChunk chunk=w.getChunkManager().getWorldChunk(p.x,p.z);if(chunk!=null)chunk(w,chunk,true);
                }}
                // Round-robin loaded chunks retries queue overflow, edits without the optional mixin, and frontiers.
                if(ticks%80==0 && !loaded.isEmpty()){
                    ChunkKey k=loaded.iterator().next();loaded.remove(k);loaded.add(k);ServerWorld w=world(k.dimension());
                    if(w!=null && w.getChunkManager().getWorldChunk(k.x(),k.z())!=null)schedule(w,k.x(),k.z());else loaded.remove(k);
                }
                if(job!=null && job.invalid){job.cancel();job=null;}
                if(job==null && !pending.isEmpty()){
                    var r=pending.iterator().next();pending.remove(r);ServerWorld w=world(r.dimension());
                    if(w!=null && anyLoaded(w,r)){var e=journal.entry(r);if(e!=null)job=new Job(this,w,e);else status="Region persistence budget exhausted";}
                }
                if(job!=null){job.advance(deadline);lastSampled=job.lastSampled;if(job.done){job.cancel();job=null;}}
            } catch(RuntimeException error){
                status="Deferred: "+error.getClass().getSimpleName()+": "+error.getMessage();
                if(ticks>=nextWarningTick){LOG.warn("Extraction job deferred; repository unchanged where uncommitted",error);nextWarningTick=ticks+200;}
                if(job!=null){var retry=job.entry.region;job.cancel();job=null;if(!closed)enqueue(retry);}
            }
        }
        boolean anyLoaded(ServerWorld world,ExtractionJournal.Region r){
            for(int x=r.x();x<r.x()+32;x+=8)for(int z=r.z();z<r.z()+32;z+=8)
                if(world.getChunkManager().getWorldChunk(Math.floorDiv(x,16),Math.floorDiv(z,16))!=null)return true;
            return false;
        }
        @Override public void close(){closed=true;if(job!=null)job.cancel();pending.clear();loaded.clear();worker.shutdownNow();status="Stopped";}
    }

    private static final class Job {
        final Controller c;final ServerWorld world;final ExtractionJournal.Entry entry;final Bounds root;final long version;
        final ExtractionGraph.Observation[] cells=new ExtractionGraph.Observation[ExtractionGraph.MAX_CELLS];
        final HashMap<BlockState,Material> materials=new HashMap<>();
        final TreeMap<String,Landmark> old=new TreeMap<>();
        final Map<String,List<GeometryPage>> priorPages=new TreeMap<>();
        final Map<String,SourceGeometry> priorGeometry=new HashMap<>();
        int priorPageCount;
        final ArrayList<GeometryPage> readPages=new ArrayList<>();
        final ArrayDeque<String> oldIds;
        LandmarkStore.GeometryRead read;
        CompletableFuture<List<ExtractionGraph.Feature>> graph;
        CompletableFuture<Vec384f> embedding;
        List<ExtractionGraph.Feature> features;int output;
        final Map<String,Vec384f> vectors=new HashMap<>();
        final Set<String> processed=new HashSet<>(),refreshedParents=new HashSet<>();
        final Map<String,Map<String,SourceGeometry>> mergeMasks=new HashMap<>();
        ExtractionGraph.Feature postMerge;
        boolean topologyPending;
        String fingerprint;boolean unchanged;
        ArrayDeque<Landmark> validateOld;
        ArrayDeque<String> retire;
        LandmarkStore.PendingMutation mutation;
        List<String> removeAfter=List.of(),addAfter=List.of();
        int sampled,lastSampled;volatile boolean invalid;boolean done;
        final LinkedHashSet<String> committed;
        Job(Controller c,ServerWorld world,ExtractionJournal.Entry entry){
            this.c=c;this.world=world;this.entry=entry;root=Bounds.cube(entry.region.x(),entry.region.y(),entry.region.z(),32);
            version=c.journal.reserve(entry);oldIds=new ArrayDeque<>(entry.ids());committed=new LinkedHashSet<>(entry.ids());
        }
        void cancel(){invalid=true;if(read!=null)read.cancel();if(mutation!=null && !mutation.complete())mutation.cancel();if(graph!=null)graph.cancel(true);if(embedding!=null)embedding.cancel(true);}
        void advance(long deadline){
            lastSampled=0;
            if(mutation!=null){
                mutation.advance(1,256);if(mutation.complete()){
                    committed.removeAll(removeAfter);committed.addAll(addAfter);c.journal.publish(entry,new ArrayList<>(committed));mutation=null;embedding=null;
                    if(topologyPending){topologyPending=false;COMMITTED_TOPOLOGY.invoker().committed(c.server,removeAfter,addAfter);}
                    if(postMerge!=null){
                        var feature=postMerge;postMerge=null;var m=c.store.metadata(addAfter.getFirst()).orElseThrow();Landmark h=m.header();
                        Landmark refreshed=new Landmark(h.id(),h.dimension(),h.algorithmVersion(),h.kind(),h.biome(),h.anchor(),h.bounds(),LandmarkProfiles.wrap(vectors.get(feature.id())),feature.importance(),h.activity(),h.ownership(),feature.geometry(),Math.incrementExact(h.revision()),"merged observed source snapshot "+version);
                        mutation=c.store.stagePut(refreshed,h.revision());removeAfter=List.of();addAfter=List.of();
                    }
                }return;
            }
            // Stream prior masks incrementally; no resident-only find(), no cold whole-landmark hydration on tick.
            if(read!=null || !oldIds.isEmpty()){
                if(read==null){String id=oldIds.removeFirst();var metadata=c.store.metadata(id);if(metadata.isEmpty()){committed.remove(id);return;}read=c.store.beginGeometryRead(id);}
                read.advance(1,256);readPages.addAll(read.drain());
                if(priorPageCount+readPages.size()>512)throw new IllegalStateException("Prior regional page budget exceeded");
                if(read.complete()){
                    if(!read.isCurrent()){invalid=true;return;}
                    Landmark value=read.metadata().header();old.put(value.id(),value);
                    priorPages.put(value.id(),List.copyOf(readPages));priorPageCount+=readPages.size();read=null;readPages.clear();
                }return;
            }
            if(sampled<cells.length){
                while(lastSampled<CELLS_PER_TICK && sampled<cells.length && System.nanoTime()<deadline){sample(sampled++);lastSampled++;}
                c.status="Observing "+sampled+"/"+cells.length;return;
            }
            if(graph==null){
                graph=CompletableFuture.supplyAsync(()->{
                    fingerprint=ObservationFingerprints.of(cells);
                    if(fingerprint.equals(entry.fingerprint()) && old.size()==entry.ids().size()){unchanged=true;return List.of();}
                    ArrayList<ExtractionGraph.Seed> seeds=new ArrayList<>();
                    for(var value:old.values()){
                        SourceGeometry geometry=new SourceGeometry(priorPages.get(value.id()),value.geometry().frontiers());priorGeometry.put(value.id(),geometry);
                        seeds.add(new ExtractionGraph.Seed(value.id(),value.kind(),value.biome(),value.anchor(),geometry,value.algorithmVersion()));
                    }
                    ExtractionGraph g=new ExtractionGraph(entry.region.dimension(),root,cells,seeds,version,entry.seen());
                    while(!g.complete()){if(c.closed || invalid || Thread.currentThread().isInterrupted())throw new CancellationException();g.advance(256);}
                    if(g.overflow())throw new IllegalStateException("Regional feature budget exceeded; no partial graph published");
                    List<ExtractionGraph.Feature> result=g.finish().stream().sorted(Comparator.<ExtractionGraph.Feature>comparingInt(f->f.parents().size()>1?0:f.parents().size()==1?1:2).thenComparing(ExtractionGraph.Feature::id)).toList();validateTopology(result);
                    for(var feature:result)if(feature.parents().size()>1)mergeMasks.put(feature.id(),ExtractionGraph.refreshParents(entry.region.dimension(),feature,seeds,version));
                    return result;
                },c.worker);c.status="Graph worker";return;
            }
            if(!graph.isDone())return;
            if(features==null){features=graph.getNow(List.of());c.journal.requireCapacity(entry,features.stream().map(ExtractionGraph.Feature::id).toList());if(unchanged){done=true;c.status="Unchanged source";return;}validateOld=new ArrayDeque<>(old.values());retire=new ArrayDeque<>(committed);}
            if(!validateOld.isEmpty()){
                Landmark value=validateOld.removeFirst();var current=c.store.metadata(value.id());
                if(current.isEmpty() || current.get().revision()!=value.revision())throw new IllegalStateException("Stale extraction parent");return;
            }
            // Remove vanished parents before births; merge reductions run before split/birth
            // growth so temporary publication never exceeds the regional identity cap.
            if(!retire.isEmpty()){
                String id=retire.removeFirst();if(committed.contains(id) && features.stream().noneMatch(f->f.parents().contains(id) || f.id().equals(id))){
                    var m=c.store.metadata(id);if(m.isPresent()){mutation=c.store.stageDelete(new LandmarkRepository.RevisionRef(id,m.get().revision()));removeAfter=List.of(id);addAfter=List.of();return;}
                    committed.remove(id);
                }return;
            }
            while(output<features.size() && processed.contains(features.get(output).id()))output++;
            if(output>=features.size()){
                c.journal.publish(entry,new ArrayList<>(committed));c.journal.completed(entry,fingerprint);done=true;c.status="Ready";return;
            }
            ExtractionGraph.Feature feature=features.get(output);
            List<ExtractionGraph.Feature> family=feature.parents().size()==1?features.stream().filter(f->f.parents().contains(feature.parents().getFirst())).toList():List.of(feature);
            for(var sibling:family)if(!vectors.containsKey(sibling.id())){
                if(embedding==null){embedding=semantic(sibling);c.status="Embedding descriptors (async)";return;}
                if(!embedding.isDone())return;
                vectors.put(sibling.id(),embedding.getNow(null));embedding=null;return;
            }
            Landmark value=landmark(feature,vectors.get(feature.id()));
            List<String> parents=feature.parents();
            if(parents.size()>1){
                for(String id:parents)if(!refreshedParents.contains(id)){
                    Landmark original=old.get(id);SourceGeometry geometry=mergeMasks.get(feature.id()).get(id);
                    Bounds bounds=Bounds.cube(original.anchor().x(),original.anchor().y(),original.anchor().z(),1);for(var p:geometry.pages())bounds=bounds.union(p.bounds());
                    Landmark refreshed=new Landmark(id,original.dimension(),original.algorithmVersion(),original.kind(),original.biome(),original.anchor(),bounds,original.baseEmbedding(),original.baseImportance(),original.activity(),original.ownership(),geometry,Math.incrementExact(original.revision()),"edited source mask before verified merge "+version);
                    mutation=c.store.stagePut(refreshed,original.revision());refreshedParents.add(id);removeAfter=List.of();addAfter=List.of();return;
                }
                ArrayList<LandmarkRepository.RevisionRef> refs=new ArrayList<>();for(String id:parents){var m=c.store.metadata(id).orElseThrow();refs.add(new LandmarkRepository.RevisionRef(id,m.revision()));}
                mutation=c.store.stageMerge(new LandmarkRepository.VerifiedConnectivity(refs,"six-neighbour observed air; exact biome key; source snapshot "+version),c.ticks,new ImportancePolicy(1,12000,.001,.5),feature.geometry());
                removeAfter=parents;addAfter=List.of(parents.stream().min(String::compareTo).orElseThrow());postMerge=feature;topologyPending=true;output++;
            } else if(parents.size()==1 && (family.size()>1 || !value.id().equals(parents.getFirst()))){
                String parent=parents.getFirst();var metadata=c.store.metadata(parent).orElseThrow();Landmark original=metadata.header();ArrayList<Landmark> children=new ArrayList<>();
                Bounds expanded=original.bounds();
                for(var sibling:family){Landmark child=landmark(sibling,vectors.get(sibling.id()));expanded=expanded.union(child.bounds());
                    children.add(new Landmark(child.id(),child.dimension(),child.algorithmVersion(),child.kind(),child.biome(),child.anchor(),child.bounds(),child.baseEmbedding(),child.baseImportance(),original.activity(),original.ownership(),child.geometry(),Math.incrementExact(original.revision()),child.provenance()));}
                if(!original.bounds().contains(expanded)){
                    Landmark grown=new Landmark(parent,original.dimension(),original.algorithmVersion(),original.kind(),original.biome(),original.anchor(),expanded,original.baseEmbedding(),original.baseImportance(),original.activity(),original.ownership(),priorGeometry.get(parent),Math.incrementExact(original.revision()),"observed split bounds expansion "+version);
                    mutation=c.store.stagePut(grown,original.revision());removeAfter=List.of();addAfter=List.of();return;
                }
                children.forEach(child->processed.add(child.id()));
                mutation=c.store.stageSplit(new LandmarkRepository.RevisionRef(parent,metadata.revision()),children);
                removeAfter=List.of(parent);addAfter=children.stream().map(Landmark::id).toList();topologyPending=true;output++;
            } else {
                var metadata=c.store.metadata(value.id());long expected=metadata.map(LandmarkMetadata::revision).orElse(-1L);
                mutation=c.store.stagePut(value,expected);removeAfter=parents;addAfter=List.of(value.id());output++;
            }
        }
        static void validateTopology(List<ExtractionGraph.Feature> features){
            for(var f:features)if(f.parents().size()>1 && features.stream().anyMatch(other->other!=f && other.parents().stream().anyMatch(f.parents()::contains)))
                throw new IllegalStateException("Many-to-many topology change deferred");
        }
        void sample(int i){
            int x=(int)root.minX()+i%32,y=(int)root.minY()+i/1024,z=(int)root.minZ()+(i/32)%32;
            if(y<world.getBottomY() || y>=world.getTopY())return;
            WorldChunk chunk=world.getChunkManager().getWorldChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));if(chunk==null)return;
            BlockPos pos=new BlockPos(x,y,z);BlockState state=chunk.getBlockState(pos);
            Material material=materials.get(state);
            if(material==null){
                if(materials.size()>=512)throw new IllegalStateException("Snapshot palette budget");
                TreeMap<String,String> properties=new TreeMap<>();for(var p:state.getEntries().entrySet())properties.put(p.getKey().getName(),property(p.getKey(),p.getValue()));
                material=new Material(new BlockPalette.State(Registries.BLOCK.getId(state.getBlock()).toString(),properties),Registries.ITEM.getId(state.getBlock().asItem()).toString());materials.put(state,material);
            }
            String biome=chunk.getBiomeForNoiseGen(x>>2,y>>2,z>>2).getKey().orElseThrow().getValue().toString();
            int top=chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x&15,z&15);
            // Heightmap proof: any observed air above the obstructing column is outside, including dimensions without skylight.
            cells[i]=new ExtractionGraph.Observation(material.state(),biome,material.item(),state.isAir(),state.isAir() && y>top,top,world.getSeaLevel());
        }
        @SuppressWarnings({"rawtypes","unchecked"}) static String property(Property p,Comparable v){return p.name(v);}
        CompletableFuture<Vec384f> semantic(ExtractionGraph.Feature feature){
            ArrayList<DescriptorVectors.WeightedDescriptor> descriptors=new ArrayList<>();
            descriptors.add(new DescriptorVectors.WeightedDescriptor(CanonicalDescriptors.region(entry.region.dimension(),feature.biome()),2));
            descriptors.add(new DescriptorVectors.WeightedDescriptor("landmark "+feature.kind().name().toLowerCase(Locale.ROOT)+". "+(feature.kind()==Landmark.Kind.CAVE?"underground connected air cavity, rock walls, "+(feature.geometry().frontierClosed()?"observed enclosure":"unknown open frontier"):feature.kind()==Landmark.Kind.MOUNTAIN?"elevated mountain terrain above sea level":"surface terrain biome"),2));
            List<Map.Entry<String,Integer>> items=feature.items().entrySet().stream().filter(e->!e.getKey().equals("minecraft:air")).sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(12).toList();
            var index=c.itemIndex;ArrayList<DescriptorVectors.WeightedVector> fallback=new ArrayList<>();
            feature.blocks().entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(12).forEach(block->
                descriptors.add(new DescriptorVectors.WeightedDescriptor("source block palette "+block.getKey(),Math.sqrt(block.getValue()))));
            for(var item:items){
                Vec384f vector=index.isPopulated()?index.getVec(item.getKey()):null;if(vector!=null)fallback.add(new DescriptorVectors.WeightedVector(vector,Math.sqrt(item.getValue())));
            }
            Vec384f fallbackVector=fallback.isEmpty()?null:DescriptorVectors.compose(fallback);
            if(!EmbeddingHelper.isReady())return fallbackVector==null?CompletableFuture.failedFuture(new IllegalStateException("Nomic and compatible item index unavailable")):CompletableFuture.completedFuture(fallbackVector);
            return EmbeddingHelper.composeDescriptors(descriptors).handle((vector,error)->{
                if(error==null)return vector;if(fallbackVector!=null)return fallbackVector.clone();throw new CompletionException(error);
            });
        }
        Landmark landmark(ExtractionGraph.Feature f,Vec384f vector){
            Bounds bounds=Bounds.cube(f.anchor().x(),f.anchor().y(),f.anchor().z(),1);for(var page:f.geometry().pages())bounds=bounds.union(page.bounds());
            Landmark previous=old.get(f.id());long revision=previous==null?0:Math.incrementExact(previous.revision());
            return new Landmark(f.id(),entry.region.dimension(),f.algorithmVersion(),f.kind(),f.biome(),f.anchor(),bounds,LandmarkProfiles.wrap(vector),f.importance(),previous==null?new ActivityMetadata(0,0):previous.activity(),previous==null?new Ownership(List.of()):previous.ownership(),f.geometry(),revision,"loaded terrain; "+f.airCells()+" air; "+f.solidCells()+" material cells; page revision "+version);
        }
    }
}
