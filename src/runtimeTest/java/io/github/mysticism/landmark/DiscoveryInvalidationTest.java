package io.github.mysticism.landmark;

import io.github.mysticism.vector.Vec384f;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Real refresh queues, source preparation, paged store/CAS and chunk-area retry state machine.
 * No game bootstrap, model endpoint, dummy production vectors or new test dependencies. */
public final class DiscoveryInvalidationTest {
    private static int checks;
    private static final String DIM="minecraft:overworld";
    private static final BlockPalette.State AIR=new BlockPalette.State("minecraft:air",Map.of());
    private static final BlockPalette.State STONE=new BlockPalette.State("minecraft:stone",Map.of());
    private static final BlockPalette.State DIRT=new BlockPalette.State("minecraft:dirt",Map.of());
    private static LandmarkEmbedding embedding(){
        float[] data=Vec384f.ZERO().data();data[0]=1;
        return new LandmarkEmbedding(new EmbeddingProfile("refresh-test","test-revision","test-tokenizer","none",data.length,EmbeddingProfile.Normalization.NONE,"refresh-test"),new Vec384f(data));
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void stale(Runnable action,String message){
        checks++;try{action.run();}catch(IllegalStateException expected){check(DiscoveryInvalidation.transientStaleness(expected),message+" requeues");return;}
        throw new AssertionError(message+" was not rejected");
    }
    private static SourceLandmarks.Cell cell(long x,long y,long z,BlockPalette.State material,boolean sky){return new SourceLandmarks.Cell(new BlockPoint(x,y,z),material,"minecraft:plains",sky);}
    private static Landmark landmark(Landmark.Kind kind,long x,List<FrontierFace> frontiers){
        var anchor=new BlockPoint(x,0,0);String id=LandmarkIds.seed(DIM,SourceLandmarks.ALGORITHM,kind,"minecraft:plains",anchor);
        Map<BlockPoint,OwnershipGeometry.Material> mask=new HashMap<>();
        mask.put(anchor,new OwnershipGeometry.Material(STONE,false));mask.put(new BlockPoint(x,1,0),new OwnershipGeometry.Material(AIR,true));
        var geometry=OwnershipGeometry.geometry(DIM,id,0,mask,frontiers,1);
        return new Landmark(id,DIM,SourceLandmarks.ALGORITHM,kind,"minecraft:plains",anchor,OwnershipGeometry.bounds(anchor,geometry),embedding(),.5,new ActivityMetadata(.6,42),new Ownership(List.of()),geometry,0,"refresh regression fixture");
    }
    private record Disk(Path directory,PersistentStateManager manager,LandmarkStore store) {
        Disk reopen(){var next=DiscoveryInvalidationTest.manager(directory);return new Disk(directory,next,LandmarkStore.open(next,directory));}
    }
    private static PersistentStateManager manager(Path directory) {
        SharedConstants.createGameVersion();
        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        DataFixer identity=new DataFixer(){
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to){return input;}
            public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(directory.toFile(),identity,lookup);
    }
    private static Disk disk() throws Exception {
        Path directory=Files.createTempDirectory("discovery-invalidation-");var manager=manager(directory);
        return new Disk(directory,manager,LandmarkStore.open(manager,directory));
    }
    private static LandmarkStore store() throws Exception {return disk().store;}
    private static void put(LandmarkStore store,Landmark value,long expected){var mutation=store.stagePut(value,expected);while(!mutation.complete())mutation.advance(1,512);}
    private static Landmark load(LandmarkStore store,String id){
        var read=store.beginGeometryRead(id);List<GeometryPage> pages=new ArrayList<>();
        for(int n=0;!read.complete();n++){check(n<100,"bounded fixture hydration completes");read.advance(1,512);pages.addAll(read.drain());}
        check(read.isCurrent(),"fixture hydrates current committed geometry");return LandmarkNbt.hydrate(read.metadata(),pages);
    }

    private static void hintsAndOverflow(){
        var hints=new DiscoveryInvalidation.HintQueue(2);
        check(hints.poll()==null,"empty hint drain is safe");
        var a=new DiscoveryInvalidation.Hint(DIM,0,0,0);var b=new DiscoveryInvalidation.Hint(DIM,1,0,0);var c=new DiscoveryInvalidation.Hint(DIM,2,0,0);
        check(hints.offer(a)==null&&hints.offer(b)==null,"normal hints do not spill");
        check(hints.offer(a)==null&&hints.size()==2,"duplicates do not starve old work");
        check(hints.offer(c).equals(a),"overflow returns old work to the area retry, not silent eviction");
        check(hints.poll().equals(b)&&hints.poll().equals(c),"bounded FIFO survives overflow");
        hints.offer(a);hints.offer(new DiscoveryInvalidation.Hint("minecraft:the_nether",0,0,0));hints.removeIf(h->h.dimension().equals(DIM));
        check(hints.size()==1&&hints.poll().dimension().equals("minecraft:the_nether"),"unload is dimension scoped");
        check(!DiscoveryInvalidation.transientStaleness(new IllegalArgumentException("source parent page budget")),"deterministic budget errors do not spin");
        check(DiscoveryInvalidation.transientStaleness(new CompletionException(new IllegalStateException("stale source geometry"))),"async stale snapshots requeue");
    }
    private static void retryOverflowAndCoverage(){
        var retries=new DiscoveryInvalidation.RetryQueue<SourceLandmarks.FrontierRetry>(2);
        var first=new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(-10,0,0,2),true);
        retries.offer(first);retries.offer(new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(-9,0,0,2),false));
        check(retries.size()==1&&first.bounds.maxX()==-7,"intersecting unstarted chunk retries coalesce");
        first.started=true;first.cursor="consumed-page";first.end=true;
        retries.offer(new SourceLandmarks.FrontierRetry("minecraft:the_nether",Bounds.cube(0,0,0,1),false));
        retries.offer(new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(30,0,0,1),true));
        check(retries.size()==2&&retries.peek()==first,"full retry queue retains old and new same-dimension work");
        check(first.bounds.contains(30,0,0)&&first.bounds.contains(-10,0,0)&&!first.started&&first.cursor==null&&!first.end,"overflow restarts previously consumed catalog cursor");
        retries.offer(new SourceLandmarks.FrontierRetry("minecraft:the_end",Bounds.cube(0,0,0,1),false));
        check(retries.size()==3,"distinct loaded dimensions cannot evict each other's refresh");
        retries.clear();check(retries.peek()==null&&retries.poll()==null,"empty retry drain is safe");
        // Negative coordinates, non-multiple extents, singletons and half-open edge coverage.
        for(var b:List.of(new Bounds(-37,-15,-19,42,17,33),Bounds.cube(100,3,-200,1),new Bounds(0,0,0,26,1,1))){
            var sweep=new DiscoveryInvalidation.Sweep(b);List<BlockPoint> points=new ArrayList<>();BlockPoint p;
            while((p=sweep.poll())!=null)points.add(p);
            for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++){
                long px=x,py=y,pz=z;
                check(points.stream().anyMatch(q->Math.abs(px-q.x())<=12&&Math.abs(py-q.y())<=12&&Math.abs(pz-q.z())<=12),"overflow sweep leaves no missing observation windows");
            }
            check(sweep.poll()==null,"completed sweep stays complete");
        }
    }
    private static void preparationRefresh(){
        for(var kind:Landmark.Kind.values()){
            var face=new FrontierFace(DIM,Bounds.cube(1,1,0,1),FrontierFace.Direction.EAST,0,"disk-unknown");
            var prior=landmark(kind,0,List.of(face));
            var rows=List.of(cell(0,0,0,DIRT,false),cell(0,1,0,AIR,kind!=Landmark.Kind.CAVE),cell(1,1,0,AIR,kind!=Landmark.Kind.CAVE));
            var result=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,new Bounds(-1,-1,-1,3,3,2),rows,true),List.of(prior),new BlockPos(0,1,0),null,false,50,63,Map.of());
            check(result!=null&&result.prior().id().equals(prior.id())&&result.anchor().equals(prior.anchor()),kind+" refresh retains native source identity/anchor");
            check(result.geometry().materialAt(0,0,0).orElseThrow().material().equals(DIRT),kind+" changed source material actually replaces stale geometry");
            check(result.geometry().materialAt(1,1,0).isPresent(),kind+" grows into newly generated frontier");
            check(result.geometry().frontiers().stream().noneMatch(f->f.missingBounds().equals(face.missingBounds())),kind+" absorbed frontier is retired");
            var absent=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,Bounds.cube(0,0,0,3),List.of(cell(0,1,0,AIR,kind!=Landmark.Kind.CAVE)),false),List.of(prior),new BlockPos(0,1,0),null,false,50,63,Map.of());
            check(absent.geometry().materialAt(0,0,0).orElseThrow().material().equals(STONE),kind+" unavailable cells cannot overwrite saved terrain with air");
        }
        var rows=List.of(cell(3,0,0,STONE,true));var region=new SourceLandmarks.Region(DIM,Bounds.cube(0,0,0,8),rows,false);
        check(SourceLandmarks.prepare(region,List.of(),BlockPos.ORIGIN,null,false,0,63,Map.of())==null,"exact requests fail closed at unknown points");
        var refreshed=SourceLandmarks.prepare(region,List.of(),BlockPos.ORIGIN,null,false,0,63,Map.of(),true);
        check(refreshed!=null&&refreshed.anchor().equals(new BlockPoint(3,0,0))&&refreshed.geometry().sample(0,0,0)==null,"overflow refresh chooses real generated evidence, never invented centre material");
    }
    private static void frontierProof(){
        var first=new BlockPoint(2,0,0);var last=new BlockPoint(3,0,0);
        var face=new FrontierFace(DIM,new Bounds(2,0,0,4,1,1),FrontierFace.Direction.EAST,0,"unknown");
        var observed=Map.of(first,cell(2,0,0,AIR,true),last,cell(3,0,0,AIR,true));
        var retained=DiscoveryInvalidation.retainFrontiers(List.of(face),observed,Map.of(first,Boolean.TRUE),4);
        check(retained.size()==1&&retained.getFirst().sourceRevision()==4&&retained.getFirst().cursor().equals(DiscoveryInvalidation.REFRESH_FRONTIER),"observed but unabsorbed slab survives with current generation stamp");
        check(DiscoveryInvalidation.retainFrontiers(List.of(face),observed,Map.of(first,true,last,true),5).isEmpty(),"whole owned slab, not just its corner, retires frontier");
        check(DiscoveryInvalidation.retainFrontiers(List.of(face),Map.of(),Map.of(),6).getFirst().cursor().equals("unknown"),"unavailable frontier remains unknown");
    }
    private static void storeStalenessAndRetries() throws Exception {
        var store=store();List<Landmark> landmarks=new ArrayList<>();
        int index=0;
        for(var kind:Landmark.Kind.values()){
            long x=index++*16;
            var slab=new FrontierFace(DIM,new Bounds(x+2,0,0,x+5,2,1),FrontierFace.Direction.EAST,0,"generated-later");
            var value=landmark(kind,x,List.of(slab));put(store,value,-1);landmarks.add(value);
        }
        long sourceRevision=store.ownershipRevision();
        DiscoveryInvalidation.requireCurrentParents(store,sourceRevision,landmarks);
        var updated=landmarks.getFirst();
        var prepared=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,Bounds.cube(0,0,0,8),List.of(cell(0,0,0,DIRT,false),cell(0,1,0,AIR,true)),false),landmarks,new BlockPos(0,1,0),null,false,50,63,Map.of());
        var claims=new Ownership(List.of(new Ownership.Claim(UUID.fromString("00000000-0000-0000-0000-000000000007"),7)));
        var cas=store.stageActivity(new LandmarkRepository.RevisionRef(updated.id(),0),new ActivityMetadata(.9,99),claims);while(!cas.complete())cas.advance(1);
        check(store.ownershipRevision()==sourceRevision,"activity-only CAS does not change geometry stamp");
        DiscoveryInvalidation.requireCurrentParents(store,sourceRevision,landmarks);
        var fresh=store.metadata(updated.id()).orElseThrow().header();
        check(fresh.revision()==1&&fresh.activity().equals(new ActivityMetadata(.9,99)),"activity-only CAS supplies current publication revision/clock without starving geometry refresh");
        var refreshed=SourceLandmarks.publication(prepared,DIM,updated.baseEmbedding(),fresh,50);
        put(store,refreshed,fresh.revision());
        var committed=store.metadata(updated.id()).orElseThrow().header();
        check(committed.revision()==2&&committed.activity().equals(fresh.activity())&&committed.ownership().equals(claims),"real refreshed publication preserves newer activity/claims and uses fresh CAS revision");
        check(refreshed.geometry().materialAt(0,0,0).orElseThrow().material().equals(DIRT),"rebased publication installs changed source geometry, not only a status/metadata update");
        put(store,landmark(Landmark.Kind.BIOME,96,List.of()),-1);
        stale(()->DiscoveryInvalidation.requireCurrentParents(store,sourceRevision,List.of()),"new source ownership invalidates even seed creation from an empty paged scan");
        // Exercise the exact production retry using paged metadata AND stored geometry.
        var hints=new DiscoveryInvalidation.HintQueue(128);Set<BlockPos> emitted=new HashSet<>();
        var retry=new SourceLandmarks.FrontierRetry(DIM,new Bounds(-1,-1,-1,64,4,2),false);
        int steps=0;
        while(!retry.advance(store,hints,p->emitted.add(p.toImmutable()))){if(++steps>300)throw new AssertionError("retry never completes");}
        for(var value:landmarks){long x=value.anchor().x();check(emitted.stream().anyMatch(p->p.getX()==x),value.kind()+" known terrain page is refreshed on chunk generation");check(emitted.contains(new BlockPos((int)x+2,0,0)),value.kind()+" frontier resumes on chunk load, not caves only");}
        var overlap=new SourceLandmarks.FrontierRetry(DIM,new Bounds(3,0,0,4,1,1),false);Set<BlockPos> intersected=new HashSet<>();
        steps=0;while(!overlap.advance(store,hints,intersected::add)){if(++steps>100)throw new AssertionError("slab retry never completes");}
        check(intersected.contains(new BlockPos(3,0,0)),"slab intersection retries even when its minimum corner is outside loaded chunk area");
        var overflow=new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(500,70,500,1),true);Set<BlockPos> unowned=new HashSet<>();
        steps=0;while(!overflow.advance(store,hints,unowned::add)){if(++steps>100)throw new AssertionError("overflow never completes");}
        check(unowned.contains(new BlockPos(500,70,500)),"spilled new terrain still discovers where the catalog has no landmark");
        var full=new DiscoveryInvalidation.HintQueue(8);for(int n=0;n<8;n++)full.offer(new DiscoveryInvalidation.Hint(DIM,n,0,0));
        var waiting=new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(0,0,0,8),false);
        check(!waiting.advance(store,full,p->{throw new AssertionError("retry emitted into full hints");})&&!waiting.started,"refresh scan applies backpressure instead of recursively overflowing");
        var restart=new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(0,0,0,8),false);
        steps=0;while(restart.read==null){restart.advance(store,hints,p->{});if(++steps>20)throw new AssertionError("no retry geometry read");}
        check(!store.geometryReadAvailable(),"retry holds normal bounded geometry read");
        check(!restart.advance(store,full,p->{throw new AssertionError("backpressure emitted a hint");})&&store.geometryReadAvailable(),"full hints cannot deadlock an Ensure behind retry's held geometry lane");
        restart.advance(store,hints,p->{});
        check(!store.geometryReadAvailable(),"retry reacquires saved page cursor when backpressure drains");
        restart.extend(new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(48,0,0,8),true));
        check(store.geometryReadAvailable()&&restart.cursor==null&&!restart.started&&restart.discover,"overflow cancels old read before restarting catalog/geometry scan");
    }
    private static final class Observation extends SourceLandmarks.Operation<Boolean> {
        final boolean background;
        Observation(){this(false);}
        Observation(boolean background){super(null,DIM,Bounds.cube(0,0,0,25),new CompletableFuture<>());this.background=background;}
        void advance(){}
        BlockPos refreshPoint(){return new BlockPos(12,12,12);}
        Bounds refreshArea(){return background?bounds:null;}
    }
    private static void cancelledGenerationAndEdit() throws Exception {
        var store=store();
        // Stage one immutable geometry page but do not publish the catalog. Generation/edit
        // cancels actual PendingMutation, completes the waiting caller, and retains discovery.
        for(String reason:List.of("source edit invalidated snapshot","source chunk loaded during observation","source chunk unloaded during observation")){
            var observation=new Observation();
            var value=landmark(Landmark.Kind.BIOME,0,List.of());
            observation.mutation=store.stagePut(value,-1);observation.mutation.advance(1);
            observation.work=new CompletableFuture<>();
            Set<BlockPos> resumed=new HashSet<>();
            SourceLandmarks.invalidateObservation(observation,"minecraft:the_nether",Bounds.cube(0,0,0,1),reason,(p,area)->resumed.add(p));
            SourceLandmarks.invalidateObservation(observation,DIM,Bounds.cube(25,0,0,1),reason,(p,area)->resumed.add(p));
            check(!observation.done&&resumed.isEmpty(),"other dimension and touching half-open bounds cannot invalidate a snapshot");
            SourceLandmarks.invalidateObservation(observation,DIM,Bounds.cube(24,0,0,1),reason,(p,area)->{check(area==null,"exact caller retains its point retry");resumed.add(p);});
            check(observation.done&&observation.future.isCompletedExceptionally()&&observation.work.isCancelled(),reason+" cancels async snapshot and caller");
            check(store.metadata(value.id()).isEmpty()&&!observation.mutation.complete(),reason+" cannot publish partly staged stale terrain");
            check(resumed.equals(Set.of(new BlockPos(12,12,12))),reason+" resumes original observation point, not only event location");
            SourceLandmarks.invalidateObservation(observation,DIM,Bounds.cube(1,0,0,1),reason,(p,area)->{throw new AssertionError("cancelled observation requeued twice");});
        }
        // A retry whose queued header disappeared must finish, not loop on a retired seed.
        var value=landmark(Landmark.Kind.BIOME,0,List.of());put(store,value,-1);
        var hints=new DiscoveryInvalidation.HintQueue(128);
        var retry=new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(0,0,0,8),false);
        retry.advance(store,hints,p->{});
        var deletion=store.stageDelete(new LandmarkRepository.RevisionRef(value.id(),0));while(!deletion.complete())deletion.advance(1);
        int steps=0;while(!retry.advance(store,hints,p->{})){if(++steps>30)throw new AssertionError("deleted header pinned retry");}
        check(store.geometryReadAvailable(),"retired queued header leaves geometry lane available");
    }
    private static void cancelledChangedGeometry() throws Exception {
        for(String reason:List.of("source edit invalidated snapshot","source chunk loaded during observation","source chunk unloaded during observation"))
        for(boolean update:List.of(false,true))for(boolean restart:List.of(false,true)) {
            var disk=disk();var store=disk.store;var original=landmark(Landmark.Kind.BIOME,0,List.of());
            if(update)put(store,original,-1);
            var prior=update?List.of(original):List.<Landmark>of();
            var firstRows=List.of(cell(0,0,0,update?DIRT:STONE,false),cell(0,1,0,AIR,true));
            var secondRows=List.of(cell(0,0,0,update?STONE:DIRT,false),cell(0,1,0,AIR,true));
            var first=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,Bounds.cube(0,0,0,8),firstRows,false),prior,new BlockPos(0,1,0),null,false,50,63,Map.of(),true);
            var a=SourceLandmarks.publication(first,DIM,embedding(),update?original:null,50);
            var observation=new Observation(true);observation.mutation=store.stagePut(a,update?0:-1);
            check(observation.mutation.advance(1)==1&&!observation.mutation.complete(),"cancel fixture stages a real geometry page before catalog publication");
            List<Bounds> retained=new ArrayList<>();
            SourceLandmarks.invalidateObservation(observation,DIM,Bounds.cube(1,1,0,1),reason,(p,area)->retained.add(area));
            check(retained.equals(List.of(observation.bounds)),"cancelled background retry keeps its whole discovery window, not a spillable centre alone");
            check(observation.done&&store.metadata(a.id()).isPresent()==update,"cancel retains only previously committed ownership");
            disk.manager.save();if(restart){disk=disk.reopen();store=disk.store;}
            var second=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,Bounds.cube(0,0,0,8),secondRows,false),prior,new BlockPos(0,1,0),null,false,51,63,Map.of(),true);
            var b=SourceLandmarks.publication(second,DIM,embedding(),update?original:null,51);
            check(a.id().equals(b.id())&&a.revision()==b.revision()&&a.anchor().equals(b.anchor()),"changed retry preserves landmark seed/anchor and unreserved CAS revision");
            var pa=a.geometry().pages().getFirst();var pb=b.geometry().pages().getFirst();
            check(pa.revision()==pb.revision()&&!pa.id().equals(pb.id()),"changed terrain at same revision cannot reuse cancelled immutable page identity");
            put(store,b,update?0:-1);disk.manager.save();var reopened=disk.reopen().store;
            check(load(reopened,b.id()).geometry().materialAt(0,0,0).orElseThrow().material().equals(update?STONE:DIRT),"changed retry publishes and survives cold compressed reload: "+reason);
            var mask=new CompletableFuture<SourceOwnership.Region>();var request=new SourceOwnership.Request(reopened,DIM,new Bounds(0,0,0,2,2,1),mask);
            for(int n=0;!request.done;n++){check(n<100,"cancel retry ownership query makes progress");request.advance();}
            check(mask.join().ownerAt(new BlockPos(0,1,0)).orElseThrow().equals(b.id()),"cancelled attempt cannot prevent exact AIR ownership after refresh");
        }
        // Hashing is deterministic across insertion order and revisions, but never crosses
        // source-fragment identity or material changes. No random staging nonce/new save schema.
        Map<BlockPoint,OwnershipGeometry.Material> forward=new LinkedHashMap<>();forward.put(new BlockPoint(0,0,0),new OwnershipGeometry.Material(STONE,false));forward.put(new BlockPoint(1,0,0),new OwnershipGeometry.Material(DIRT,false));
        Map<BlockPoint,OwnershipGeometry.Material> reverse=new LinkedHashMap<>();reverse.put(new BlockPoint(1,0,0),forward.get(new BlockPoint(1,0,0)));reverse.put(new BlockPoint(0,0,0),forward.get(new BlockPoint(0,0,0)));
        String id=landmark(Landmark.Kind.BIOME,0,List.of()).id();
        var a=OwnershipGeometry.geometry(DIM,id,0,forward,List.of(),1).pages().getFirst();var b=OwnershipGeometry.geometry(DIM,id,7,reverse,List.of(),1).pages().getFirst();
        check(a.id().equals(b.id()),"geometry content identity does not depend on CAS revision or map insertion order");
        check(!a.id().equals(OwnershipGeometry.geometry(DIM,landmark(Landmark.Kind.BIOME,16,List.of()).id(),0,forward,List.of(),1).pages().getFirst().id()),"geometry content identity retains source fragment ownership namespace");
    }
    private static void coalescedCavesOwnTerrain() throws Exception {
        var disk=disk();var store=disk.store;var hints=new DiscoveryInvalidation.HintQueue(1);
        var retries=new DiscoveryInvalidation.RetryQueue<SourceLandmarks.FrontierRetry>(1);
        // Use the same production spill -> area -> union path, dropping exact point state.
        hints.offer(new DiscoveryInvalidation.Hint(DIM,-8,0,0));
        for(long x:new long[]{8,100}) {
            var spill=hints.offer(new DiscoveryInvalidation.Hint(DIM,x,0,0));
            retries.offer(new SourceLandmarks.FrontierRetry(spill.dimension(),Bounds.cube(spill.x(),spill.y(),spill.z(),1),true));
        }
        check(retries.size()==1,"two spilled cave seeds coalesce into one constant-state retry");
        var retry=retries.poll();var observations=new ArrayDeque<BlockPos>();var empty=new DiscoveryInvalidation.HintQueue(128);
        for(int n=0;!retry.advance(store,empty,observations::add);n++)check(n<100,"coalesced overflow scan completes");
        check(observations.size()==1&&observations.peek().getX()==4,"coalesced window centre is stone, NOT either underground cave hint");
        List<SourceLandmarks.Cell> rows=new ArrayList<>();
        for(long x=-8;x<=16;x++)for(long y=-1;y<=1;y++)for(long z=-1;z<=1;z++)rows.add(cell(x,y,z,(x==-8||x==8)&&y==0&&z==0?AIR:STONE,false));
        int steps=0;boolean spillAgain=true;
        while(!observations.isEmpty()) {
            check(++steps<100,"retry discovery finishes rather than repeatedly publishing BIOME-only observations");
            var point=observations.removeFirst();var bounds=new Bounds(point.getX()-12L,point.getY()-12L,point.getZ()-12L,point.getX()+13L,point.getY()+13L,point.getZ()+13L);
            List<Landmark> parents=new ArrayList<>();for(String id:store.ids())parents.add(load(store,id));
            var local=rows.stream().filter(c->bounds.contains(c.position().x(),c.position().y(),c.position().z())).toList();
            if(local.isEmpty())continue;
            var prepared=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,bounds,local,false),parents,point,null,false,steps,63,Map.of(),true);
            check(prepared!=null,"generated stone/cave evidence prepares real terrain");
            var old=prepared.prior()==null?null:store.metadata(prepared.prior().id()).orElseThrow().header();
            var published=SourceLandmarks.publication(prepared,DIM,embedding(),old,steps);put(store,published,old==null?-1:old.revision());
            if(prepared.revisit()) {
                // Same production post-commit AREA retry. Recoalesce it once with distant
                // work: keeping just the original centre here would drop the left cave.
                var followups=new DiscoveryInvalidation.RetryQueue<SourceLandmarks.FrontierRetry>(1);
                followups.offer(new SourceLandmarks.FrontierRetry(DIM,bounds,true));
                if(spillAgain){followups.offer(new SourceLandmarks.FrontierRetry(DIM,Bounds.cube(40,0,0,1),true));spillAgain=false;}
                var followup=followups.poll();
                for(int n=0;!followup.advance(store,empty,observations::add);n++)check(n<500,"follow-up original observation area survives repeat coalescing");
            }
        }
        disk.manager.save();store=disk.reopen().store;
        var future=new CompletableFuture<SourceOwnership.Region>();var request=new SourceOwnership.Request(store,DIM,new Bounds(-8,-1,-1,17,2,2),future);
        for(int n=0;!request.done;n++){check(n<300,"coalesced ownership query completes");request.advance();}
        var ownership=future.join();Set<String> caves=new HashSet<>();
        for(int x:new int[]{-8,8}) {
            String owner=ownership.ownerAt(new BlockPos(x,0,0)).orElseThrow();caves.add(owner);
            check(store.metadata(owner).orElseThrow().header().kind()==Landmark.Kind.CAVE,"spilled underground AIR obtains actual CAVE ownership, not mere BIOME window coverage");
            check(ownership.ownerAt(new BlockPos(x,1,0)).orElseThrow().equals(owner),"discovered cave owns its real SOLID wall too");
        }
        check(caves.size()==2,"solid separation cannot collapse disconnected cave components into one owner");
        check(ownership.ownerAt(new BlockPos(0,1,1)).isEmpty(),"unowned underground solid/unknown space is never filled by area coverage");
        var exact=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,Bounds.cube(0,0,0,8),List.of(cell(0,0,0,STONE,false),cell(1,0,0,AIR,false)),false),List.of(),BlockPos.ORIGIN,null,false,0,63,Map.of());
        check(exact.kind()==Landmark.Kind.BIOME&&!exact.revisit(),"exact captured callers do not substitute a skipped cave destination");
    }
    private static Landmark refreshFixture(LandmarkStore store,Map<String,Landmark> live,List<SourceLandmarks.Cell> rows,BlockPos point,long time){
        var bounds=new Bounds(point.getX()-12L,point.getY()-12L,point.getZ()-12L,point.getX()+13L,point.getY()+13L,point.getZ()+13L);
        var local=rows.stream().filter(c->bounds.contains(c.position().x(),c.position().y(),c.position().z())).toList();
        var prepared=SourceLandmarks.prepare(new SourceLandmarks.Region(DIM,bounds,local,false),List.copyOf(live.values()),point,null,false,time,63,Map.of(),true);
        check(prepared!=null,"interleaved Ensure prepares actual generated evidence");
        var old=prepared.prior()==null?null:store.metadata(prepared.prior().id()).orElseThrow().header();
        var published=SourceLandmarks.publication(prepared,DIM,embedding(),old,time);put(store,published,old==null?-1:old.revision());live.put(published.id(),published);return published;
    }
    private static void publicationBackpressureProgress() throws Exception {
        // Production capacity, not a tiny stand-in: one landmark alone has more pages than
        // the entire hint queue. Every pressure drain publishes a real Ensure refresh.
        for(boolean changeTerrain:List.of(false,true)) {
            var disk=disk();var store=disk.store;int pageCount=130,length=pageCount*8;
            var anchor=new BlockPoint(0,1,0);String id=LandmarkIds.seed(DIM,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",anchor);
            Map<BlockPoint,OwnershipGeometry.Material> cells=new HashMap<>();List<SourceLandmarks.Cell> rows=new ArrayList<>();
            for(int x=0;x<length;x++) {
                cells.put(new BlockPoint(x,0,0),new OwnershipGeometry.Material(STONE,false));cells.put(new BlockPoint(x,1,0),new OwnershipGeometry.Material(AIR,true));
                rows.add(cell(x,0,0,STONE,false));rows.add(cell(x,1,0,AIR,true));
            }
            BlockPos cave=new BlockPos(length+4,7,7);rows.add(cell(cave.getX(),7,7,AIR,false));
            for(var d:OwnershipGeometry.DIRECTIONS)rows.add(cell(cave.getX()+d[0],7+d[1],7+d[2],STONE,false));
            var geometry=OwnershipGeometry.geometry(DIM,id,0,cells,List.of(),1);
            var original=new Landmark(id,DIM,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",anchor,OwnershipGeometry.bounds(anchor,geometry),embedding(),.5,new ActivityMetadata(0,0),new Ownership(List.of()),geometry,0,"large retry fixture");
            put(store,original,-1);var captured=store.metadata(id).orElseThrow();Map<String,Landmark> live=new LinkedHashMap<>();live.put(id,original);
            var hints=new DiscoveryInvalidation.HintQueue(128);var bounds=new Bounds(0,0,0,length+16,8,8);
            var retry=new SourceLandmarks.FrontierRetry(DIM,bounds,true);Set<BlockPos> emitted=new HashSet<>();Map<BlockPos,Integer> pageVisits=new HashMap<>();
            java.util.function.Consumer<BlockPos> emit=p->{
                emitted.add(p.toImmutable());if(retry.read!=null)pageVisits.merge(p.toImmutable(),1,Integer::sum);
                check(hints.offer(new DiscoveryInvalidation.Hint(DIM,p.getX(),p.getY(),p.getZ()))==null,"retry never spills while resuming under backpressure");
            };
            int pressure=0,steps=0;boolean midReadPublication=false;
            while(!retry.advance(store,hints,emit)) {
                check(++steps<600,"interleaved Ensure publications cannot starve later pages or discovery sweep");
                if(!midReadPublication&&!pageVisits.isEmpty()) {
                    check(retry.read!=null&&!retry.read.complete(),"mid-read publication precedes completion");
                    refreshFixture(store,live,rows,pageVisits.keySet().iterator().next(),steps);midReadPublication=true;
                    check(!retry.read.isCurrent(),"old hint snapshot correctly remains stale for authoritative consumers");
                }
                if(hints.remaining()<8) {
                    boolean geometryPending=retry.current!=null;
                    int completed=retry.read==null?retry.nextGeometryPage:retry.read.nextPageIndex();
                    check(!retry.advance(store,hints,p->{throw new AssertionError("pressure emitted extra work");}),"pressure yields without completing retry");
                    check(store.geometryReadAvailable(),"pressure releases geometry lane for Ensure hydration");
                    check(retry.nextGeometryPage==completed&&(!geometryPending||completed>0),"pressure saves exact completed-page boundary");
                    BlockPos point=null;
                    for(int n=0;n<32;n++){var h=hints.poll();if(h==null)break;if(point==null)point=new BlockPos((int)h.x(),(int)h.y(),(int)h.z());}
                    if(changeTerrain&&pressure==0) {
                        final int changedX=point.getX();rows.replaceAll(c->c.position().equals(new BlockPoint(changedX,0,0))?cell(changedX,0,0,DIRT,false):c);
                    }
                    refreshFixture(store,live,rows,point,steps);pressure++;
                    var now=store.metadata(id).orElseThrow();
                    check(!now.geometryKeys().equals(captured.geometryKeys()),"Ensure replaces revision-qualified geometry keys during scan");
                    var oldIds=captured.geometryKeys().stream().map(k->k.substring(0,k.lastIndexOf('.'))).toList();
                    var newIds=now.geometryKeys().stream().map(k->k.substring(0,k.lastIndexOf('.'))).toList();
                    check(oldIds.equals(newIds)!=changeTerrain,"fixture distinguishes revision-only changes from material/page-order changes");
                    if(pressure==1){disk.manager.save();disk=disk.reopen();store=disk.store;}
                    // Cold resume must retain the captured list, not reinterpret its page
                    // index against newer sorted keys (including truly changed content).
                    retry.advance(store,hints,emit);
                    if(geometryPending)check(retry.read!=null&&retry.read.nextPageIndex()==completed&&retry.read.metadata().geometryKeys().equals(captured.geometryKeys()),"resume keeps immutable page list and cursor together across publications");
                    else check(retry.read==null&&retry.current==null&&retry.sweep!=null,"publication cannot restart completed geometry while discovery is sweeping");
                }
            }
            check(pressure>=1&&midReadPublication,"fixture interleaves publications both while reading and at backpressure");
            Set<BlockPos> expectedPages=new HashSet<>();for(var page:geometry.pages()) {var b=page.knownCells().getFirst().bounds();expectedPages.add(new BlockPos((int)b.minX(),(int)b.minY(),(int)b.minZ()));}
            check(pageVisits.keySet().equals(expectedPages)&&pageVisits.values().stream().allMatch(n->n==1),"every one of 130 captured pages is visited exactly once despite newer revisions");
            var sweep=new DiscoveryInvalidation.Sweep(bounds);BlockPoint p;
            while((p=sweep.poll())!=null)check(emitted.contains(new BlockPos((int)p.x(),(int)p.y(),(int)p.z())),"finite retry reaches every discovery sweep window after all geometry pages");
            check(emitted.contains(cave),"last sweep reaches undiscovered cave beyond the landmark");
            var discovered=refreshFixture(store,live,rows,cave,steps+1);check(discovered.kind()==Landmark.Kind.CAVE,"reached sweep performs actual cave discovery");
            disk.manager.save();store=disk.reopen().store;
            var ownership=new CompletableFuture<SourceOwnership.Region>();var request=new SourceOwnership.Request(store,DIM,Bounds.cube(cave.getX()-1,6,6,3),ownership);
            for(int n=0;!request.done;n++){check(n<100,"post-sweep ownership read completes");request.advance();}
            check(ownership.join().ownerAt(cave).orElseThrow().equals(discovered.id()),"new tail cave owns real AIR after cold save/reopen");
            check(ownership.join().ownerAt(cave.down()).orElseThrow().equals(discovered.id()),"new tail cave owns its actual SOLID support");
            check(store.geometryReadAvailable(),"completed retry and ownership query release geometry lane");
        }
    }
    public static void main(String[] args) throws Exception {
        hintsAndOverflow();retryOverflowAndCoverage();preparationRefresh();frontierProof();storeStalenessAndRetries();cancelledGenerationAndEdit();cancelledChangedGeometry();coalescedCavesOwnTerrain();publicationBackpressureProgress();
        System.out.println("DiscoveryInvalidationTest: "+checks+" checks passed");
    }
}
