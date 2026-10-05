package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;

/** Staging helper. Activations/callbacks run on the server thread; inference never does. */
public final class IndexGeneration {
    private IndexGeneration(){}
    public record Generation(KnnIndex index,Map<String,String> descriptors){}
    public static CompletableFuture<Generation> build(MinecraftServer server,Map<String,String> descriptors){
        Map<String,String> canonical=Collections.unmodifiableMap(new TreeMap<>(descriptors));
        SimpleKnnIndex staged=new SimpleKnnIndex();
        CompletableFuture<Void> chain=EmbeddingHelper.readiness();
        for(var entry:canonical.entrySet())chain=chain.thenCompose(ignored->EmbeddingHelper.getEmbedding(entry.getValue()).thenAccept(vector->staged.upsert(entry.getKey(),vector)));
        return chain.thenCompose(ignored->{
            CompletableFuture<Generation> activated=new CompletableFuture<>();
            if(!server.isRunning()){activated.completeExceptionally(new IllegalStateException("Server stopped before activation"));return activated;}
            // Service shutdown cancels outstanding work; no server thread waits for inference.
            server.execute(()->{if(!server.isRunning())activated.completeExceptionally(new IllegalStateException("Server stopped before activation"));else activated.complete(new Generation(staged,canonical));});
            return activated.orTimeout(5,TimeUnit.SECONDS);
        });
    }
}
