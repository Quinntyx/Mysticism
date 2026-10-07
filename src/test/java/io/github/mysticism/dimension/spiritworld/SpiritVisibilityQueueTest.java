package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.vector.SimpleKnnIndex;
import java.util.*;
import java.util.concurrent.*;

/** Exact production Runtime/job/generation paths; no server boot, registry reads or tick mocks. */
public final class SpiritVisibilityQueueTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void hold(CountDownLatch gate){try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public static void main(String[] args) throws Exception {
        try(var runtime=new SpiritVisibilityService.Runtime()) {
            CountDownLatch started=new CountDownLatch(1),gate=new CountDownLatch(1);
            runtime.worker.execute(()->{started.countDown();hold(gate);});
            check(started.await(1,TimeUnit.SECONDS),"Worker started");
            List<CompletableFuture<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<64;i++){var job=runtime.submit(()->1);check(job!=null,"Queue admission");jobs.add(job);}
            check(runtime.worker.getQueue().size()==64,"Actual queue full");
            jobs.forEach(f->f.cancel(false));
            check(runtime.worker.getQueue().isEmpty(),"Cancelled futures retained executor slots");
            for(int round=0;round<200;round++) {
                var job=runtime.submit(()->1);check(job!=null,"Cancellation churn prevents fresh admission");job.cancel(false);
                check(runtime.worker.getQueue().isEmpty(),"Cancellation churn leaks queue nodes");
            }
            jobs.clear();for(int i=0;i<64;i++)jobs.add(runtime.submit(()->1));
            var index=new SimpleKnnIndex();
            runtime.refreshCatalogue(index); // Original supplyAsync path threw from tick on this saturation.
            check(runtime.index==null && runtime.size==-1 && runtime.building==null,"Rejected generation acknowledged/suppresses retry");
            jobs.get(0).cancel(false);
            runtime.refreshCatalogue(index);
            check(runtime.index==index && runtime.building!=null,"Same generation was not retried after capacity returned");
            jobs.forEach(f->f.cancel(false));gate.countDown();
            runtime.building.get(2,TimeUnit.SECONDS);runtime.pollCatalogue();
            check(runtime.catalogue!=null && runtime.building==null,"Accepted retry did not publish catalogue");
            runtime.building=runtime.submit(()->{throw new IllegalArgumentException("forced failed catalogue");});
            try{runtime.building.get(1,TimeUnit.SECONDS);throw new AssertionError("Expected failed catalogue");}catch(ExecutionException expected){checks++;}
            runtime.pollCatalogue();
            check(runtime.index==null && runtime.catalogue==null,"Failed build generation suppresses retries");
            runtime.refreshCatalogue(index);runtime.building.get(1,TimeUnit.SECONDS);runtime.pollCatalogue();
            check(runtime.catalogue!=null,"Failed build did not recover");
        }
        var closed=new SpiritVisibilityService.Runtime();closed.close();
        closed.refreshCatalogue(new SimpleKnnIndex());
        check(closed.building==null && closed.index==null,"Closed executor rejection escaped or marked generation current");
        System.out.println("SpiritVisibilityQueueTest passed: "+checks+" checks");
    }
}
