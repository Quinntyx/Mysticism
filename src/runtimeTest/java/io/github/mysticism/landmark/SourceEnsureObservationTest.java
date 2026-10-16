package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Drives the real Ensure.advance -> observe -> resultAsync -> observation-consumption
 * path, followed by real topology preparation. No game bootstrap, server/world creation,
 * registry mutation, embedding service or model weights. Production passes these same
 * detached metadata inputs after reading the source world on the owner thread. */
public final class SourceEnsureObservationTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Field field(Class<?> type,String name) throws ReflectiveOperationException {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void advance(Object ensure) throws ReflectiveOperationException {
        var method=ensure.getClass().getDeclaredMethod("advance");method.setAccessible(true);
        try{method.invoke(ensure);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw failure;
        }
    }
    private static GeneratedSourceReader.ChunkIo savedChunk() {
        // A single stone section with four blocks of relief. Above it is generated air.
        var root=new NbtCompound();root.putString("Status","full");var sections=new NbtList();
        var section=new NbtCompound();section.putByte("Y",(byte)5);
        var states=new NbtCompound();var palette=new NbtList();
        for(String block:List.of("minecraft:stone","minecraft:air")){var entry=new NbtCompound();entry.putString("Name",block);palette.add(entry);}
        states.put("palette",palette);
        long[] data=new long[256];
        for(int index=0;index<4096;index++){int x=index&15,y=index>>8;if(y>10+x)data[index/16]|=1L<<((index%16)*4);}
        states.putLongArray("data",data);section.put("block_states",states);
        var biomes=new NbtCompound();var biomePalette=new NbtList();biomePalette.add(NbtString.of("minecraft:plains"));
        biomes.put("palette",biomePalette);section.put("biomes",biomes);sections.add(section);
        var upper=section.copy();upper.putByte("Y",(byte)6);
        var airStates=new NbtCompound();var airPalette=new NbtList();var air=new NbtCompound();air.putString("Name","minecraft:air");airPalette.add(air);
        airStates.put("palette",airPalette);upper.put("block_states",airStates);sections.add(upper);root.put("sections",sections);
        root.put("Heightmaps",new NbtCompound()); // absent height proof conservatively marks sky
        return new GeneratedSourceReader.ChunkIo(){
            @Override public net.minecraft.world.chunk.WorldChunk live(ChunkPos pos){return null;}
            @Override public CompletableFuture<?> scan(ChunkPos pos,GeneratedSourceReader.LimitedCollector collector){root.accept(collector);return CompletableFuture.completedFuture(null);}
        };
    }
    public static void main(String[] args) throws Exception {
        // Allocate ONLY the private session holder, without initializing persistence,
        // workers or a server. The real observe path here needs its status/cell counters
        // and owner-thread clock; all IO runs through the actual reader state machine.
        var allocator=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        Class<?> sessionType=Class.forName(SourceLandmarks.class.getName()+"$Session");
        Object session=allocator.allocateInstance(sessionType);
        int[] clockReads={0};
        field(sessionType,"observationClock").set(session,(LongSupplier)()->{clockReads[0]++;return 9876;});
        Bounds bounds=new Bounds(0,90,0,5,97,1);
        // Non-default source sea level. A fallback of 63/Overworld=100 would classify
        // the same observed relief as BIOME, not MOUNTAIN.
        var detachedTasks=new ArrayDeque<Runnable>();
        var reader=new GeneratedSourceReader("minecraft:the_nether",bounds,detachedTasks::add,-64,384,50,savedChunk());
        var future=new CompletableFuture<Optional<LandmarkMetadata>>();
        Class<?> ensureType=Class.forName(SourceLandmarks.class.getName()+"$Ensure");
        var constructor=ensureType.getDeclaredConstructor(sessionType,String.class,BlockPos.class,String.class,boolean.class,CompletableFuture.class);
        constructor.setAccessible(true);var position=new BlockPos(0,91,0);
        Object ensure=constructor.newInstance(session,"minecraft:the_nether",position,null,false,future);
        Class<?> operationType=ensureType.getSuperclass();field(operationType,"reader").set(ensure,reader);
        for(int tick=0;tick<8&&field(operationType,"snapshot").get(ensure)==null;tick++){
            advance(ensure);
            check(reader.lastSampled<=512,"Ensure retains the per-tick sampling budget");
            if(field(operationType,"snapshot").get(ensure)==null)while(!detachedTasks.isEmpty())detachedTasks.removeFirst().run();
        }
        var snapshot=(CompletableFuture<?>)field(operationType,"snapshot").get(ensure);
        check(snapshot!=null&&!snapshot.isDone(),"Ensure staged a genuinely pending detached snapshot");
        advance(ensure); // must return while the worker is pending, not join it on the tick
        check(field(ensureType,"observed").get(ensure)==null&&clockReads[0]==0&&!future.isDone(),"Ensure yields while the snapshot worker is pending");
        while(!detachedTasks.isEmpty())detachedTasks.removeFirst().run();
        advance(ensure); // actual production Ensure transition formerly dereferenced reader.world=null
        var observed=(SourceLandmarks.Region)field(ensureType,"observed").get(ensure);
        check(observed!=null&&observed.complete()&&observed.cells().size()==35,"Ensure consumed all generated cells from the real NBT reader");
        check(!future.isDone(),"successful observation proceeds to preparation rather than failing/completing the Ensure prematurely");
        check(field(ensureType,"time").getLong(ensure)==9876&&clockReads[0]==1,"Ensure captures its owner-thread clock exactly once at observation consumption");
        int seaLevel=field(ensureType,"seaLevel").getInt(ensure);
        check(seaLevel==50,"Ensure preserves its actual source sea level without needing a retained world or an invented default");
        check(observed.dimension().equals("minecraft:the_nether"),"detached observation retains its source dimension");
        // No parents in this fixture: skip only the persistence-catalogue enumeration,
        // then drive Ensure's own bounded-worker preparation stage (not a substitute).
        field(ensureType,"catalogEnd").setBoolean(ensure,true);
        try(var worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("ensure-regression").factory())){
            field(sessionType,"worker").set(session,worker);advance(ensure);
            @SuppressWarnings("unchecked") var preparation=(CompletableFuture<SourceLandmarks.Prepared>)field(ensureType,"prepared").get(ensure);
            check(preparation!=null&&preparation==field(operationType,"work").get(ensure),"Ensure schedules and tracks its real async topology preparation");
            var prepared=preparation.get(5,TimeUnit.SECONDS); // test-only wait; never on a game tick
            check(prepared!=null&&prepared.kind()==Landmark.Kind.MOUNTAIN,"completed Ensure observation proceeds to real source topology preparation");
            check(!prepared.geometry().pages().isEmpty(),"real source ownership geometry was built, not just a successful status");
        }
        for(int wrongSeaLevel:List.of(63,100)){
            var wrong=SourceLandmarks.prepare(observed,List.of(),position,null,false,9876,wrongSeaLevel,Map.of());
            check(wrong!=null&&wrong.kind()==Landmark.Kind.BIOME,"fixture detects an incorrect default/Overworld sea level of "+wrongSeaLevel);
        }
        System.out.println("SourceEnsureObservationTest: "+checks+" checks passed");
    }
}
