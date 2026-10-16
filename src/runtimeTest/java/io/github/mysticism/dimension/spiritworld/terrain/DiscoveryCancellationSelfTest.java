package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.SourceLandmarks;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Exercises the actual private window teardown without booting Minecraft or an embedding engine.
 * Direct whenComplete callbacks model MinecraftServer.execute running inline on its server thread;
 * queued callbacks model execution after teardown and a new request for the same source tile. */
public final class DiscoveryCancellationSelfTest {
    private static int checks;
    private static final Class<?> WINDOW;
    private static final Method CANCEL;
    static {
        try {
            WINDOW=Class.forName(SpiritTerrainService.class.getName()+"$Window");
            CANCEL=SpiritTerrainService.class.getDeclaredMethod("cancelWindow",WINDOW);
            CANCEL.setAccessible(true);
        } catch(ReflectiveOperationException error) {throw new ExceptionInInitializerError(error);}
    }
    private static void check(boolean value,String why) {checks++;if(!value)throw new AssertionError(why);}
    private static Field field(String name) throws ReflectiveOperationException {
        Field field=WINDOW.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static Object window() throws ReflectiveOperationException {
        var constructor=WINDOW.getDeclaredConstructor(String.class,Vec3d.class,Vec384f.class,Basis384f.class,String.class);
        constructor.setAccessible(true);
        // Only inert window state: no inference, item vectors or persisted embeddings are replaced.
        return constructor.newInstance("minecraft:overworld",Vec3d.ZERO,Vec384f.ZERO(),new Basis384f(),"");
    }
    @SuppressWarnings("unchecked")
    private static Map<Bounds,CompletableFuture<SourceLandmarks.Region>> pending(Object window) throws ReflectiveOperationException {
        return (Map<Bounds,CompletableFuture<SourceLandmarks.Region>>)field("discoveryPending").get(window);
    }
    @SuppressWarnings("unchecked")
    private static Map<Bounds,List<SourceMeshBuilder.Node>> discovered(Object window) throws ReflectiveOperationException {
        return (Map<Bounds,List<SourceMeshBuilder.Node>>)field("discovered").get(window);
    }
    private static Bounds tile(int x) {return Bounds.cube(x,64,0,32);}

    private static void inlineCancellation() throws ReflectiveOperationException {
        Object window=window();var pending=pending(window);var discovered=discovered(window);
        var callbacks=new ArrayList<CompletableFuture<SourceLandmarks.Region>>();
        var futures=new ArrayList<CompletableFuture<SourceLandmarks.Region>>();
        Thread serverThread=Thread.currentThread();int[] invoked={0},accepted={0};
        for(int i=0;i<SourceDiscovery.MAX_INFLIGHT;i++) {
            Bounds tile=tile(i*32);var future=new CompletableFuture<SourceLandmarks.Region>();
            pending.put(tile,future);discovered.put(tile,List.of());futures.add(future);
            callbacks.add(future.whenComplete((region,error)->{
                invoked[0]++;
                check(Thread.currentThread()==serverThread,"cancellation handler executes synchronously on the cancelling thread");
                // The production handler's identity guard must reject teardown cancellation before
                // retry/data publication. Clearing the WHOLE map first also invalidates other callbacks.
                boolean matches=pending.remove(tile,future);
                check(pending.isEmpty(),"all pending identities invalidated before the first inline callback");
                if(!matches)return;
                accepted[0]++;discovered.put(tile,List.of());
            }));
        }
        var owner=new CompletableFuture<>();var near=new CompletableFuture<>();
        field("ownershipFuture").set(window,owner);field("nearFuture").set(window,near);
        var material=new TerrainMeshFrame.Material("minecraft:stone",Map.of());
        var shape=new SourceMeshBuilder.Tile(material,List.of(SourceMeshBuilder.UNIT),0xffffff,0,false,true);
        field("discoveredNodes").set(window,List.of(new SourceMeshBuilder.Node(new BlockPos(0,64,0),1,shape)));
        field("nearCells").set(window,List.of().iterator());field("nearSamples").set(window,window());
        CANCEL.invoke(null,window); // Old values().forEach teardown throws ConcurrentModificationException here.
        check(invoked[0]==SourceDiscovery.MAX_INFLIGHT,"every pending future cancelled despite synchronous callbacks");
        check(accepted[0]==0,"cancelled completions cannot update retry/data state");
        check(futures.stream().allMatch(CompletableFuture::isCancelled),"every pending source read is cancelled");
        check(callbacks.stream().allMatch(CompletableFuture::isCompletedExceptionally),"cancellation propagates through completion stages");
        for(var callback:callbacks) {
            Throwable failure=callback.handle((value,error)->error).join();
            if(failure instanceof java.util.concurrent.CompletionException)failure=failure.getCause();
            check(failure instanceof java.util.concurrent.CancellationException && failure.getSuppressed().length==0,
                    "handler completed normally; exceptional outcome is cancellation, not a hidden assertion failure");
        }
        check(owner.isCancelled() && near.isCancelled(),"ownership and near reads still cancelled");
        check(pending.isEmpty() && discovered.isEmpty(),"teardown clears pending and retained tile maps");
        check(((List<?>)field("discoveredNodes").get(window)).isEmpty(),"retained mesh nodes cleared after cancellation");
        check(field("nearCells").get(window)==null && field("nearSamples").get(window)==null && field("stream").get(window)==null,
                "remaining window cleanup completes rather than aborting during cancellation");
        CANCEL.invoke(null,window);
        check(invoked[0]==SourceDiscovery.MAX_INFLIGHT,"repeated teardown does not re-run callbacks");
    }

    private static void deferredCancellation() throws ReflectiveOperationException {
        Object window=window();var pending=pending(window);var queued=new ArrayDeque<Runnable>();
        var old=new CompletableFuture<SourceLandmarks.Region>();Bounds tile=tile(0);pending.put(tile,old);
        int[] accepted={0};
        old.whenComplete((region,error)->queued.add(()->{
            if(!pending.remove(tile,old))return;
            accepted[0]++;
        }));
        CANCEL.invoke(null,window);
        check(old.isCancelled() && pending.isEmpty() && queued.size()==1,"deferred cancellation also invalidates before scheduling handler");
        var replacement=new CompletableFuture<SourceLandmarks.Region>();pending.put(tile,replacement);
        queued.removeFirst().run();
        check(accepted[0]==0 && pending.get(tile)==replacement,"late cancelled handler cannot displace fresh same-tile request");
        check(!replacement.isDone(),"teardown snapshot does not cancel a later replacement");
        CANCEL.invoke(null,window);
        check(replacement.isCancelled() && pending.isEmpty(),"subsequent teardown cancels the new snapshot");
    }

    private static void emptyAndCompleted() throws ReflectiveOperationException {
        Object window=window();CANCEL.invoke(null,window);
        check(pending(window).isEmpty(),"empty teardown is harmless");
        var done=CompletableFuture.<SourceLandmarks.Region>completedFuture(null);
        pending(window).put(tile(0),done);
        CANCEL.invoke(null,window);
        check(done.isDone() && !done.isCancelled() && pending(window).isEmpty(),"already completed reads do not interrupt cleanup");
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        inlineCancellation();deferredCancellation();emptyAndCompleted();
        System.out.println("DiscoveryCancellationSelfTest: "+checks+" checks passed");
    }
}
