package io.github.mysticism.embedding;

/** A definitive missing/changed/invalid model, not per-descriptor overflow or queue pressure.
 * Providers must use this to revoke readiness and prevent cached successes masking model loss. */
public final class EmbeddingModelException extends IllegalStateException {
    public EmbeddingModelException(String message) { super(message); }
    public EmbeddingModelException(String message, Throwable cause) { super(message, cause); }
}
