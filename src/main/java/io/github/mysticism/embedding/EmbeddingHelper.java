package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Shared asynchronous provider lifecycle. Initialization never blocks the server thread. */
public final class EmbeddingHelper {
    private static volatile AsyncEmbeddingEngine engine;
    private static volatile CompletableFuture<Void> readiness=CompletableFuture.failedFuture(new IllegalStateException("Embeddings not started"));
    private static volatile EmbeddingReadiness inactive = new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED, new IllegalStateException("Embeddings not started"));
    private EmbeddingHelper(){}
    public static void initializeServer(){initializeServer(Integer.getInteger("mysticism.embedding.workers",2));}
    public static synchronized void initializeServer(int threads){
        if(engine!=null)return;
        EmbeddingProvider provider=null;
        try{
            provider=EmbeddingProviders.create();
            long preparation = provider instanceof EmbeddedEmbeddingService ? 900000 : 30000;
            engine=new AsyncEmbeddingEngine(provider,threads,256,8192,
                    Duration.ofMillis(Long.getLong("mysticism.embedding.deadlineMillis",30000)),
                    Duration.ofMillis(Long.getLong("mysticism.embedding.preparationDeadlineMillis",preparation)));
            readiness=engine.readiness();
        }catch(Throwable t){
            if(provider!=null)try{provider.close();}catch(Throwable closing){t.addSuppressed(closing);}
            inactive=new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED,t);readiness=CompletableFuture.failedFuture(t);
        }
    }
    public static EmbeddingProfile profile(){return EmbeddingProfile.current();}
    public static CompletableFuture<Void> readiness(){AsyncEmbeddingEngine e=engine;return e==null?readiness.copy():e.readiness();}
    public static EmbeddingReadiness state(){AsyncEmbeddingEngine e=engine;return e==null?inactive:e.state();}
    public static boolean isReady(){return state().ready();}
    /** Compatibility only. Never call on a server tick/startup thread. */
    public static void awaitReady(){readiness().join();}
    public static void shutdown(){
        AsyncEmbeddingEngine stopped;
        synchronized(EmbeddingHelper.class){
            stopped=engine;engine=null;
            var failure=new IllegalStateException("Embeddings stopped");
            inactive=new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED,failure);
            readiness=CompletableFuture.failedFuture(failure);
        }
        // Provider close and future delivery must not run while holding the shared lifecycle lock.
        if(stopped!=null)stopped.close();
    }
    public static CompletableFuture<Vec384f> getEmbedding(String descriptor){AsyncEmbeddingEngine e=engine;return e==null?CompletableFuture.failedFuture(new IllegalStateException("Embeddings unavailable",inactive.failure())):e.embed(descriptor);}
    public static Optional<Vec384f> getEmbeddingBlocking(String descriptor){try{return Optional.of(getEmbedding(descriptor).get());}catch(InterruptedException e){Thread.currentThread().interrupt();return Optional.empty();}catch(Exception e){return Optional.empty();}}
    public static CompletableFuture<Vec384f> composeDescriptors(List<DescriptorVectors.WeightedDescriptor> descriptors){return DescriptorVectors.embed(descriptors);}
    public static String getInitializationStatus(){var snapshot=state();return switch(snapshot.phase()){
        case READY -> "Ready";
        case INITIALIZING -> "Initializing";
        case STOPPED -> "Stopped";
        case FAILED -> "Unavailable: "+EmbeddingReadiness.describe(snapshot.failure());
    };}
    public static int getCacheSize(){AsyncEmbeddingEngine e=engine;return e==null?0:e.cacheSize();}
    public static int getInflightSize(){AsyncEmbeddingEngine e=engine;return e==null?0:e.inflightSize();}
}
