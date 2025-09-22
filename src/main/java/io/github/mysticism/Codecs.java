package io.github.mysticism;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.github.mysticism.vector.Vec384f;

import java.util.stream.IntStream;

public class Codecs {
    public static Codec<Vec384f> VEC384F = Codec.INT_STREAM.flatXmap(
            stream -> DataResult.success(Vec384f.fromBits(stream.toArray())),
            vec -> DataResult.success(IntStream.of(vec.toBits()))
    );
}
