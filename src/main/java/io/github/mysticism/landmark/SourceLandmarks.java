package io.github.mysticism.landmark;

import io.github.mysticism.activity.*;
import io.github.mysticism.embedding.*;
import io.github.mysticism.landmark.extract.*;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import java.util.*;
import java.util.concurrent.*;

/** Real bounded source-coordinate pipeline. Chunks are read-only IO, not ownership or identity.
 * One live operation per server; detached topology/encoding work runs on a bounded worker.
 * No generation, source writes, joins, model inference or catalogue hydration on tick. */
public final class SourceLandmarks {
    public static final String ALGORITHM="source-octree-v2";
    public record Cell(BlockPoint position,BlockPalette.State material,String biome,boolean sky) {
        public boolean air(){return Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(material.blockId());}
    }
    public record Region(String dimension,Bounds bounds,List<Cell> cells,boolean complete){public Region{cells=List.copyOf(cells);}}
    private static final Map<MinecraftServer,Session> SESSIONS=new IdentityHashMap<>();private static boolean initialized;
    private SourceLandmarks(){}
    public static void init(){
        if(initialized)return;initialized=true;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{var store=LandmarkStore.get(server);if(!store.usesNativeSourceProfile(LandmarkProfiles.current()))SpiritActivityService.discardGeneratedInfluences(server);store.clearGeneratedIfIncompatible(LandmarkProfiles.current());SESSIONS.put(server,new Session(server));});
        ServerTickEvents.END_SERVER_TICK.register(server->{Session s=SESSIONS.get(server);if(s!=null)s.tick();});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{Session s=SESSIONS.remove(server);if(s!=null)s.close();});
        ServerWorldEvents.UNLOAD.register((server,world)->{Session s=SESSIONS.get(server);if(s!=null)s.unload(world.getRegistryKey().getValue().toString());});
        ServerChunkEvents.CHUNK_UNLOAD.register((world,chunk)->{Session s=SESSIONS.get(world.getServer());if(s!=null&&s.active!=null){String dim=world.getRegistryKey().getValue().toString();Bounds area=new Bounds(chunk.getPos().getStartX(),world.getBottomY(),chunk.getPos().getStartZ(),chunk.getPos().getStartX()+16L,world.getTopY(),chunk.getPos().getStartZ()+16L);if(s.active.dimension.equals(dim)&&s.active.guarded!=null&&s.active.guarded.intersects(area))s.active.cancel(new CancellationException("source chunk unloaded during observation"));}});
        ServerChunkEvents.CHUNK_LOAD.register((world,chunk)->{
            if(!SourceDimensions.isSource(world.getRegistryKey().getValue().toString()))return;
            Session s=SESSIONS.get(world.getServer());if(s==null)return;
            int x=chunk.getPos().getStartX()+8,z=chunk.getPos().getStartZ()+8;
            var center=new BlockPos(x,chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x&15,z&15)+1,z);
            s.hint(world.getRegistryKey().getValue().toString(),center);
            if(s.retries.size()<64)s.retries.add(new FrontierRetry(world.getRegistryKey().getValue().toString(),new Bounds(chunk.getPos().getStartX()-1L,world.getBottomY(),chunk.getPos().getStartZ()-1L,chunk.getPos().getStartX()+17L,world.getTopY(),chunk.getPos().getStartZ()+17L)));
            s.survey(world,chunk,center);
        });
    }
    public static CompletableFuture<Optional<LandmarkMetadata>> ensureSourceLocation(MinecraftServer server,String dimension,BlockPos position){return ensure(server,dimension,position,null,false);}
    private static CompletableFuture<Optional<LandmarkMetadata>> ensure(MinecraftServer server,String dimension,BlockPos position,String preferred,boolean activity){
        Session s=session(server);var future=new CompletableFuture<Optional<LandmarkMetadata>>();
        if(!SourceDimensions.isSource(dimension)){future.complete(Optional.empty());return future;}
        s.offer(new Ensure(s,dimension,position.toImmutable(),preferred,activity,future));return future;
    }
    /** Near-view refinement and growth use exact generated material samples. */
    public static CompletableFuture<Optional<LandmarkMetadata>> refine(MinecraftServer server,String dimension,BlockPos position){return ensureSourceLocation(server,dimension,position);}
    public static CompletableFuture<Region> region(MinecraftServer server,String dimension,Bounds bounds,int maxCells){
        Session s=session(server);checkBounds(bounds,maxCells);var future=new CompletableFuture<Region>();
        if(!SourceDimensions.isSource(dimension)){future.complete(new Region(dimension,bounds,List.of(),false));return future;}
        s.offer(new Read(s,dimension,bounds,future));return future;
    }
    /** Exact persisted ownership of both known AIR and SOLID source blocks. Missing map
     * entries grant no ownership. Does not wait for model readiness or access source chunks.
     * Caller must check Region.isCurrent(server) before mesh/support/exit use. */
    public static CompletableFuture<SourceOwnership.Region> owners(MinecraftServer server,String dimension,Bounds bounds,int maxCells){
        Session s=session(server);checkBounds(bounds,maxCells);var future=new CompletableFuture<SourceOwnership.Region>();
        if(!SourceDimensions.isSource(dimension)){future.completeExceptionally(new IllegalArgumentException("not a source dimension"));return future;}
        if(s.ownerRequests.size()>=8){future.completeExceptionally(new RejectedExecutionException("source ownership request budget"));return future;}
        s.ownerRequests.addLast(new SourceOwnership.Request(s.store,dimension,bounds,future));return future;
    }
    /** Explicit bounded query only; NEVER an automatic destination replacement policy. */
    public static CompletableFuture<Optional<BlockPos>> safeAir(MinecraftServer server,String dimension,BlockPos position,int radius){
        if(radius<0||radius>8)throw new IllegalArgumentException("safe air radius");
        Session s=session(server);BlockPos center=position.toImmutable();
        Bounds b=new Bounds(position.getX()-radius,position.getY()-radius-1L,position.getZ()-radius,position.getX()+radius+1L,position.getY()+radius+3L,position.getZ()+radius+1L);
        return region(server,dimension,b,32768).thenCompose(r->s.computeAndDeliver(dimension,()->{Map<BlockPoint,Cell> cells=new HashMap<>();r.cells.forEach(c->cells.put(c.position,c));
            return r.cells.stream().filter(Cell::air).filter(c->Math.abs(c.position.x()-center.getX())<=radius&&Math.abs(c.position.y()-center.getY())<=radius&&Math.abs(c.position.z()-center.getZ())<=radius)
                .filter(c->{Cell head=cells.get(new BlockPoint(c.position.x(),c.position.y()+1,c.position.z())),floor=cells.get(new BlockPoint(c.position.x(),c.position.y()-1,c.position.z()));return head!=null&&head.air()&&floor!=null&&fullFloor(floor.material);})
                .sorted(Comparator.<Cell>comparingDouble(c->distance(c.position,center)).thenComparingLong(c->c.position.x()).thenComparingLong(c->c.position.y()).thenComparingLong(c->c.position.z()))
                .map(c->new BlockPos((int)c.position.x(),(int)c.position.y(),(int)c.position.z())).findFirst();}));
    }
    private static boolean fullFloor(BlockPalette.State m){return Set.of("minecraft:stone","minecraft:dirt","minecraft:grass_block","minecraft:deepslate","minecraft:netherrack","minecraft:end_stone","minecraft:bedrock","minecraft:sandstone").contains(m.blockId());}
    private static double distance(BlockPoint p,BlockPos q){return (p.x()-q.getX())*(double)(p.x()-q.getX())+(p.y()-q.getY())*(double)(p.y()-q.getY())+(p.z()-q.getZ())*(double)(p.z()-q.getZ());}
    public static CompletableFuture<Boolean> transfer(MinecraftServer server,String receiverId,String donorId,Bounds cells){
        checkBounds(cells,32768);Session s=session(server);var future=new CompletableFuture<Boolean>();s.offer(new Transfer(s,receiverId,donorId,cells,false,false,future));return future;
    }
    /** Real activity bridge: grows contiguous unowned source cells, then attempts a physically
     * adjoining converged union. Initial cave biome cutoff does not veto this activity path. */
    public static void activity(MinecraftServer server,String landmarkId,String dimension,BlockPos position){
        ensure(server,dimension,position,landmarkId,true); // bounded operation retains the actual event location
    }
    public static void changed(ServerWorld world,BlockPos pos){
        if(!SourceDimensions.isSource(world.getRegistryKey().getValue().toString()))return;
        Session s=SESSIONS.get(world.getServer());if(s!=null){s.invalidate(world.getRegistryKey().getValue().toString(),pos);s.hint(world.getRegistryKey().getValue().toString(),pos.toImmutable());}
    }
    public static int pending(MinecraftServer server){Session s=SESSIONS.get(server);return s==null?0:s.requests.size()+s.hints.size()+(s.active==null?0:1);}
    /** Bounded pending-hint budget; retention now follows recency instead of freezing on
     * the first chunks ever seen (render-distance streaming must keep landmarking). */
    public static final int HINT_BUDGET=256;
    /** Recency admission: duplicates refresh to newest, the oldest entry is evicted at capacity. */
    static <T> void admit(LinkedHashSet<T> set,T value,int cap){
        if(cap<1)throw new IllegalArgumentException("hint budget");Objects.requireNonNull(value);
        if(!set.remove(value)&&set.size()>=cap){var oldest=set.iterator();oldest.next();oldest.remove();}
        set.add(value);
    }
    public static int lastSampledCells(MinecraftServer server){Session s=SESSIONS.get(server);return s==null?0:s.lastCells;}
    public static String status(MinecraftServer server){Session s=SESSIONS.get(server);return s==null?"Stopped":s.status;}
    private static Session session(MinecraftServer server){if(!server.isOnThread())throw new IllegalStateException("source server thread");Session s=SESSIONS.get(server);if(s==null)throw new IllegalStateException("source service not initialized");return s;}
    private static void checkBounds(Bounds b,int maxCells){long volume=Math.multiplyExact(Math.multiplyExact(b.maxX()-b.minX(),b.maxY()-b.minY()),b.maxZ()-b.minZ());if(maxCells<1||maxCells>32768||volume>maxCells||b.minX()<Integer.MIN_VALUE||b.maxX()>Integer.MAX_VALUE||b.minY()<Integer.MIN_VALUE||b.maxY()>Integer.MAX_VALUE||b.minZ()<Integer.MIN_VALUE||b.maxZ()>Integer.MAX_VALUE)throw new IllegalArgumentException("source query bounds");}
    private record Hint(String dimension,BlockPos pos){}
    private static final class FrontierRetry {
        final String dimension;final Bounds bounds;String cursor;boolean end;final ArrayDeque<LandmarkMetadata> headers=new ArrayDeque<>();Iterator<FrontierFace> faces;
        FrontierRetry(String dimension,Bounds bounds){this.dimension=dimension;this.bounds=bounds;}
        boolean advance(Session s){
            if(faces!=null){for(int n=0;n<8&&faces.hasNext();n++){var b=faces.next().missingBounds();if(bounds.contains(b.minX(),b.minY(),b.minZ()))s.hint(dimension,new BlockPos((int)b.minX(),(int)b.minY(),(int)b.minZ()));}if(!faces.hasNext())faces=null;return false;}
            if(!headers.isEmpty()){var h=headers.removeFirst().header();if(h.kind()==Landmark.Kind.CAVE)faces=h.geometry().frontiers().iterator();return false;}
            if(end)return true;var page=s.store.sourceRangePage(dimension,bounds,cursor,2,2);cursor=page.nextId();end=page.end();headers.addAll(page.landmarks());return false;
        }
    }
    private static final class Session {
        final MinecraftServer server;final LandmarkStore store;final ItemEmbeddingIndexState itemIndex;final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),Thread.ofPlatform().daemon().name("mysticism-source").factory(),new ThreadPoolExecutor.AbortPolicy());
        final ArrayDeque<SourceOwnership.Request> ownerRequests=new ArrayDeque<>();
        final ArrayDeque<FrontierRetry> retries=new ArrayDeque<>();final Map<CompletableFuture<?>,String> auxiliary=new IdentityHashMap<>();final ArrayDeque<Operation<?>> requests=new ArrayDeque<>();final LinkedHashSet<Hint> hints=new LinkedHashSet<>();Operation<?> active;String status="Ready";int playerCursor,lastCells;
        Session(MinecraftServer server){this.server=server;store=LandmarkStore.get(server);itemIndex=ItemEmbeddingIndexState.get(server);}
        <T> CompletableFuture<T> computeAndDeliver(String dim,java.util.function.Supplier<T> computation){
            var out=new CompletableFuture<T>();if(auxiliary.size()>=64){out.completeExceptionally(new RejectedExecutionException("source auxiliary budget"));return out;}auxiliary.put(out,dim);
            try{CompletableFuture.supplyAsync(computation,worker).whenComplete((value,error)->server.execute(()->{auxiliary.remove(out);if(SESSIONS.get(server)!=this)out.cancel(false);else if(error!=null)out.completeExceptionally(error);else out.complete(value);}));}catch(RuntimeException failure){auxiliary.remove(out);out.completeExceptionally(failure);}return out;
        }
        void offer(Operation<?> op){if(requests.size()==64){op.future.completeExceptionally(new RejectedExecutionException("source request budget"));return;}if(op instanceof Read)requests.addFirst(op);else requests.addLast(op);}
        void hint(String dim,BlockPos p){admit(hints,new Hint(dim,p.toImmutable()),HINT_BUDGET);}
        /** Generation landmarking beyond the chunk-center probe: derive representative
         * surface/peak hints from the loaded chunk's actual heightmap and biome grid so
         * real terrain across the render distance becomes landmark work, bounded per chunk. */
        void survey(ServerWorld world,WorldChunk chunk,BlockPos center){
            String dimension=world.getRegistryKey().getValue().toString();int bottom=world.getBottomY(),top=world.getTopY();var origin=chunk.getPos();
            List<GenerationSurvey.Probe> probes;
            try{probes=GenerationSurvey.survey(origin.getStartX(),origin.getStartZ(),bottom,top,new GenerationSurvey.ColumnView(){
                public int height(int localX,int localZ){
                    try{return chunk.hasHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES)?chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,localX,localZ):Integer.MIN_VALUE;}
                    catch(RuntimeException failure){return Integer.MIN_VALUE;}
                }
                public String biome(int localX,int localZ,int surfaceY){
                    try{return world.getBiomeForNoiseGen((origin.getStartX()+localX)>>2,Math.max(bottom,Math.min(top-1,surfaceY))>>2,(origin.getStartZ()+localZ)>>2).getKey().map(key->key.getValue().toString()).orElse(null);}
                    catch(RuntimeException failure){return null;}
                }
            });}catch(RuntimeException failure){return;}
            for(var probe:GenerationSurvey.spread(probes,center.getX(),center.getY(),center.getZ(),10))hint(dimension,new BlockPos(probe.x(),probe.y(),probe.z()));
        }
        void invalidate(String dim,BlockPos pos){if(active!=null&&active.dimension.equals(dim)&&active.guarded!=null&&active.guarded.contains(pos.getX(),pos.getY(),pos.getZ()))active.cancel(new CancellationException("source edit invalidated snapshot"));}
        void tick(){
            lastCells=0;
            if(!ownerRequests.isEmpty()){var request=ownerRequests.peekFirst();try{request.advance();}catch(RuntimeException failure){request.cancel(failure);}if(request.done)ownerRequests.removeFirst();}
            if(server.getTicks()%5==0&&!retries.isEmpty()){try{if(retries.peekFirst().advance(this))retries.removeFirst();}catch(RuntimeException stale){retries.removeFirst();}}
            if(server.getTicks()%200==0){var players=server.getPlayerManager().getPlayerList();for(int n=0;n<Math.min(2,players.size());n++){var p=players.get(Math.floorMod(playerCursor++,players.size()));if(SourceDimensions.isSource(p.getServerWorld().getRegistryKey().getValue().toString()))hint(p.getServerWorld().getRegistryKey().getValue().toString(),p.getBlockPos());}}
            boolean ready=EmbeddingHelper.isReady()||itemIndex.isPopulated();
            if(active==null&&!requests.isEmpty()&&(requests.peekFirst() instanceof Read||ready))active=requests.removeFirst();
            if(active==null&&ready&&!hints.isEmpty()){Hint h=hints.iterator().next();hints.remove(h);active=new Ensure(this,h.dimension,h.pos,null,false,new CompletableFuture<>());}
            if(active==null)return;
            try{if(active.future.isCancelled())active.cancel(new CancellationException());if(!active.done)active.advance();if(active.done){active.release();active=null;status="Ready";}}
            catch(RuntimeException e){status="Deferred: "+e.getMessage();active.cancel(e);active.release();active=null;}
        }
        void unload(String dim){ownerRequests.removeIf(request->{if(request.dimension.equals(dim)){request.cancel(new CancellationException("source dimension unloaded"));return true;}return false;});retries.removeIf(r->r.dimension.equals(dim));auxiliary.entrySet().removeIf(e->{if(e.getValue().equals(dim)){e.getKey().cancel(false);return true;}return false;});if(active!=null&&active.dimension.equals(dim)){active.cancel(new CancellationException("source dimension unloaded"));active.release();active=null;}requests.removeIf(op->{if(op.dimension.equals(dim)){op.cancel(new CancellationException("source dimension unloaded"));return true;}return false;});hints.removeIf(h->h.dimension.equals(dim));}
        void close(){ownerRequests.forEach(request->request.cancel(new CancellationException("server stopping")));ownerRequests.clear();auxiliary.keySet().forEach(f->f.cancel(false));auxiliary.clear();retries.clear();if(active!=null){active.cancel(new CancellationException("server stopping"));active.release();}requests.forEach(op->op.cancel(new CancellationException("server stopping")));requests.clear();hints.clear();worker.shutdownNow();}
        ServerWorld world(String dimension){return SourceDimensions.isSource(dimension)?server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(dimension))):null;}
    }
    private abstract static class Operation<T> {
        final Session s;String dimension;Bounds bounds,guarded;final CompletableFuture<T> future;boolean done;
        GeneratedSourceReader reader;CompletableFuture<Region> snapshot;CompletableFuture<Landmark> hydrated;LandmarkStore.GeometryRead geometryRead;LandmarkStore.PendingMutation mutation;CompletableFuture<?> work;Runnable resume;
        LandmarkExtractionService.TopologyPlan history;
        Operation(Session s,String dimension,Bounds bounds,CompletableFuture<T> future){this.s=s;this.dimension=dimension;this.bounds=bounds;this.guarded=bounds;this.future=future;}
        abstract void advance();
        void complete(T value){done=true;future.complete(value);}
        void pause(){if(resume==null)resume=SpiritActivityService.pauseLandmarkMutations(s.server);}
        void cancel(Throwable failure){done=true;if(reader!=null)reader.cancel();if(geometryRead!=null)geometryRead.cancel();if(work!=null)work.cancel(false);if(mutation!=null&&!mutation.complete())mutation.cancel();if(history!=null)history.cancel();future.completeExceptionally(failure);}
        void release(){if(resume!=null){resume.run();resume=null;}}
        boolean observe(){if(snapshot!=null)return snapshot.isDone();if(reader==null){ServerWorld world=s.world(dimension);if(world==null)throw new IllegalStateException("source world unavailable");reader=new GeneratedSourceReader(world,bounds,s.worker);}s.status="Source read";boolean complete=reader.advance(512);s.lastCells=reader.lastSampled;if(complete){snapshot=reader.resultAsync();work=snapshot;}return false;}
    }
    private static final class Read extends Operation<Region> {
        Read(Session s,String dimension,Bounds bounds,CompletableFuture<Region> future){super(s,dimension,bounds,future);}
        void advance(){if(observe())complete(snapshot.getNow(null));}
    }
    record Prepared(Landmark prior,String id,String biome,Landmark.Kind kind,BlockPoint anchor,SourceGeometry geometry,List<DescriptorVectors.WeightedDescriptor> descriptors,Map<String,Double> items,String adjoining){}
    private static final class Ensure extends Operation<Optional<LandmarkMetadata>> {
        final BlockPos position;final String preferred;final boolean activity;String cursor;boolean catalogEnd;
        final ArrayDeque<LandmarkMetadata> candidates=new ArrayDeque<>();final List<Landmark> parents=new ArrayList<>();final List<GeometryPage> pages=new ArrayList<>();
        final Map<String,Double> importance=new HashMap<>();Region observed;CompletableFuture<Prepared> prepared;CompletableFuture<Vec384f> embedding;Prepared value;Landmark published;long time;int seaLevel;
        Ensure(Session s,String dimension,BlockPos pos,String preferred,boolean activity,CompletableFuture<Optional<LandmarkMetadata>> future){super(s,dimension,new Bounds(pos.getX()-12L,pos.getY()-12L,pos.getZ()-12L,pos.getX()+13L,pos.getY()+13L,pos.getZ()+13L),future);this.position=pos;this.preferred=preferred;this.activity=activity;}
        void advance(){
            if(mutation!=null){mutation.advance(1,512);if(mutation.complete()){
                if(history!=null){history.commit();history=null;}
                var metadata=s.store.metadata(published.id());complete(metadata);
                if(published.kind()==Landmark.Kind.CAVE)for(var face:published.geometry().frontiers()){var b=face.missingBounds();s.hint(dimension,new BlockPos((int)b.minX(),(int)b.minY(),(int)b.minZ()));}
                if(value.adjoining!=null)s.offer(new Transfer(s,published.id(),value.adjoining,activity?bounds:null,true,activity,new CompletableFuture<>()));
            }return;}
            if(observed==null){if(observe()){observed=snapshot.getNow(null);time=s.server.getOverworld().getTime();seaLevel=reader.world.getSeaLevel();}return;}
            // Pause only at topology reads/staging; never while waiting for model readiness.
            if(prepared==null){
                if(hydrated!=null){if(!hydrated.isDone())return;parents.add(hydrated.getNow(null));hydrated=null;return;}
                if(geometryRead!=null){geometryRead.advance(1,512);pages.addAll(geometryRead.drain());if(pages.size()>512)throw new IllegalArgumentException("source parent page budget");if(geometryRead.complete()){if(!geometryRead.isCurrent())throw new IllegalStateException("stale source geometry");var meta=geometryRead.metadata();var immutable=List.copyOf(pages);hydrated=CompletableFuture.supplyAsync(()->LandmarkNbt.hydrate(meta,immutable),s.worker);work=hydrated;pages.clear();geometryRead=null;}return;}
                if(!candidates.isEmpty()){if(!s.store.geometryReadAvailable())return;var m=candidates.removeFirst();importance.put(m.id(),SpiritActivityService.importance(s.server,m));guarded=guarded.union(m.header().bounds());geometryRead=s.store.beginGeometryRead(m.id());return;}
                if(!catalogEnd){var page=s.store.sourceRangePage(dimension,bounds,cursor,4,4);cursor=page.nextId();catalogEnd=page.end();candidates.addAll(page.landmarks());if(parents.size()+candidates.size()>64)throw new IllegalArgumentException("local overlap operation budget");return;}
                List<Landmark> snapshot=List.copyOf(parents);prepared=CompletableFuture.supplyAsync(()->prepare(observed,snapshot,position,preferred,activity,time,seaLevel,Map.copyOf(importance)),s.worker);work=prepared;return;
            }
            if(!prepared.isDone())return;if(value==null){value=prepared.getNow(null);if(value==null){complete(Optional.empty());return;}}
            if(embedding==null){
                ArrayList<DescriptorVectors.WeightedVector> fallback=new ArrayList<>();var index=s.itemIndex;
                if(index.isPopulated())for(var item:value.items.entrySet()){Vec384f v=index.getVec(item.getKey());if(v!=null)fallback.add(new DescriptorVectors.WeightedVector(v,item.getValue()));}
                Vec384f backup=fallback.isEmpty()?null:DescriptorVectors.compose(fallback);
                if(!EmbeddingHelper.isReady()&&backup==null){s.status="Waiting for real embedding/index readiness";return;}
                embedding=EmbeddingHelper.isReady()?EmbeddingHelper.composeDescriptors(value.descriptors).handle((v,e)->{if(e==null)return v;if(backup!=null)return backup.clone();throw new CompletionException(e);}):CompletableFuture.completedFuture(backup);work=embedding;return;
            }
            if(!embedding.isDone())return;
            if(resume==null){try{pause();}catch(IllegalStateException busy){return;}}
            Vec384f vector=embedding.getNow(null);Landmark old=value.prior;
            // Terrain changes move low-importance semantics strongly, high-importance ones slowly.
            if(old!=null){double inertia=SpiritActivityService.importance(s.server,s.store.metadata(old.id()).orElseThrow());vector=DescriptorVectors.compose(List.of(new DescriptorVectors.WeightedVector(old.baseEmbedding().vector(),1),new DescriptorVectors.WeightedVector(vector,0.05+0.5*(1-inertia))));}
            long revision=old==null?0:old.revision()+1;
            published=new Landmark(value.id,dimension,ALGORITHM,value.kind,value.biome,value.anchor,OwnershipGeometry.bounds(value.anchor,value.geometry),LandmarkProfiles.wrap(vector),old==null?.08:old.baseImportance(),old==null?new ActivityMetadata(0,time):old.activity(),old==null?new Ownership(List.of()):old.ownership(),value.geometry,revision,"native 3D source observation; generated-only IO");
            if(old!=null)history=SpiritActivityService.prepareSourceUpdate(s.server,old,published);
            mutation=s.store.stagePut(published,old==null?-1:old.revision());s.status="Source geometry publication";
        }
    }
    static Prepared prepare(Region region,List<Landmark> parents,BlockPos position,String preferred,boolean activity,long tick,int seaLevel,Map<String,Double> importance){
        BlockPoint point=new BlockPoint(position.getX(),position.getY(),position.getZ());Map<BlockPoint,Cell> observed=new HashMap<>();region.cells.forEach(c->observed.put(c.position,c));Cell seed=observed.get(point);if(seed==null)return null;
        Map<String,Map<BlockPoint,OwnershipGeometry.Material>> masks=new HashMap<>();Map<BlockPoint,String> owners=new HashMap<>();
        for(var parent:parents){var mask=OwnershipGeometry.expand(parent.geometry());masks.put(parent.id(),mask);for(var p:mask.keySet())if(owners.put(p,parent.id())!=null)throw new IllegalStateException("conflicting source ownership");}
        Landmark prior=parents.stream().filter(p->p.id().equals(preferred!=null?preferred:owners.get(point))).findFirst().orElse(null);
        Map<BlockPoint,Long> surface=new HashMap<>();for(var c:region.cells)if(!c.air()){
            Cell a=observed.get(new BlockPoint(c.position.x(),c.position.y()+1,c.position.z())),b=observed.get(new BlockPoint(c.position.x(),c.position.y()+2,c.position.z()));
            if(a!=null&&a.air()&&(a.sky||b!=null&&b.air()&&b.sky))surface.merge(new BlockPoint(c.position.x(),0,c.position.z()),c.position.y(),Math::max);
        }
        long low=surface.values().stream().mapToLong(Long::longValue).min().orElse(point.y()),high=surface.values().stream().mapToLong(Long::longValue).max().orElse(point.y());
        Landmark.Kind kind=prior!=null?prior.kind():seed.air()&&!seed.sky?Landmark.Kind.CAVE:high>seaLevel+32&&high-low>=4?Landmark.Kind.MOUNTAIN:Landmark.Kind.BIOME;
        // Initial connected air is cut at exact biome keys and sky escape is never an enclosure.
        Set<BlockPoint> allowed=new HashSet<>();for(var c:region.cells)if((activity || c.biome.equals(seed.biome)) && (kind==Landmark.Kind.CAVE?c.air():c.sky||c.position.equals(point)||surface.containsKey(new BlockPoint(c.position.x(),0,c.position.z()))&&c.position.y()>=surface.get(new BlockPoint(c.position.x(),0,c.position.z()))-4) && (!owners.containsKey(c.position)||prior!=null&&owners.get(c.position).equals(prior.id())))allowed.add(c.position);
        if(!allowed.contains(point)&&prior==null)return null;
        Set<BlockPoint> desired=new HashSet<>();ArrayDeque<BlockPoint> queue=new ArrayDeque<>();if(allowed.contains(point)){desired.add(point);queue.add(point);}
        while(!queue.isEmpty()){var p=queue.removeFirst();for(var d:OwnershipGeometry.DIRECTIONS){var n=OwnershipGeometry.next(p,d);if(allowed.contains(n)&&desired.add(n))queue.add(n);}}
        if(kind==Landmark.Kind.CAVE && desired.stream().map(observed::get).anyMatch(c->c.sky)){
            kind=high>seaLevel+32&&high-low>=4?Landmark.Kind.MOUNTAIN:Landmark.Kind.BIOME; // retain the actual open-air mask, never label it an enclosed cave
        }
        if(prior==null)for(var p:parents.stream().sorted(Comparator.comparing(Landmark::id)).toList())if((activity || p.biome().equals(seed.biome)&&p.kind()==kind)&&OwnershipGeometry.adjacent(desired,kind==Landmark.Kind.CAVE?airMask(masks.get(p.id())):masks.get(p.id()).keySet())){prior=p;break;}
        if(prior!=null){String winner=prior.id();desired.removeIf(p->owners.containsKey(p)&&!owners.get(p).equals(winner));}
        {Set<BlockPoint> walls=new HashSet<>();for(var p:desired)if(observed.get(p).air())for(var d:OwnershipGeometry.DIRECTIONS){var n=OwnershipGeometry.next(p,d);Cell c=observed.get(n);if(c!=null&&!c.air()&&(activity||c.biome.equals(seed.biome))&&(!owners.containsKey(n)||prior!=null&&owners.get(n).equals(prior.id())))walls.add(n);}desired.addAll(walls);}
        Map<BlockPoint,OwnershipGeometry.Material> result=prior==null?new HashMap<>():new HashMap<>(masks.get(prior.id()));
        if(prior!=null)for(var c:region.cells)if(result.containsKey(c.position))result.put(c.position,new OwnershipGeometry.Material(c.material,c.air()));
        for(var p:desired){Cell c=observed.get(p);if(c!=null)result.put(p,new OwnershipGeometry.Material(c.material,c.air()));}
        if(result.isEmpty() || OwnershipGeometry.components(result.keySet()).size()!=1)return null; // no pretend disconnected aggregate
        BlockPoint anchor=prior==null?point:prior.anchor();String biome=prior==null?seed.biome:prior.biome();String id=prior==null?LandmarkIds.seed(region.dimension,ALGORITHM,kind,biome,anchor):prior.id();long revision=prior==null?0:prior.revision()+1;
        List<FrontierFace> frontiers=new ArrayList<>();for(int[] d:OwnershipGeometry.DIRECTIONS){var direction=d[0]<0?FrontierFace.Direction.WEST:d[0]>0?FrontierFace.Direction.EAST:d[1]<0?FrontierFace.Direction.DOWN:d[1]>0?FrontierFace.Direction.UP:d[2]<0?FrontierFace.Direction.NORTH:FrontierFace.Direction.SOUTH;
            for(var p:desired){if(kind==Landmark.Kind.CAVE&&!result.get(p).air())continue;var n=OwnershipGeometry.next(p,d);if(!observed.containsKey(n)&&!result.containsKey(n)){frontiers.add(new FrontierFace(region.dimension,Bounds.cube(n.x(),n.y(),n.z(),1),direction,revision,"source-coordinate retry"));break;}}}
        if(prior!=null)for(var face:prior.geometry().frontiers())if(!observed.containsKey(new BlockPoint(face.missingBounds().minX(),face.missingBounds().minY(),face.missingBounds().minZ())))frontiers.add(face);
        frontiers=frontiers.stream().distinct().limit(LandmarkNbt.MAX_FRONTIERS).toList();
        double score=prior==null?.08:importance.getOrDefault(prior.id(),.08);int detail=score>=.65?1:score>=.3?2:4;
        SourceGeometry geometry=OwnershipGeometry.geometry(region.dimension,id,revision,result,frontiers,detail,point);
        Map<String,Integer> blocks=new HashMap<>();for(var v:result.values())if(!v.air())blocks.merge(v.state().blockId(),1,Integer::sum);
        List<DescriptorVectors.WeightedDescriptor> descriptors=new ArrayList<>();descriptors.add(new DescriptorVectors.WeightedDescriptor(CanonicalDescriptors.region(region.dimension,biome),2));descriptors.add(new DescriptorVectors.WeightedDescriptor("source landmark "+kind.name().toLowerCase(Locale.ROOT)+(kind==Landmark.Kind.CAVE?" connected underground air and rock walls, unknown frontiers":kind==Landmark.Kind.MOUNTAIN?" elevated mountain structure, slopes and surface":" contiguous surface terrain and biome"),2));
        double paletteMass=blocks.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(12).mapToDouble(e->Math.sqrt(e.getValue())).sum();
        Map<String,Double> items=new LinkedHashMap<>();blocks.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(12).forEach(e->{double weight=5*Math.sqrt(e.getValue())/paletteMass;items.put(e.getKey(),weight);descriptors.add(new DescriptorVectors.WeightedDescriptor("source material item "+e.getKey(),weight));});
        String adjoining=null;if(prior!=null&&(activity||kind==Landmark.Kind.CAVE))for(var other:parents.stream().sorted(Comparator.comparing(Landmark::id)).toList())if(!other.id().equals(prior.id())&&(activity||other.kind()==Landmark.Kind.CAVE&&other.biome().equals(biome))&&OwnershipGeometry.adjacent(activity?result.keySet():airMask(result),activity?masks.get(other.id()).keySet():airMask(masks.get(other.id())))){adjoining=other.id();break;}
        return new Prepared(prior,id,biome,kind,anchor,geometry,List.copyOf(descriptors),Map.copyOf(items),adjoining);
    }
    private static Set<BlockPoint> airMask(Map<BlockPoint,OwnershipGeometry.Material> mask){Set<BlockPoint> result=new HashSet<>();mask.forEach((p,m)->{if(m.air())result.add(p);});return result;}
    private record Exchange(Landmark receiver,Landmark donor,SourceGeometry conserved,boolean merge){}
    private static final class Transfer extends Operation<Boolean> {
        final String receiverId,donorId;boolean merge;final boolean allowSteal;final List<Landmark> parents=new ArrayList<>();final List<GeometryPage> pages=new ArrayList<>();CompletableFuture<Exchange> prepared;Exchange value;
        Transfer(Session s,String receiver,String donor,Bounds cells,boolean merge,boolean allowSteal,CompletableFuture<Boolean> future){super(s,"",cells,future);receiverId=receiver;donorId=donor;this.merge=merge;this.allowSteal=allowSteal;s.store.metadata(receiver).ifPresent(m->{dimension=m.header().dimension();guarded=m.header().bounds();});}
        void advance(){
            if(mutation!=null){mutation.advance(1,512);if(mutation.complete()){if(history!=null){history.commit();history=null;}LandmarkExtractionService.COMMITTED_TOPOLOGY.invoker().committed(s.server,parents.stream().map(Landmark::id).toList(),parents.stream().map(l->s.store.resolve(l.id())).distinct().toList());complete(true);}return;}
            if(hydrated!=null){if(!hydrated.isDone())return;parents.add(hydrated.getNow(null));hydrated=null;return;}
            if(geometryRead!=null){geometryRead.advance(1,512);pages.addAll(geometryRead.drain());if(pages.size()>512)throw new IllegalArgumentException("transfer page budget");if(geometryRead.complete()){if(!geometryRead.isCurrent())throw new IllegalStateException("stale transfer geometry");var meta=geometryRead.metadata();var immutable=List.copyOf(pages);hydrated=CompletableFuture.supplyAsync(()->LandmarkNbt.hydrate(meta,immutable),s.worker);work=hydrated;pages.clear();geometryRead=null;}return;}
            if(parents.size()<2){if(!s.store.geometryReadAvailable())return;String id=parents.isEmpty()?receiverId:donorId;var m=s.store.metadata(id);if(m.isEmpty()||!SourceDimensions.isSource(m.get().header().dimension()) || s.store.resolve(receiverId).equals(s.store.resolve(donorId))){complete(false);return;}dimension=m.get().header().dimension();guarded=guarded==null?m.get().header().bounds():guarded.union(m.get().header().bounds());geometryRead=s.store.beginGeometryRead(m.get().id());return;}
            if(resume==null){try{pause();}catch(IllegalStateException busy){return;}}
            if(prepared==null){Landmark receiver=parents.getFirst(),donor=parents.getLast();if(!receiver.dimension().equals(donor.dimension())){complete(false);return;}
                LandmarkEmbedding a=SpiritActivityService.effectiveEmbedding(s.server,s.store.metadata(receiver.id()).orElseThrow()),b=SpiritActivityService.effectiveEmbedding(s.server,s.store.metadata(donor.id()).orElseThrow());
                if(merge&&a.distanceSquared(b)>.2*.2){
                    if(!allowSteal||SpiritActivityService.importance(s.server,s.store.metadata(receiver.id()).orElseThrow())<=SpiritActivityService.importance(s.server,s.store.metadata(donor.id()).orElseThrow())+.05){complete(false);return;}
                    merge=false; // stronger nearby activity can acquire one real adjacent cell
                }
                if(merge){var proof=new LandmarkRepository.VerifiedConnectivity(parents.stream().map(l->new LandmarkRepository.RevisionRef(l.id(),l.revision())).toList(),"activity convergence; worker-verified physical source adjacency");history=LandmarkExtractionService.prepareTopology(s.server,new LandmarkExtractionService.TopologyChange(LandmarkExtractionService.TopologyKind.MERGE,proof.fragments(),List.of(),proof));}
                prepared=CompletableFuture.supplyAsync(()->exchange(receiver,donor,bounds,merge,allowSteal&&!merge),s.worker);work=prepared;return;
            }
            if(!prepared.isDone())return;value=prepared.getNow(null);if(value==null){if(history!=null){history.cancel();history=null;}complete(false);return;}
            var refs=parents.stream().map(l->new LandmarkRepository.RevisionRef(l.id(),l.revision())).toList();
            if(merge){var proof=new LandmarkRepository.VerifiedConnectivity(refs,"activity convergence; six-neighbour source masks");mutation=s.store.stageSemanticMerge(proof,s.server.getOverworld().getTime(),new ImportancePolicy(1,35040000,.00002,.35),value.conserved);}
            else mutation=s.store.stageTransfer(refs,List.of(value.receiver,value.donor),value.conserved);
        }
    }
    private static Exchange exchange(Landmark receiver,Landmark donor,Bounds range,boolean merge,boolean singleCell){
        Map<BlockPoint,OwnershipGeometry.Material> a=OwnershipGeometry.expand(receiver.geometry()),b=OwnershipGeometry.expand(donor.geometry());
        if(a.keySet().stream().anyMatch(b::containsKey))throw new IllegalStateException("exclusive source ownership violated");
        if(!OwnershipGeometry.adjacent(a.keySet(),b.keySet()))return null;
        if(merge){Map<BlockPoint,OwnershipGeometry.Material> union=new HashMap<>(a);union.putAll(b);String canonical=receiver.id().compareTo(donor.id())<0?receiver.id():donor.id();List<FrontierFace> frontiers=new ArrayList<>(receiver.geometry().frontiers());frontiers.addAll(donor.geometry().frontiers());var geometry=OwnershipGeometry.geometry(receiver.dimension(),canonical,Math.max(receiver.revision(),donor.revision())+1,union,frontiers,1);return new Exchange(receiver,donor,geometry,true);}
        Set<BlockPoint> moved=new HashSet<>();for(var p:b.keySet())if(range.contains(p.x(),p.y(),p.z()))moved.add(p);
        if(singleCell){var cell=moved.stream().filter(p->Arrays.stream(OwnershipGeometry.DIRECTIONS).anyMatch(d->a.containsKey(OwnershipGeometry.next(p,d)))).sorted().findFirst();moved.clear();cell.ifPresent(moved::add);}
        if(moved.isEmpty()||moved.size()==b.size()||!OwnershipGeometry.adjacent(a.keySet(),moved))return null;
        for(var p:moved)a.put(p,b.remove(p));
        if(OwnershipGeometry.components(a.keySet()).size()!=1 || OwnershipGeometry.components(b.keySet()).size()!=1)return null;
        SourceGeometry ga=OwnershipGeometry.geometry(receiver.dimension(),receiver.id(),receiver.revision()+1,a,receiver.geometry().frontiers(),1),gb=OwnershipGeometry.geometry(donor.dimension(),donor.id(),donor.revision()+1,b,donor.geometry().frontiers(),1);
        Landmark ra=replace(receiver,ga),rb=replace(donor,gb);List<GeometryPage> all=new ArrayList<>(ga.pages());all.addAll(gb.pages());return new Exchange(ra,rb,new SourceGeometry(all,List.of()),false);
    }
    private static Landmark replace(Landmark l,SourceGeometry geometry){return new Landmark(l.id(),l.dimension(),l.algorithmVersion(),l.kind(),l.biome(),l.anchor(),OwnershipGeometry.bounds(l.anchor(),geometry),l.baseEmbedding(),l.baseImportance(),l.activity(),l.ownership(),geometry,l.revision()+1,"exclusive contiguous source-cell transfer");}
}
