package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import net.minecraft.nbt.NbtCompound;

/** Shared persistence/CCA semantic-space header. Untagged data is always incompatible. */
public final class EmbeddingNbt {
    private EmbeddingNbt(){}
    public static boolean compatible(NbtCompound tag){
        return tag.getInt("embeddingSchema")==EmbeddingSpace.SCHEMA
                && tag.getInt("embeddingDimensions")==EmbeddingSpace.DIMENSIONS
                && tag.getInt("embeddingDescriptorVersion")==EmbeddingSpace.DESCRIPTOR_VERSION
                && EmbeddingSpace.FINGERPRINT.equals(tag.getString("embeddingFingerprint"))
                && EmbeddingSpace.MODEL.equals(tag.getString("embeddingModel"))
                && EmbeddingSpace.REVISION.equals(tag.getString("embeddingRevision"))
                && EmbeddingSpace.SEMANTICS.equals(tag.getString("embeddingSemantics"));
    }
    public static void stamp(NbtCompound tag){
        tag.putInt("embeddingSchema",EmbeddingSpace.SCHEMA);tag.putInt("embeddingDimensions",EmbeddingSpace.DIMENSIONS);
        tag.putInt("embeddingDescriptorVersion",EmbeddingSpace.DESCRIPTOR_VERSION);
        tag.putString("embeddingFingerprint",EmbeddingSpace.FINGERPRINT);tag.putString("embeddingModel",EmbeddingSpace.MODEL);
        tag.putString("embeddingRevision",EmbeddingSpace.REVISION);tag.putString("embeddingSemantics",EmbeddingSpace.SEMANTICS);
    }
}
