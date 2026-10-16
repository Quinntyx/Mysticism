package io.github.mysticism.navigation;

import io.github.mysticism.embedding.*;
import java.util.concurrent.CompletionException;

/** The same readiness policy used by real navigation admission/cancellation. */
public final class DiscoveryReadinessSelfTest {
    public static void main(String[] args) {
        int checks = 0;
        var initializing = new EmbeddingReadiness(EmbeddingReadiness.Phase.INITIALIZING, null);
        var ready = new EmbeddingReadiness(EmbeddingReadiness.Phase.READY, null);
        var cause = new EmbeddingModelException("Pinned native v2 model missing\nNo weights installed");
        var failed = new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED, cause);
        var stopped = new EmbeddingReadiness(EmbeddingReadiness.Phase.STOPPED, cause);
        for (var model : new EmbeddingReadiness[]{initializing, ready, failed, stopped}) {
            var undiscovered = new DiscoveryReadiness(false, model);
            if (undiscovered.navigationReady()) throw new AssertionError("An executor/model flag is not a published source anchor"); checks++;
            if (undiscovered.terminallyBlocked() != model.unavailable()) throw new AssertionError("Only terminal unavailability rejects/cancels pending walking"); checks++;
            var grounded = new DiscoveryReadiness(true, model);
            if (!grounded.navigationReady() || grounded.terminallyBlocked()) throw new AssertionError("Current-profile persisted anchors do not require fresh inference"); checks++;
        }
        if (!new DiscoveryReadiness(false, initializing).flightDescription().contains("initialization")) throw new AssertionError("Initializing diagnosis"); checks++;
        if (!new DiscoveryReadiness(false, ready).flightDescription().contains("ownership publication")) throw new AssertionError("A ready model alone never claims source readiness"); checks++;
        String message = new DiscoveryReadiness(false, failed).flightDescription();
        if (!message.contains("Pinned native v2 model missing") || message.contains("\n")) throw new AssertionError("Actionable bounded single-line failure"); checks++;
        if (!EmbeddingReadiness.describe(new CompletionException(cause)).equals(EmbeddingReadiness.describe(cause))) throw new AssertionError("Do not hide provider cause behind future wrappers"); checks++;
        try { new EmbeddingReadiness(EmbeddingReadiness.Phase.READY, cause); throw new AssertionError("Invalid readiness state accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { new EmbeddingReadiness(EmbeddingReadiness.Phase.FAILED, null); throw new AssertionError("Lost model cause accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("DiscoveryReadinessSelfTest passed: " + checks + " checks");
    }
}
