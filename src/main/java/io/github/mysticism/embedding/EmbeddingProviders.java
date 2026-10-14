package io.github.mysticism.embedding;

/** Explicit selection: an endpoint property alone must never turn local inference into HTTP. */
public final class EmbeddingProviders {
    private EmbeddingProviders() {}
    public static EmbeddingProvider create() {
        return switch (System.getProperty("mysticism.embedding.provider", "embedded")) {
            case "embedded" -> new EmbeddedEmbeddingService();
            case "ollama" -> new EmbeddingService();
            default -> throw new IllegalArgumentException("mysticism.embedding.provider must be embedded or ollama");
        };
    }
}
