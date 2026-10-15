package io.github.mysticism.landmark;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentStateManager;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Real Session scheduler + Ensure geometry/publication + Read/GeneratedSourceReader.
 * The session holder is allocated without a Minecraft server or model. Persistence uses
 * real Yarn NBT/PersistentStateManager; movement samples generated NBT through the normal
 * detached-reader seam. Test-only synthetic embeddings never reach production inference.
 * Tick counts measure scheduler interference, NOT wall-clock IO/worker/gameplay latency. */
public final class SourceDiscoveryReadLatencyTest {
    private static int checks;
    private static final String DIMENSION="minecraft:overworld";
    private static final int PAGE_COUNT=100;
    private static final Bounds READ_BOUNDS=Bounds.cube(0,0,0,1);
    private static final Class<?> SESSION=type("Session"),ENSURE=type("Ensure"),READ=type("Read"),OPERATION=ENSURE.getSuperclass();
    private static final Method ADVANCE=method(SESSION,"advanceScheduled",boolean.class);
    private static Class<?> type(String name){
        try{return Class.forName(SourceLandmarks.class.getName()+"$"+name);}catch(ClassNotFoundException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static Method method(Class<?> type,String name,Class<?>... parameters){
        try{var method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;}catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static Field field(Class<?> type,String name) throws ReflectiveOperationException {
        var field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void invoke(Method method,Object target,Object... args) throws Exception {
        try{method.invoke(target,args);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception exception)throw exception;
            if(failure.getCause() instanceof Error error)throw error;
            throw failure;
        }
    }
    private static Object ensure(Object session) throws Exception {
        var constructor=ENSURE.getDeclaredConstructor(SESSION,String.class,BlockPos.class,String.class,boolean.class,CompletableFuture.class);
        constructor.setAccessible(true);
        var ensure=constructor.newInstance(session,DIMENSION,BlockPos.ORIGIN,null,false,new CompletableFuture<Optional<LandmarkMetadata>>());
        field(ENSURE,"observed").set(ensure,new SourceLandmarks.Region(DIMENSION,READ_BOUNDS,List.of(),true));
        return ensure;
    }
    private static Object session(LandmarkStore store,SourceSchedule<Object> schedule) throws Exception {
        var allocator=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        var session=allocator.allocateInstance(SESSION);
        field(SESSION,"store").set(session,store);field(SESSION,"schedule").set(session,schedule);
        field(SESSION,"hints").set(session,new LinkedHashSet<>());
        return session;
    }
    private static GeneratedSourceReader.ChunkIo savedChunk(){
        var root=new NbtCompound();root.putString("Status","full");root.put("Heightmaps",new NbtCompound());
        var sections=new NbtList();var section=new NbtCompound();section.putByte("Y",(byte)0);
        var states=new NbtCompound();var palette=new NbtList();var stone=new NbtCompound();stone.putString("Name","minecraft:stone");palette.add(stone);
        states.put("palette",palette);section.put("block_states",states);
        var biomes=new NbtCompound();var biomePalette=new NbtList();biomePalette.add(NbtString.of("minecraft:plains"));biomes.put("palette",biomePalette);section.put("biomes",biomes);
        sections.add(section);root.put("sections",sections);
        return new GeneratedSourceReader.ChunkIo(){
            @Override public net.minecraft.world.chunk.WorldChunk live(ChunkPos pos){return null;}
            @Override public CompletableFuture<?> scan(ChunkPos pos,GeneratedSourceReader.LimitedCollector collector){root.accept(collector);return CompletableFuture.completedFuture(null);}
        };
    }
    private static CompletableFuture<SourceLandmarks.Region> queueRead(Object session,SourceSchedule<Object> schedule) throws Exception {
        var future=new CompletableFuture<SourceLandmarks.Region>();
        var constructor=READ.getDeclaredConstructor(SESSION,String.class,Bounds.class,CompletableFuture.class);constructor.setAccessible(true);
        var read=constructor.newInstance(session,DIMENSION,READ_BOUNDS,future);
        field(OPERATION,"reader").set(read,new GeneratedSourceReader(DIMENSION,READ_BOUNDS,Runnable::run,0,256,63,savedChunk()));
        schedule.read(read);return future;
    }
    private static Landmark landmark(){
        var pages=new ArrayList<GeometryPage>();
        var palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:stone",Map.of())));
        for(int index=0;index<PAGE_COUNT;index++){
            var anchor=new BlockPoint(index,0,0);var bounds=Bounds.cube(index,0,0,1);
            var cells=SparseOctree.<BlockSample>empty(bounds,1,1).with(bounds,new BlockSample(BlockSample.Occupancy.SOLID,0),1);
            pages.add(new GeometryPage(LandmarkIds.geometryPage(DIMENSION,anchor,1),0,bounds,palette,cells));
        }
        var anchor=new BlockPoint(0,0,0);
        var profile=new EmbeddingProfile("test-native","pinned-test-revision","test-tokenizer","none",Vec384f.ZERO().data().length,EmbeddingProfile.Normalization.NONE,"test-source-v2");
        var embedding=new LandmarkEmbedding(profile,Vec384f.ZERO());
        return new Landmark(LandmarkIds.seed(DIMENSION,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",anchor),DIMENSION,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,"minecraft:plains",anchor,new Bounds(0,0,0,PAGE_COUNT,1,1),embedding,.08,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(pages,List.of()),0,"100-page scheduler fixture");
    }
    private static LandmarkStore store() throws Exception {
        var directory=Files.createTempDirectory("source-discovery-read-latency-");
        var identity=new DataFixer(){
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to){return input;}
            @Override public Schema getSchema(int version){throw new UnsupportedOperationException("current-version fixture");}
        };
        return LandmarkStore.open(new PersistentStateManager(directory.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty())),directory);
    }
    private static void validateRead(CompletableFuture<SourceLandmarks.Region> future){
        var region=future.join(); // test-only: caller has already checked isDone()
        check(region.complete()&&region.cells().size()==1,"read completes real generated terrain, not just a status");
        check(region.cells().getFirst().material().blockId().equals("minecraft:stone"),"read preserves the sampled material");
    }
    private static void multiPage(boolean publication) throws Exception {
        var store=store();var landmark=landmark();
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->{
            try{return field(OPERATION,"background").getBoolean(op);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        var session=session(store,schedule);var owner=ensure(session);var later=ensure(session);
        field(OPERATION,"background").setBoolean(owner,publication);
        LandmarkStore.GeometryRead geometry=null;LandmarkStore.PendingMutation mutation=null;int[] releases={0};
        if(publication){
            mutation=store.stagePut(landmark,-1);
            field(OPERATION,"mutation").set(owner,mutation);field(OPERATION,"resume").set(owner,(Runnable)()->releases[0]++);
            field(ENSURE,"published").set(owner,landmark);
            field(ENSURE,"value").set(owner,new SourceLandmarks.Prepared(null,landmark.id(),landmark.biome(),landmark.kind(),landmark.anchor(),landmark.geometry(),List.of(),Map.of(),null));
        }else{
            var initial=store.stagePut(landmark,-1);while(!initial.complete())initial.advance(8);
            geometry=store.beginGeometryRead(landmark.id());field(OPERATION,"geometryRead").set(owner,geometry);
        }
        field(SESSION,"active").set(session,owner);schedule.discover(later);
        long revision=store.ownershipRevision();
        var urgent=new ArrayList<CompletableFuture<SourceLandmarks.Region>>();
        for(String purpose:List.of("walk","near-terrain","target"))urgent.add(queueRead(session,schedule));
        int urgentTicks=0,progress=0,streamReads=0,maxLatency=0;
        try(var worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("discovery-latency-regression").factory())){
            field(SESSION,"worker").set(session,worker);
            while(urgent.stream().anyMatch(future->!future.isDone())&&urgentTicks<12){
                invoke(ADVANCE,session,true);urgentTicks++;
                check(!finished(publication,geometry,mutation),"movement answers before the 100-page discovery finishes");
                check(!store.geometryReadAvailable()||publication,"movement reads never release/replace discovery's geometry read");
            }
            check(urgentTicks<=12&&urgent.stream().allMatch(CompletableFuture::isDone),"walk, near-terrain and target all finish within 12 tick advances, not 100+ ticks");
            urgent.forEach(SourceDiscoveryReadLatencyTest::validateRead);
            // Sustained reads must neither monopolize discovery nor admit another discovery
            // while this owner holds the store/activity resources. Each read takes several
            // ticks in the real reader; quota counts completed read operations, not pages.
            CompletableFuture<SourceLandmarks.Region> read=null;int readStart=0;
            for(int tick=0;tick<2000&&!finished(publication,geometry,mutation);tick++){
                if(read==null){read=queueRead(session,schedule);readStart=tick;}
                int before=publication?mutation.remainingPages():geometry.nextPageIndex();
                invoke(ADVANCE,session,true);
                int after=publication?mutation.remainingPages():geometry.nextPageIndex();
                int advanced=publication?before-after:after-before;
                check(advanced>=0&&advanced<=1,"discovery retains one-page-per-tick budget while interleaving reads");
                progress+=advanced;
                check(field(ENSURE,"prepared").get(later)==null&&field(OPERATION,"geometryRead").get(later)==null,"other discovery cannot pass the suspended resource owner, even from requested lane");
                if(publication&&!mutation.complete()){
                    check(store.metadata(landmark.id()).isEmpty()&&store.ownershipRevision()==revision,"movement interleaving never exposes partially staged publication");
                    check(releases[0]==0,"activity pause stays held until publication finishes");
                }
                if(read.isDone()){
                    validateRead(read);int latency=tick-readStart+1;maxLatency=Math.max(maxLatency,latency);
                    check(latency<=4,"movement read latency bounded by at most one discovery advance (actual "+latency+")");
                    read=null;streamReads++;
                }
            }
            check(finished(publication,geometry,mutation),"resource owner makes progress under sustained movement reads");
            check(progress>=PAGE_COUNT-2&&streamReads>=100,"regression exercises many discovery pages and continuous real movement reads");
            if(publication){
                check(store.metadata(landmark.id()).orElseThrow().geometryKeys().size()==PAGE_COUNT,"all 100 geometry pages publish atomically");
                check(store.ownershipRevision()==revision+1&&releases[0]==1,"final publication increments revision and releases the activity pause once");
                check(((CompletableFuture<?>)field(OPERATION,"future").get(owner)).isDone(),"real Ensure publication completes its result");
            }else{
                check(store.geometryReadAvailable(),"geometry resource released after all pages are decoded");
                @SuppressWarnings("unchecked") var hydrated=(CompletableFuture<Landmark>)field(OPERATION,"hydrated").get(owner);
                check(hydrated.get(5,TimeUnit.SECONDS).geometry().pages().size()==PAGE_COUNT,"real detached parent hydration preserves every page");
            }
            System.out.println("SourceDiscoveryReadLatencyTest: "+(publication?"publication/background":"geometry/requested")+", queued reads="+urgentTicks+" ticks, sustained max="+maxLatency+" ticks, streamed reads="+streamReads+", discovery page advances="+progress);
        }
    }
    private static boolean finished(boolean publication,LandmarkStore.GeometryRead geometry,LandmarkStore.PendingMutation mutation){return publication?mutation.complete():geometry.complete();}
    private static void invalidatedSuspendedOwner(boolean publication) throws Exception {
        var store=store();var landmark=landmark();
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->false);
        var session=session(store,schedule);var owner=ensure(session);var later=ensure(session);int[] releases={0};
        if(publication){
            field(OPERATION,"mutation").set(owner,store.stagePut(landmark,-1));
            field(OPERATION,"resume").set(owner,(Runnable)()->releases[0]++);
        }else{
            var put=store.stagePut(landmark,-1);while(!put.complete())put.advance(8);
            field(OPERATION,"geometryRead").set(owner,store.beginGeometryRead(landmark.id()));
        }
        // Edit intersects the owner's guard, not the read or unrelated waiting discovery.
        field(OPERATION,"guarded").set(later,Bounds.cube(10000,0,0,1));schedule.discover(later);
        field(SESSION,"active").set(session,owner);
        var read=queueRead(session,schedule);invoke(ADVANCE,session,true);
        check(schedule.discoveryPending()==2&&READ.isInstance(field(SESSION,"active").get(session)),"resource owner is suspended while a real Read is active");
        var invalidate=method(SESSION,"invalidate",String.class,BlockPos.class);
        invoke(invalidate,session,DIMENSION,new BlockPos(8,0,0));
        invoke(invalidate,session,DIMENSION,new BlockPos(8,0,0));
        check(schedule.discoveryPending()==1,"source invalidation removes the suspended owner but preserves unrelated discovery");
        check(((CompletableFuture<?>)field(OPERATION,"future").get(owner)).isCompletedExceptionally(),"invalidated owner cannot later publish its snapshot");
        check(store.geometryReadAvailable(),"cancel releases the real exclusive geometry resource");
        check(releases[0]==(publication?1:0),"cancel releases the real operation's activity pause exactly once");
        for(int tick=0;tick<4&&!read.isDone();tick++)invoke(ADVANCE,session,false);
        check(read.isDone(),"unrelated movement read continues after suspended owner invalidation");validateRead(read);
        check(schedule.pick(null,op->true,op->true)==later,"no stale resource-owner reservation remains after invalidation");
        if(publication){
            check(store.metadata(landmark.id()).isEmpty(),"cancel never publishes partially staged topology");
            // A fresh discovery can acquire the mutation slot; cancelled owner held none.
            store.stagePut(landmark,-1).cancel();
        }
    }
    /** Terrain support needs BOTH region samples and exact persisted ownership. Exercise the
     * real Request phase before the real scheduled op, in the same order as Session.tick.
     * A far-page proof also bounds the skipped-page scan, not just a lucky resident first page. */
    private static void ownershipDuringDiscovery(boolean background) throws Exception {
        var store=store();var landmark=landmark();
        var put=store.stagePut(landmark,-1);while(!put.complete())put.advance(8);
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->{
            try{return field(OPERATION,"background").getBoolean(op);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        var session=session(store,schedule);var owner=ensure(session);var later=ensure(session);
        field(OPERATION,"background").setBoolean(owner,background);
        var geometry=store.beginGeometryRead(landmark.id());field(OPERATION,"geometryRead").set(owner,geometry);
        field(SESSION,"active").set(session,owner);schedule.discover(later);
        var bounds=new Bounds(PAGE_COUNT-1,0,0,PAGE_COUNT+1,1,1);
        var future=new CompletableFuture<SourceOwnership.Region>();
        var request=new SourceOwnership.Request(store,DIMENSION,bounds,future);
        CompletableFuture<SourceLandmarks.Region> read=null;int readStart=0,ticks=0,maxLatency=0,streamReads=0;
        try(var worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("ownership-latency-regression").factory())){
            field(SESSION,"worker").set(session,worker);
            for(;ticks<200&&!future.isDone();ticks++){
                if(read==null){read=queueRead(session,schedule);readStart=ticks;}
                request.advance();
                invoke(ADVANCE,session,true);
                check(!geometry.complete()&&!store.geometryReadAvailable(),"ownership progresses before discovery releases its 100-page cursor");
                check(field(ENSURE,"prepared").get(later)==null&&field(OPERATION,"geometryRead").get(later)==null,"ownership progress never admits competing discovery");
                if(read.isDone()){
                    validateRead(read);int latency=ticks-readStart+1;maxLatency=Math.max(maxLatency,latency);
                    check(latency<=4,"ownership work preserves bounded movement-read scheduler latency");
                    read=null;streamReads++;
                }
            }
            check(future.isDone()&&!future.isCompletedExceptionally(),"real ownership proof completes before the 200-tick walk deadline while discovery still holds its cursor (ticks="+ticks+")");
            var result=future.join();
            check(result.owners().equals(Map.of(new BlockPos(PAGE_COUNT-1,0,0),landmark.id())),"far-page proof grants exact known ownership, not AABB ownership of the unknown neighbor");
            check(result.bounds().equals(bounds)&&result.ownershipRevision()==store.ownershipRevision(),"proof preserves exact request bounds and topology revision");
            check(geometry.nextPageIndex()>0&&streamReads>20,"discovery and real movement reads both progress while the ownership proof runs");
            check(store.cacheStats().pages()<=LandmarkStore.DECODED_PAGE_LIMIT&&store.cacheStats().leaves()<=LandmarkStore.DECODED_LEAF_LIMIT,"ownership shares the bounded decoded-page cache");
            System.out.println("SourceDiscoveryReadLatencyTest: ownership/"+(background?"background":"requested")+", proof="+ticks+" ticks, sustained read max="+maxLatency+" ticks, discovery pages="+geometry.nextPageIndex());
        }finally{
            request.cancel(new CancellationException("fixture finished"));geometry.cancel();
        }
    }
    /** Full-size horizon tiles use the real background Read path, not a movement Read.
     * Hold the detached decode pending to prove no tick ever joins it, then finish all
     * real NBT samples under sustained movement pressure with the engine unavailable. */
    private static void horizonReadYieldsToMovement() throws Exception {
        Class<?> horizon=type("DiscoveryRead");
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->{
            try{return field(OPERATION,"background").getBoolean(op);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        var session=session(null,schedule);var tasks=new ArrayDeque<Runnable>();
        Bounds bounds=Bounds.cube(0,0,0,32);var future=new CompletableFuture<SourceLandmarks.Region>();
        var constructor=horizon.getDeclaredConstructor(SESSION,String.class,Bounds.class,CompletableFuture.class);constructor.setAccessible(true);
        Object observation=constructor.newInstance(session,DIMENSION,bounds,future);
        var reader=new GeneratedSourceReader(DIMENSION,bounds,tasks::add,0,256,63,savedChunk());
        field(OPERATION,"reader").set(observation,reader);
        invoke(method(SESSION,"offer",OPERATION),session,observation);
        check(schedule.backgroundPending()==1&&schedule.readPending()==0,"actual offer keeps full horizon tiles out of the uninterrupted movement lane");
        invoke(ADVANCE,session,false);
        check(!tasks.isEmpty()&&!future.isDone(),"horizon decode is genuinely pending with no model readiness");
        var urgent=new ArrayList<CompletableFuture<SourceLandmarks.Region>>();
        for(int n=0;n<3;n++)urgent.add(queueRead(session,schedule));
        int ticks=0;
        while(urgent.stream().anyMatch(f->!f.isDone())&&ticks<12){invoke(ADVANCE,session,false);ticks++;}
        check(ticks<=9&&urgent.stream().allMatch(CompletableFuture::isDone),"walk/near/target reads finish without waiting for a 32768-cell horizon decode (ticks="+ticks+")");
        check(!future.isDone()&&!tasks.isEmpty(),"movement never joined or completed the held horizon worker");urgent.forEach(SourceDiscoveryReadLatencyTest::validateRead);
        CompletableFuture<SourceLandmarks.Region> movement=null;int start=0,maxLatency=0,completed=0;
        for(int tick=0;tick<2000&&!future.isDone();tick++){
            if(movement==null){movement=queueRead(session,schedule);start=tick;}
            while(!tasks.isEmpty())tasks.removeFirst().run();
            invoke(ADVANCE,session,false);
            check(reader.lastSampled<=512,"horizon retains the 512-cell advance cap");
            if(movement.isDone()){
                validateRead(movement);maxLatency=Math.max(maxLatency,tick-start+1);
                check(tick-start+1<=4,"movement waits at most one horizon sampling advance");movement=null;completed++;
            }
        }
        check(future.isDone()&&!future.isCompletedExceptionally(),"full horizon observation also progresses under sustained movement reads");
        var result=future.join();
        check(result.bounds().equals(bounds)&&result.cells().size()==16384&&!result.complete(),"all four real saved chunk slices survive; absent upper sections remain UNKNOWN");
        check(result.cells().stream().allMatch(cell->cell.material().blockId().equals("minecraft:stone")),"horizon result preserves actual material rather than fabricated coverage");
        check(completed>50&&maxLatency<=4,"test exercises sustained movement while the entire horizon tile finishes");
        // Saturation is reported through the actual public-operation admission path.
        for(int n=0;n<SourceLandmarks.DISCOVERY_BUDGET;n++){
            var pending=constructor.newInstance(session,DIMENSION,bounds,new CompletableFuture<SourceLandmarks.Region>());
            invoke(method(SESSION,"offer",OPERATION),session,pending);
        }
        var rejected=new CompletableFuture<SourceLandmarks.Region>();
        invoke(method(SESSION,"offer",OPERATION),session,constructor.newInstance(session,DIMENSION,bounds,rejected));
        check(schedule.backgroundPending()==SourceLandmarks.DISCOVERY_BUDGET&&rejected.isCompletedExceptionally(),"horizon queue remains bounded with visible rejection, never truncated success");
        schedule.cancelIf(op->true,op->{});
        System.out.println("SourceDiscoveryReadLatencyTest: full horizon tile, initial movement="+ticks+" ticks, sustained max="+maxLatency+" ticks, sampled="+result.cells().size());
    }
    /** Exercise the real Session hint admission while a Read is active. A regression that
     * only tests a pure picker would miss the old idle-only admission starvation. */
    private static void hintsAdmittedDuringContinuousMovement() throws Exception {
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->{
            try{return field(OPERATION,"background").getBoolean(op);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        var session=session(null,schedule);
        field(SESSION,"observationClock").set(session,(java.util.function.LongSupplier)()->1234);
        invoke(method(SESSION,"hint",String.class,BlockPos.class),session,DIMENSION,BlockPos.ORIGIN);
        var first=queueRead(session,schedule);invoke(ADVANCE,session,false);
        check(schedule.backgroundPending()==0&&((Set<?>)field(SESSION,"hints").get(session)).size()==1,"unready hints remain unmaterialized while movement progresses");
        invoke(ADVANCE,session,true); // same running Read; the old admission site never ran
        check(schedule.backgroundPending()==1&&((Set<?>)field(SESSION,"hints").get(session)).isEmpty(),"real Ensure is admitted before pick even with a running movement Read");
        Object cascade=schedule.backgroundLane.peekFirst();
        check(ENSURE.isInstance(cascade)&&field(OPERATION,"background").getBoolean(cascade),"hint becomes a real background Ensure, not just a status/queue counter");
        invoke(method(SESSION,"hint",String.class,BlockPos.class),session,DIMENSION,new BlockPos(100,0,0));
        invoke(ADVANCE,session,true);
        check(schedule.backgroundPending()==1&&((Set<?>)field(SESSION,"hints").get(session)).size()==1,"hint admission keeps one materialized Ensure rather than flooding IO/snapshot memory");
        field(OPERATION,"reader").set(cascade,new GeneratedSourceReader(DIMENSION,READ_BOUNDS,Runnable::run,0,256,63,savedChunk()));
        field(ENSURE,"catalogEnd").setBoolean(cascade,true); // no persisted parents in this fixture
        CompletableFuture<SourceLandmarks.Region> movement=first;int observedAt=-1,completed=0;
        try(var worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("hint-integration").factory())){
            field(SESSION,"worker").set(session,worker);
            for(int tick=0;tick<100;tick++){
                if(movement.isDone()){validateRead(movement);completed++;movement=queueRead(session,schedule);}
                invoke(ADVANCE,session,true);
                if(observedAt<0&&field(ENSURE,"observed").get(cascade)!=null)observedAt=tick;
                if(field(ENSURE,"prepared").get(cascade)!=null)break;
            }
            check(observedAt>=0&&completed>5,"hint observes actual generated cells while movement remains continuously queued");
            check(field(ENSURE,"time").getLong(cascade)==1234&&field(ENSURE,"seaLevel").getInt(cascade)==63,"scheduled hint preserves detached source metadata");
            @SuppressWarnings("unchecked") var prepared=(CompletableFuture<SourceLandmarks.Prepared>)field(ENSURE,"prepared").get(cascade);
            check(prepared!=null&&!prepared.get(5,TimeUnit.SECONDS).geometry().pages().isEmpty(),"admitted hint proceeds to real asynchronous source ownership preparation");
            check(!((CompletableFuture<?>)field(OPERATION,"future").get(cascade)).isCompletedExceptionally(),"continuous movement did not cancel real generation");
        }
    }
    private static void invalidatedHorizonObservation() throws Exception {
        Class<?> horizon=type("DiscoveryRead");
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->horizon.isInstance(op));
        var session=session(null,schedule);var tasks=new ArrayDeque<Runnable>();
        var future=new CompletableFuture<SourceLandmarks.Region>();
        var constructor=horizon.getDeclaredConstructor(SESSION,String.class,Bounds.class,CompletableFuture.class);constructor.setAccessible(true);
        Object observation=constructor.newInstance(session,DIMENSION,READ_BOUNDS,future);
        var reader=new GeneratedSourceReader(DIMENSION,READ_BOUNDS,tasks::add,0,256,63,savedChunk());
        field(OPERATION,"reader").set(observation,reader);schedule.background(observation);
        invoke(ADVANCE,session,false);var movement=queueRead(session,schedule);invoke(ADVANCE,session,false);
        check(schedule.backgroundPending()==1&&!tasks.isEmpty(),"horizon observation is parked with a genuinely pending detached decode");
        invoke(method(SESSION,"invalidate",String.class,BlockPos.class),session,DIMENSION,BlockPos.ORIGIN);
        check(future.isCompletedExceptionally()&&reader.cancelled&&schedule.backgroundPending()==0,"source edit cancels the actual parked horizon operation and reader");
        check(movement.isCompletedExceptionally(),"source edit also invalidates an active overlapping movement sample");
        while(!tasks.isEmpty())tasks.removeFirst().run();
        var replacement=queueRead(session,schedule);
        for(int tick=0;tick<5&&!replacement.isDone();tick++)invoke(ADVANCE,session,false);
        check(future.isCompletedExceptionally(),"late horizon completion cannot overwrite an invalidated observation");
        check(replacement.isDone(),"fresh movement sampling continues after source invalidation");validateRead(replacement);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        horizonReadYieldsToMovement();hintsAdmittedDuringContinuousMovement();invalidatedHorizonObservation();
        ownershipDuringDiscovery(false);ownershipDuringDiscovery(true);
        multiPage(false);multiPage(true);
        invalidatedSuspendedOwner(false);invalidatedSuspendedOwner(true);
        System.out.println("SourceDiscoveryReadLatencyTest: "+checks+" checks passed");
    }
}
