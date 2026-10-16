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
    private static volatile EmbeddingReadiness inactive=new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED,new IllegalStateException("Embeddings not started"));
    private static Object owner;
    private EmbeddingHelper(){}
    public static void initializeServer(){initializeServer(EmbeddingHelper.class);}
    public static void initializeServer(Object server){
        initializeDefault(server,Integer.getInteger("mysticism.embedding.workers",2));
    }
    public static void initializeServer(int threads){initializeDefault(EmbeddingHelper.class,threads);}
    private static void initializeDefault(Object server,int threads){
        initializeServer(server,threads,EmbeddingProviders::create,
                Duration.ofMillis(Long.getLong("mysticism.embedding.deadlineMillis",30000)),
                Duration.ofMillis(Long.getLong("mysticism.embedding.preparationDeadlineMillis",
                        "ollama".equals(System.getProperty("mysticism.embedding.provider"))?30000L:900000L)));
    }
    // The factory seam exercises actual lifecycle behavior without loading models or using a server.
    static void initializeServer(Object server,int threads,Supplier<? extends EmbeddingProvider> factory,Duration deadline){
        initializeServer(server,threads,factory,deadline,deadline);
    }
    static void initializeServer(Object server,int threads,Supplier<? extends EmbeddingProvider> factory,Duration deadline,Duration preparationDeadline){
        Objects.requireNonNull(server);
        AsyncEmbeddingEngine previous;
        LifecycleEmbeddingProvider unstarted=null;
        synchronized(EmbeddingHelper.class){
            if(engine!=null && owner==server)return;
            previous=engine;engine=null;owner=server;
            try{
                // Selection, path/hash IO and JNI construction all happen lazily on a worker.
                unstarted=new LifecycleEmbeddingProvider(factory);
                engine=new AsyncEmbeddingEngine(unstarted,threads,256,8192,deadline,preparationDeadline);
                unstarted=null; // ownership transferred to the engine
            }catch(Throwable t){
                inactive=new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED,t);
                readiness=CompletableFuture.failedFuture(t);
            }
        }
        // The process lease prevents new factory execution until this old engine has drained.
        if(previous!=null)previous.close();
        if(unstarted!=null)unstarted.close();
    }
    public static EmbeddingProfile profile(){return EmbeddingProfile.current();}
    public static CompletableFuture<Void> readiness(){
        AsyncEmbeddingEngine e=engine;
        // Independent delivery reservations: a foreign callback cannot stall world entry peers.
        return e==null?readiness.copy():e.readiness();
    }
    public static EmbeddingReadiness state(){AsyncEmbeddingEngine e=engine;return e==null?inactive:e.state();}
    public static boolean isReady(){return state().ready();}
    /** Compatibility only. Never call on a server tick/startup thread. */
    public static void awaitReady(){readiness().join();}
    public static void shutdown(){shutdown(null);}
    /** Ignore a late stop event from a server whose replacement already owns the helper. */
    public static void shutdown(Object server){
        AsyncEmbeddingEngine stopped;
        synchronized(EmbeddingHelper.class){
            if(server!=null && owner!=server)return;
            stopped=engine;engine=null;owner=null;
            var failure=new IllegalStateException("Embeddings stopped");
            inactive=new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED,failure);
            readiness=CompletableFuture.failedFuture(failure);
        }
        // Disposal and future delivery never run under the shared lifecycle monitor.
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
