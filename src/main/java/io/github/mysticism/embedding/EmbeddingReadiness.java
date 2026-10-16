package io.github.mysticism.embedding;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Nonblocking operational snapshot. A completed startup probe is not permanent readiness. */
public record EmbeddingReadiness(Phase phase, Throwable failure) {
    public enum Phase { INITIALIZING, READY, FAILED, STOPPED }
    public EmbeddingReadiness {
        Objects.requireNonNull(phase);
        if ((phase == Phase.FAILED || phase == Phase.STOPPED) != (failure != null))
            throw new IllegalArgumentException("Terminal unavailability must retain its cause");
    }
    public boolean ready() { return phase == Phase.READY; }
    public boolean unavailable() { return failure != null; }
    public String description() {
        return switch (phase) {
            case INITIALIZING -> "Model initializing";
            case READY -> "Model ready";
            case FAILED -> "Model unavailable: " + describe(failure);
            case STOPPED -> "Model stopped: " + describe(failure);
        };
    }
    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }
    public static String describe(Throwable failure) {
        Throwable cause = unwrap(failure);
        // Keep useful nested causes (e.g. ConnectException -> ClosedChannelException) without
        // repeating wrappers or exposing multi-line/oversized messages to Minecraft chat.
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 512 ? message.substring(0, 512) + "…" : message;
    }
}
