package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.util.*;
import java.util.concurrent.*;

/** Reusable weighted block/biome/activity descriptor API; no landmark ownership or geometry. */
public final class DescriptorVectors {
    private DescriptorVectors(){}
    public record WeightedDescriptor(String descriptor,double weight){
        public WeightedDescriptor{Objects.requireNonNull(descriptor);validateWeight(weight);if(descriptor.isBlank())throw new IllegalArgumentException("Empty descriptor");}
    }
    public record WeightedVector(Vec384f vector,double weight){
        public WeightedVector{EmbeddingSpace.requireCurrent(vector);validateWeight(weight);vector=vector.clone();}
        @Override public Vec384f vector(){return vector.clone();}
    }
    private static void validateWeight(double weight){if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("Weights must be finite and nonnegative");}
    /** L2-normalized weighted mean of individually normalized native descriptors.
     * Scale weights before accumulating: a finite weight distribution need not have
     * a representable total. The common mean divisor cancels in final L2, so keep
     * the compensated sum in double precision until normalization, not float first.
     */
    public static Vec384f compose(List<WeightedVector> input) {
        List<WeightedVector> parts = List.copyOf(input);
        double scale = 0;
        for (var part : parts) scale = Math.max(scale, part.weight());
        if (scale == 0) throw new IllegalArgumentException("Positive weight required");
        double[] sums = new double[EmbeddingSpace.DIMENSIONS];
        double[] compensation = new double[sums.length];
        for (var part : parts) {
            if (part.weight() == 0) continue;
            Vec384f vector = part.vector();
            EmbeddingSpace.requireCurrent(vector);
            float[] normalized = NativeEmbeddingOutput.normalize(vector.data()).data();
            double weight = part.weight() / scale;
            for (int i = 0; i < sums.length; i++) {
                double value = normalized[i] * weight;
                double previous = sums[i], next = previous + value;
                compensation[i] += Math.abs(previous) >= Math.abs(value)
                        ? (previous - next) + value : (value - next) + previous;
                sums[i] = next;
            }
        }
        for (int i = 0; i < sums.length; i++) sums[i] += compensation[i];
        // Reject exact cancellation, not a finite nonzero mean below an arbitrary epsilon.
        return NativeEmbeddingOutput.normalize(sums);
    }
    /** Snapshot inputs. Sequential async inference avoids flooding the bounded worker queue. */
    public static CompletableFuture<Vec384f> embed(List<WeightedDescriptor> input){
        List<WeightedDescriptor> parts=List.copyOf(input);
        CompletableFuture<List<WeightedVector>> chain=CompletableFuture.completedFuture(new ArrayList<>());
        for(var part:parts){if(part.weight()==0)continue;chain=chain.thenCompose(vectors->EmbeddingHelper.getEmbedding(part.descriptor()).thenApply(vector->{vectors.add(new WeightedVector(vector,part.weight()));return vectors;}));}
        return chain.thenApply(DescriptorVectors::compose);
    }
}
