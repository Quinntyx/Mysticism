package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.util.math.ChunkPos;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only bridge to actual DiscoveryRead/Session + generated-NBT reader, not a substitute
 * scheduler. A fake ChunkIo supplies saved chunks; no Minecraft server/registry/model is booted. */
public final class GeneratedDiscoveryFixture {
    private static final Class<?> SESSION=type("Session"),DISCOVERY=type("DiscoveryRead"),READ=type("Read"),OP=DISCOVERY.getSuperclass();
    private final Object session;private final ExecutorService worker;
    private final NbtCompound root=new NbtCompound();
    public int scans;
    private static Class<?> type(String name){try{return Class.forName(SourceLandmarks.class.getName()+"$"+name);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    public GeneratedDiscoveryFixture(ExecutorService worker)throws Exception{
        this.worker=worker;
        var allocator=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);session=allocator.allocateInstance(SESSION);
        var schedule=new SourceSchedule<Object>(SourceLandmarks.DISCOVERY_FAIR_SHARE,READ::isInstance,op->DISCOVERY.isInstance(op));
        field(SESSION,"schedule").set(session,schedule);field(SESSION,"hints").set(session,new io.github.mysticism.landmark.extract.DiscoveryPrioritizer(128));field(SESSION,"worker").set(session,worker);
        root.putString("Status","full");root.put("Heightmaps",new NbtCompound());var sections=new NbtList();
        for(int y=0;y<10;y++){
            var section=new NbtCompound();section.putByte("Y",(byte)y);var states=new NbtCompound();var palette=new NbtList();var material=new NbtCompound();
            material.putString("Name",y<4?"minecraft:stone":"minecraft:air");palette.add(material);states.put("palette",palette);section.put("block_states",states);
            var biomes=new NbtCompound();var names=new NbtList();names.add(NbtString.of("minecraft:plains"));biomes.put("palette",names);section.put("biomes",biomes);sections.add(section);
        }
        root.put("sections",sections);
    }
    public CompletableFuture<SourceLandmarks.Region> request(Bounds bounds,boolean urgent)throws Exception{
        var result=new CompletableFuture<SourceLandmarks.Region>();Class<?> type=urgent?READ:DISCOVERY;
        var constructor=type.getDeclaredConstructor(SESSION,String.class,Bounds.class,CompletableFuture.class);constructor.setAccessible(true);
        var op=constructor.newInstance(session,"minecraft:overworld",bounds,result);
        var reader=new GeneratedSourceReader("minecraft:overworld",bounds,worker,0,160,63,new GeneratedSourceReader.ChunkIo(){
            public net.minecraft.world.chunk.WorldChunk live(ChunkPos p){return null;}
            public CompletableFuture<?> scan(ChunkPos p,GeneratedSourceReader.LimitedCollector collector){scans++;root.accept(collector);return CompletableFuture.completedFuture(null);}
        });
        field(OP,"reader").set(op,reader);var offer=SESSION.getDeclaredMethod("offer",OP);offer.setAccessible(true);offer.invoke(session,op);return result;
    }
    public void tick()throws Exception{
        var advance=SESSION.getDeclaredMethod("advanceScheduled",boolean.class);advance.setAccessible(true);
        try{advance.invoke(session,false);}catch(InvocationTargetException e){throw new AssertionError(e.getCause());}
    }
}
