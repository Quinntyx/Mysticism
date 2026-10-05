package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded requests, total queue+inference deadlines, copied caches, and terminal shutdown. */
public final class AsyncEmbeddingEngine implements AutoCloseable {
    private final EmbeddingProvider provider;
    private final ThreadPoolExecutor workers;
    private final long deadlineMillis;
    private final int cacheCapacity;
    private final Map<String,Vec384f> cache=new LinkedHashMap<>(16,0.75f,true);
    private final Map<String,CompletableFuture<Vec384f>> pending=new HashMap<>();
    private final CompletableFuture<Void> readiness=new CompletableFuture<>();
    private boolean closed;
    public AsyncEmbeddingEngine(EmbeddingProvider provider,int threads,int queueCapacity,int cacheCapacity,Duration deadline) {
        if(threads<1 || threads>32 || queueCapacity<1 || cacheCapacity<1 || deadline.isZero() || deadline.isNegative() || deadline.toMillis()<1 || deadline.toMillis()>120000)throw new IllegalArgumentException("Invalid embedding bounds");
        if(!provider.profile().equals(EmbeddingProfile.current()))throw new IllegalArgumentException("Provider profile mismatch");
        this.provider=provider;this.deadlineMillis=deadline.toMillis();this.cacheCapacity=cacheCapacity;
        workers=new ThreadPoolExecutor(threads,threads,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queueCapacity),r->{Thread t=new Thread(r,"Mysticism-EmbedWorker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        readiness.orTimeout(deadlineMillis,TimeUnit.MILLISECONDS);
        workers.execute(()->{try{provider.checkReady();readiness.complete(null);}catch(Throwable t){readiness.completeExceptionally(t);}});
    }
    public CompletableFuture<Void> readiness(){return readiness.copy();}
    public synchronized boolean isReady(){return !closed && readiness.isDone() && !readiness.isCompletedExceptionally();}
    public synchronized CompletableFuture<Vec384f> embed(String descriptor) {
        if(descriptor==null || descriptor.isBlank() || descriptor.length()>8192)return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid descriptor"));
        if(!isReady())return CompletableFuture.failedFuture(new IllegalStateException("Embeddings unavailable; inspect readiness"));
        String key=EmbeddingSpace.FINGERPRINT+":"+descriptor;
        Vec384f cached=cache.get(key);if(cached!=null)return CompletableFuture.completedFuture(cached.clone());
        CompletableFuture<Vec384f> shared=pending.get(key);
        if(shared==null){
            shared=new CompletableFuture<>();pending.put(key,shared);
            CompletableFuture<Vec384f> promise=shared;
            promise.orTimeout(deadlineMillis,TimeUnit.MILLISECONDS).whenComplete((v,e)->{synchronized(this){pending.remove(key,promise);}});
            try{workers.execute(()->{
                if(promise.isDone())return;
                try{
                    Vec384f value=provider.getEmbedding(descriptor);EmbeddingSpace.requireCurrent(value);
                    if(value.length()==0)throw new IllegalArgumentException("Provider returned zero embedding");
                    Vec384f normalized=new Vec384f(value.norm());
                    synchronized(this){if(!closed && !promise.isDone()){cache.put(key,normalized.clone());while(cache.size()>cacheCapacity)cache.remove(cache.keySet().iterator().next());promise.complete(normalized);}}
                }catch(Throwable t){promise.completeExceptionally(t);}
            });}catch(RejectedExecutionException e){promise.completeExceptionally(e);}
        }
        // A caller cannot cancel a shared request or mutate another caller's cached vector.
        CompletableFuture<Vec384f> request=shared;
        return shared.whenComplete((value,error)->{synchronized(this){pending.remove(key,request);}})
                .thenApply(Vec384f::clone);
    }
    public synchronized int cacheSize(){return cache.size();}
    public synchronized int inflightSize(){return pending.size();}
    public void close(){
        synchronized(this){if(closed)return;closed=true;readiness.completeExceptionally(new CancellationException("Embedding shutdown"));for(var f:List.copyOf(pending.values()))f.completeExceptionally(new CancellationException("Embedding shutdown"));pending.clear();cache.clear();}
        workers.shutdownNow();provider.close();
    }
}
