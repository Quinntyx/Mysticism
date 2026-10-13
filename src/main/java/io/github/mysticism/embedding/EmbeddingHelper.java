package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shared asynchronous provider lifecycle. Initialization never blocks the server thread. */
public final class EmbeddingHelper {
    private static volatile AsyncEmbeddingEngine engine;
    private static volatile CompletableFuture<Void> readiness=CompletableFuture.failedFuture(new IllegalStateException("Embeddings not started"));
    private static volatile String failure="Not started";
    private static Object owner;
    private EmbeddingHelper(){}
    public static void initializeServer(){initializeServer(EmbeddingHelper.class);}
    public static void initializeServer(Object server){
        initializeServer(server, Integer.getInteger("mysticism.embedding.workers",2), EmbeddingService::new,
                Duration.ofMillis(Long.getLong("mysticism.embedding.deadlineMillis",30000)));
    }
    public static void initializeServer(int threads){
        initializeServer(EmbeddingHelper.class, threads, EmbeddingService::new,
                Duration.ofMillis(Long.getLong("mysticism.embedding.deadlineMillis",30000)));
    }
    // The factory seam exercises actual lifecycle behavior without loading models or using a server.
    static void initializeServer(Object server, int threads, Supplier<? extends EmbeddingProvider> factory, Duration deadline){
        Objects.requireNonNull(server);
        AsyncEmbeddingEngine previous;
        LifecycleEmbeddingProvider unstarted = null;
        synchronized(EmbeddingHelper.class){
            if(engine!=null && owner==server)return;
            previous=engine;engine=null;owner=server;
            try{
                unstarted=new LifecycleEmbeddingProvider(factory);
                engine=new AsyncEmbeddingEngine(unstarted,threads,256,8192,deadline);
                unstarted=null; // ownership transferred to the engine
                failure="Initializing";
            }catch(Throwable t){
                failure="Unavailable: "+t;readiness=CompletableFuture.failedFuture(t);
            }
        }
        // The process lease prevents new factory execution until this old engine has drained.
        if(previous!=null)previous.close();
        if(unstarted!=null)unstarted.close();
    }
    public static EmbeddingProfile profile(){return EmbeddingProfile.current();}
    public static CompletableFuture<Void> readiness(){
        AsyncEmbeddingEngine e=engine;
        // Each pending caller reserves its own delivery, not a shared completion chain on which
        // one foreign callback can hold every other world-entry listener indefinitely.
        return e==null?readiness.copy():e.readiness();
    }
    public static boolean isReady(){AsyncEmbeddingEngine e=engine;return e!=null && e.isReady();}
    /** Compatibility only. Never call on a server tick/startup thread. */
    public static void awaitReady(){readiness().join();}
    public static void shutdown(){shutdown(null);}
    /** Ignore a late stop event from a server whose replacement already owns the helper. */
    public static void shutdown(Object server){
        AsyncEmbeddingEngine stopped;
        synchronized(EmbeddingHelper.class){
            if(server!=null && owner!=server)return;
            stopped=engine;engine=null;owner=null;
            readiness=CompletableFuture.failedFuture(new IllegalStateException("Embeddings stopped"));failure="Stopped";
        }
        // Provider close and future delivery must not run while holding the shared lifecycle lock.
        if(stopped!=null)stopped.close();
    }
    public static CompletableFuture<Vec384f> getEmbedding(String descriptor){AsyncEmbeddingEngine e=engine;return e==null?CompletableFuture.failedFuture(new IllegalStateException("Embeddings unavailable")):e.embed(descriptor);}
    public static Optional<Vec384f> getEmbeddingBlocking(String descriptor){try{return Optional.of(getEmbedding(descriptor).get());}catch(InterruptedException e){Thread.currentThread().interrupt();return Optional.empty();}catch(Exception e){return Optional.empty();}}
    public static CompletableFuture<Vec384f> composeDescriptors(List<DescriptorVectors.WeightedDescriptor> descriptors){return DescriptorVectors.embed(descriptors);}
    public static String getInitializationStatus(){
        AsyncEmbeddingEngine e=engine;return e==null?failure:e.initializationStatus();
    }
    public static int getCacheSize(){AsyncEmbeddingEngine e=engine;return e==null?0:e.cacheSize();}
    public static int getInflightSize(){AsyncEmbeddingEngine e=engine;return e==null?0:e.inflightSize();}
}
