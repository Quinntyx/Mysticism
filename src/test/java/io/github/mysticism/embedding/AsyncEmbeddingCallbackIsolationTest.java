package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;

/** Production deadlines/admission, including ALL delivery workers blocked by foreign callbacks. */
public final class AsyncEmbeddingCallbackIsolationTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static final class Provider implements EmbeddingProvider {
        final CountDownLatch work=new CountDownLatch(1), ready=new CountDownLatch(1), started=new CountDownLatch(1);
        boolean holdReady, inspectHelper, closeHeld, helperAccessible;
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        public void checkReady() { if(holdReady)await(ready); }
        public Vec384f getEmbedding(String text) { started.countDown(); await(work); float[] v=new float[EmbeddingSpace.DIMENSIONS];v[0]=1;return new Vec384f(v); }
        public void close() {
            if(inspectHelper) {
                closeHeld=Thread.holdsLock(EmbeddingHelper.class);
                CountDownLatch accessible=new CountDownLatch(1);
                Thread probe=new Thread(()->{synchronized(EmbeddingHelper.class){accessible.countDown();}});
                probe.setDaemon(true);probe.start();
                try { helperAccessible=accessible.await(500,TimeUnit.MILLISECONDS); } catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
            work.countDown();ready.countDown();
        }
    }
    private static void failed(CompletableFuture<?> future) throws Exception {
        try { future.get(1,TimeUnit.SECONDS);throw new AssertionError("Expected failure"); }
        catch(ExecutionException|CancellationException expected) { checks++; }
    }
    private static void isolatedTimeouts() throws Exception {
        Provider p=new Provider();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var e=new AsyncEmbeddingEngine(p,1,8,2,Duration.ofMillis(120))) {
            e.readiness().get(1,TimeUnit.SECONDS);
            var first=e.embed("shared");check(p.started.await(1,TimeUnit.SECONDS),"Provider started");
            var sibling=e.embed("shared");
            first.whenComplete((v,error)->{entered.countDown();await(release);});
            check(entered.await(1,TimeUnit.SECONDS),"First timeout callback entered");
            failed(sibling); // same request, independently completed even while first subscriber blocks
            failed(e.embed("unrelated")); // deadline thread must remain free to process a later request
            check(e.inflightSize()==0,"Blocked callback held pending ownership");
        } finally { release.countDown(); }
        Provider slow=new Provider();slow.holdReady=true;
        CountDownLatch readyEntered=new CountDownLatch(1),readyRelease=new CountDownLatch(1);
        try(var e=new AsyncEmbeddingEngine(slow,1,8,2,Duration.ofMillis(120))) {
            var first=e.readiness();var second=e.readiness();
            first.whenComplete((v,error)->{readyEntered.countDown();await(readyRelease);});
            check(readyEntered.await(1,TimeUnit.SECONDS),"Readiness callback entered");failed(second);
            check(!e.isReady(),"Timed out readiness revived");
        } finally { readyRelease.countDown(); }
    }
    private static void successAndShutdown() throws Exception {
        Provider success=new Provider();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var e=new AsyncEmbeddingEngine(success,1,8,2,Duration.ofSeconds(1))) {
            e.readiness().get(1,TimeUnit.SECONDS);
            var first=e.embed("success-shared");var sibling=e.embed("success-shared");
            first.whenComplete((v,error)->{entered.countDown();await(release);});success.work.countDown();
            check(entered.await(1,TimeUnit.SECONDS),"Success callback entered");
            var copy=sibling.get(1,TimeUnit.SECONDS);copy.mul(0);
            check(e.embed("success-shared").get(1,TimeUnit.SECONDS).length()==1,"Subscriber/cache vectors alias");
            check(e.embed("next-success").get(1,TimeUnit.SECONDS).length()==1,"User callback occupies inference worker");
        } finally {release.countDown();}
        Provider closing=new Provider();CountDownLatch closeEntered=new CountDownLatch(1),closeRelease=new CountDownLatch(1);
        var e=new AsyncEmbeddingEngine(closing,1,8,2,Duration.ofSeconds(1));
        try {
            e.readiness().get(1,TimeUnit.SECONDS);var first=e.embed("close-shared");var sibling=e.embed("close-shared");
            first.whenComplete((v,error)->{closeEntered.countDown();await(closeRelease);});
            e.close();check(closeEntered.await(1,TimeUnit.SECONDS),"Shutdown callback entered");failed(sibling);
            check(e.inflightSize()==0,"Shutdown completion blocked ownership cleanup");
        } finally {closeRelease.countDown();e.close();}
    }
    private static void saturation() throws Exception {
        // Prior callbacks may have just returned: wait for their finally-release, not merely isDone.
        Field slots=AsyncEmbeddingEngine.class.getDeclaredField("CALLBACK_SLOTS");slots.setAccessible(true);
        Semaphore permits=(Semaphore)slots.get(null);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(permits.availablePermits()!=AsyncEmbeddingEngine.CALLBACK_CAPACITY && System.nanoTime()<until)Thread.sleep(1);
        check(permits.availablePermits()==AsyncEmbeddingEngine.CALLBACK_CAPACITY,"Prior callbacks leaked reservations");
        Provider p=new Provider();CountDownLatch entered=new CountDownLatch(AsyncEmbeddingEngine.CALLBACK_CAPACITY),release=new CountDownLatch(1);
        var e=new AsyncEmbeddingEngine(p,1,8,2,Duration.ofMillis(300));
        try {
            e.readiness().get(1,TimeUnit.SECONDS);
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(permits.availablePermits()!=AsyncEmbeddingEngine.CALLBACK_CAPACITY && System.nanoTime()<until)Thread.sleep(1);
            List<CompletableFuture<Vec384f>> accepted=new ArrayList<>();
            for(int i=0;i<AsyncEmbeddingEngine.CALLBACK_CAPACITY;i++) {
                var f=e.embed("all-blocked");accepted.add(f);
                f.whenComplete((v,error)->{entered.countDown();await(release);});
            }
            check(entered.await(2,TimeUnit.SECONDS),"A blocked subscriber starved another admitted completion");
            check(accepted.stream().allMatch(CompletableFuture::isDone),"Admitted deadlines unfinished");
            check(e.inflightSize()==0,"Deadline ownership leaked at saturation");
            var rejected=e.embed("backpressure");
            check(rejected.isCompletedExceptionally(),"All-blocked pool admitted unschedulable work");failed(rejected);
            check(e.inflightSize()==0,"Rejected subscriber created pending work");
            long deliveryThreads=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("Mysticism-EmbedDelivery")).count();
            check(deliveryThreads<=AsyncEmbeddingEngine.CALLBACK_CAPACITY,"Unbounded callback threads");
            long start=System.nanoTime();e.close();
            check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(500),"Shutdown waits for user callbacks");
        } finally {release.countDown();e.close();}
        until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(permits.availablePermits()!=AsyncEmbeddingEngine.CALLBACK_CAPACITY && System.nanoTime()<until)Thread.sleep(1);
        check(permits.availablePermits()==AsyncEmbeddingEngine.CALLBACK_CAPACITY,"Completed callbacks leaked permits");
    }
    private static void helperShutdown() throws Exception {
        Provider p=new Provider();p.inspectHelper=true;
        var e=new AsyncEmbeddingEngine(p,1,8,2,Duration.ofSeconds(1));e.readiness().get(1,TimeUnit.SECONDS);
        Field field=EmbeddingHelper.class.getDeclaredField("engine");field.setAccessible(true);
        check(field.get(null)==null,"Test refuses to overwrite an active helper engine");field.set(null,e);
        try { EmbeddingHelper.shutdown(); }
        finally {e.close();field.set(null,null);}
        check(!p.closeHeld,"Helper shutdown closes provider under lifecycle monitor");
        check(p.helperAccessible,"Provider close cross-locks lifecycle monitor");
        check(EmbeddingHelper.getInitializationStatus().equals("Stopped"),"Late shutdown overwrote status");
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("helper"))helperShutdown();
        else {isolatedTimeouts();successAndShutdown();saturation();helperShutdown();}
        System.out.println("AsyncEmbeddingCallbackIsolationTest passed: "+checks+" checks");
    }
}
