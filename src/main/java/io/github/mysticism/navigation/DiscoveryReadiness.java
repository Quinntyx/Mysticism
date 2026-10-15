package io.github.mysticism.navigation;

import io.github.mysticism.embedding.EmbeddingReadiness;
import java.util.Objects;

/** Live inference authorizes NEW discovery; only a published current-profile source
 * anchor authorizes semantic navigation. Neither substitutes for the other. */
public record DiscoveryReadiness(boolean anchored, EmbeddingReadiness model) {
    public DiscoveryReadiness { Objects.requireNonNull(model); }
    public boolean navigationReady() { return anchored; }
    public boolean terminallyBlocked() { return !anchored && model.unavailable(); }
    public String flightDescription() {
        if (anchored) return "Free flight active; semantic source anchor ready.";
        return switch (model.phase()) {
            case INITIALIZING -> "Free flight active; new source discovery waits for model initialization.";
            case READY -> "Free flight active; semantic travel awaits real source ownership publication.";
            case FAILED, STOPPED -> "Free flight active; new source discovery unavailable: " + EmbeddingReadiness.describe(model.failure());
        };
    }
}
