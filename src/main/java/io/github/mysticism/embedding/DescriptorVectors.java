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
    /** Snapshot inputs. Sequential async inference avoids flooding the bounded worker queue. */
    public static CompletableFuture<Vec384f> embed(List<WeightedDescriptor> input){
        List<WeightedDescriptor> parts=List.copyOf(input);
        CompletableFuture<List<WeightedVector>> chain=CompletableFuture.completedFuture(new ArrayList<>());
        for(var part:parts){if(part.weight()==0)continue;chain=chain.thenCompose(vectors->EmbeddingHelper.getEmbedding(part.descriptor()).thenApply(vector->{vectors.add(new WeightedVector(vector,part.weight()));return vectors;}));}
        return chain.thenApply(DescriptorVectors::compose);
    }
}
