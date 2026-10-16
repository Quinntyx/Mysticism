package io.github.mysticism.embedding;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Bounded player-facing diagnostics, retaining the provider's actionable error rather than
 * reporting only CompletionException. Does not alter failure/retry or embedding state. */
public final class EmbeddingFailures {
    private EmbeddingFailures() {}
    public static String describe(Throwable error) {
        if (error == null) return "Unknown failure";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && seen.size() < 16 && seen.add(error) && error.getCause() != null && !seen.contains(error.getCause()))
            error = error.getCause();
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        String text = type + (message == null || message.isBlank() ? "" : ": "
                + message.substring(0, Math.min(240, message.length())).replaceAll("[\\p{Cntrl}]+", " "));
        return text.length() <= 240 ? text : text.substring(0, 239) + "…";
    }
}
