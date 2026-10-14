package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Reusable weighted block/biome/activity descriptor API; no landmark ownership or geometry. */
public final class DescriptorVectors {
    private DescriptorVectors(){}
    // Bound whole compositions, not just their brief submission tasks. Keep callback capacity
    // available for the individual descriptor requests that these compositions depend on.
    static final int COMPOSITION_CAPACITY = 8;
    private static final Semaphore COMPOSITION_SLOTS = new Semaphore(COMPOSITION_CAPACITY);
    private static final ThreadPoolExecutor COMPOSITIONS = new ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread thread = new Thread(r, "Mysticism-EmbedComposition"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { COMPOSITIONS.allowCoreThreadTimeOut(true); }
    public record WeightedDescriptor(String descriptor,double weight){
        public WeightedDescriptor{Objects.requireNonNull(descriptor);validateWeight(weight);if(descriptor.isBlank())throw new IllegalArgumentException("Empty descriptor");}
    }
    public record WeightedVector(Vec384f vector,double weight){
        public WeightedVector{EmbeddingSpace.requireCurrent(vector);validateWeight(weight);vector=vector.clone();}
        @Override public Vec384f vector(){return vector.clone();}
    }
    private static void validateWeight(double weight){if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("Weights must be finite and nonnegative");}
    /** L2-normalized weighted mean of individually normalized descriptors, stable caller order. */
    public static Vec384f compose(List<WeightedVector> input){
        List<WeightedVector> parts=List.copyOf(input);double total=0;
        for(var part:parts)total+=part.weight();
        if(!Double.isFinite(total)||total<=0)throw new IllegalArgumentException("Positive finite total weight required");
        double[] sums=new double[EmbeddingSpace.DIMENSIONS];
        for(var part:parts){Vec384f vector=part.vector();EmbeddingSpace.requireCurrent(vector);if(part.weight()==0)continue;
            if(vector.length()==0)throw new IllegalArgumentException("Zero descriptor vector");
            float[] normalized=vector.norm();for(int i=0;i<sums.length;i++)sums[i]+=normalized[i]*(part.weight()/total);
        }
        float[] values=new float[sums.length];for(int i=0;i<values.length;i++)values[i]=(float)sums[i];
        Vec384f result=new Vec384f(values);if(result.length()<1e-12f)throw new IllegalArgumentException("Weighted descriptors cancel to zero");
        return new Vec384f(result.norm());
    }
    /** Snapshot inputs and one provider generation. Cache hits must not turn weighted composition
     * into server/render tick work. Sequential inference still avoids flooding the model queue. */
    public static CompletableFuture<Vec384f> embed(List<WeightedDescriptor> input){
        return EmbeddingHelper.composeDescriptors(input);
    }
    static CompletableFuture<Vec384f> embed(List<WeightedDescriptor> input,
                                            Function<String, CompletableFuture<Vec384f>> inference){
        List<WeightedDescriptor> parts=List.copyOf(input);
        return AsyncEmbeddingEngine.offThread(cancelled -> {
            if(cancelled.getAsBoolean()) throw new CancellationException("Embedding composition cancelled");
            if(!COMPOSITION_SLOTS.tryAcquire())
                throw new RejectedExecutionException("Embedding composition capacity exhausted; retry later");
            try {
                CompletableFuture<List<WeightedVector>> chain=CompletableFuture.completedFuture(new ArrayList<>());
                for(var part:parts){if(part.weight()==0)continue;
                    // Copy/normalize each descriptor on our worker as well: inference delivery
                    // callbacks only enqueue this work, they do not perform vector arithmetic.
                    chain=chain.thenCompose(vectors->{
                        if(cancelled.getAsBoolean()) throw new CancellationException("Embedding composition cancelled");
                        return inference.apply(part.descriptor()).thenApplyAsync(vector->{
                            vectors.add(new WeightedVector(vector,part.weight()));return vectors;
                        }, COMPOSITIONS);
                    });
                }
                return chain.thenApplyAsync(vectors->{
                    if(cancelled.getAsBoolean()) throw new CancellationException("Embedding composition cancelled");
                    return compose(vectors);
                }, COMPOSITIONS)
                        .whenComplete((value,error)->COMPOSITION_SLOTS.release());
            } catch(Throwable error){COMPOSITION_SLOTS.release();throw error;}
        }, COMPOSITIONS);
    }
}
