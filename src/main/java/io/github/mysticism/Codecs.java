package io.github.mysticism;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.github.mysticism.vector.Vec384f;

import java.util.stream.IntStream;

public class Codecs {
    public static Codec<Vec384f> VEC384F = Codec.INT_STREAM.flatXmap(
            stream -> {
                try { return DataResult.success(Vec384f.fromBits(stream.toArray())); }
                catch (IllegalArgumentException error) { return DataResult.error(error::getMessage); }
            },
            vec -> {
                try { io.github.mysticism.vector.EmbeddingSpace.requireCurrent(vec); return DataResult.success(IntStream.of(vec.toBits())); }
                catch (IllegalArgumentException error) { return DataResult.error(error::getMessage); }
            }
    );
}
