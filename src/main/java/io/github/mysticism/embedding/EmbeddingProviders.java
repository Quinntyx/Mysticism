package io.github.mysticism.embedding;

/** Provider selection performs no model/resource IO. Embedded failure is never an HTTP
 * fallback; an explicitly configured HTTP backend remains available for administrators.
 */
public final class EmbeddingProviders {
    public static final String PROVIDER_PROPERTY = "mysticism.embedding.provider";
    private EmbeddingProviders() {}
    public static EmbeddingProvider create() { return configured(); }
    public static EmbeddingProvider configured() {
        return switch (System.getProperty(PROVIDER_PROPERTY, "embedded")) {
            case "embedded" -> new EmbeddedEmbeddingProvider();
            case "http", "ollama" -> new EmbeddingService();
            default -> throw new IllegalArgumentException(PROVIDER_PROPERTY + " must be embedded, http or ollama");
        };
    }
}
