package io.github.mysticism.vector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Single semantic-space contract. Configure before loading any mod classes. */
public final class EmbeddingSpace {
    public static final int DIMENSIONS = 256;
    public static final int NATIVE_DIMENSIONS = 768;
    public static final int SCHEMA = 2;
    public static final int DESCRIPTOR_VERSION = 2;
    public static final String MODEL = System.getProperty("mysticism.embedding.model", "nomic-embed-text-v2-moe:latest");
    // Verified official Ollama manifest; tags must resolve to exactly this immutable revision.
    public static final String REVISION = System.getProperty("mysticism.embedding.revision",
            "ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965");
    public static final String SEMANTICS = "nomic-v2-moe|tokenizer=model-manifest|pooling=mean|task=search_document: |matryoshka=first256|normalization=l2|descriptor=" + DESCRIPTOR_VERSION;
    public static final String FINGERPRINT = fingerprint(MODEL + "|" + REVISION + "|" + DIMENSIONS + "|" + SEMANTICS);
    private EmbeddingSpace() {}
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new ExceptionInInitializerError(e); }
    }
    public static void requireCurrent(Vec384f vector) {
        if (vector == null || !FINGERPRINT.equals(vector.fingerprint())) throw new IllegalArgumentException("Incompatible embedding profile");
    }
}
