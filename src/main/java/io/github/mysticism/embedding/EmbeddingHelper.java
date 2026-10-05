package io.github.mysticism.embedding;

import io.github.mysticism.vector.Vec384f;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Shared asynchronous provider lifecycle. Initialization never blocks the server thread. */
public final class EmbeddingHelper {
    private static volatile AsyncEmbeddingEngine engine;
    private static volatile CompletableFuture<Void> readiness=CompletableFuture.failedFuture(new IllegalStateException("Embeddings not started"));
    private static volatile String failure="Not started";
    private EmbeddingHelper(){}
    public static void initializeServer(){initializeServer(Integer.getInteger("mysticism.embedding.workers",2));}
    public static synchronized void initializeServer(int threads){
        if(engine!=null)return;
        EmbeddingProvider provider=null;
        try{
            provider=new EmbeddingService();
            engine=new AsyncEmbeddingEngine(provider,threads,256,8192,Duration.ofMillis(Long.getLong("mysticism.embedding.deadlineMillis",30000)));
            readiness=engine.readiness();failure="Initializing";
            readiness.whenComplete((v,e)->{failure=e==null?"Ready":"Unavailable: "+e;});
        }catch(Throwable t){
            if(provider!=null)try{provider.close();}catch(Throwable closing){t.addSuppressed(closing);}
            failure="Unavailable: "+t;readiness=CompletableFuture.failedFuture(t);
        }
    }
    public static EmbeddingProfile profile(){return EmbeddingProfile.current();}
    public static CompletableFuture<Void> readiness(){return readiness.copy();}
    public static boolean isReady(){AsyncEmbeddingEngine e=engine;return e!=null && e.isReady();}
    /** Compatibility only. Never call on a server tick/startup thread. */
    public static void awaitReady(){readiness.join();}
    public static synchronized void shutdown(){AsyncEmbeddingEngine e=engine;engine=null;if(e!=null)e.close();readiness=CompletableFuture.failedFuture(new IllegalStateException("Embeddings stopped"));failure="Stopped";}
    public static CompletableFuture<Vec384f> getEmbedding(String descriptor){AsyncEmbeddingEngine e=engine;return e==null?CompletableFuture.failedFuture(new IllegalStateException("Embeddings unavailable")):e.embed(descriptor);}
    public static Optional<Vec384f> getEmbeddingBlocking(String descriptor){try{return Optional.of(getEmbedding(descriptor).get());}catch(InterruptedException e){Thread.currentThread().interrupt();return Optional.empty();}catch(Exception e){return Optional.empty();}}
    public static CompletableFuture<Vec384f> composeDescriptors(List<DescriptorVectors.WeightedDescriptor> descriptors){return DescriptorVectors.embed(descriptors);}
    public static String getInitializationStatus(){return failure;}
    public static int getCacheSize(){AsyncEmbeddingEngine e=engine;return e==null?0:e.cacheSize();}
    public static int getInflightSize(){AsyncEmbeddingEngine e=engine;return e==null?0:e.inflightSize();}
}
