package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.Objects;
import java.util.concurrent.*;

/** One observer's source request, pending THROUGH server-thread delivery, not just worker completion. */
public final class SourceDiscoveryAttempt {
    private CompletableFuture<?> future;
    private boolean pending, empty;
    private String error = "";
    public boolean pending() { return pending; }
    public boolean empty() { return empty; }
    public String error() { return error; }
    public CompletableFuture<?> future() { return future; }
    public void begin(CompletableFuture<?> request) {
        if (pending) throw new IllegalStateException("Source discovery already pending");
        future = Objects.requireNonNull(request); pending = true; empty = false; error = "";
    }
    /** Reject replaced/cancelled callbacks; a worker completion is not source ownership proof. */
    public boolean complete(CompletableFuture<?> request, boolean noOwner, Throwable failure) {
        if (!pending || future != request) return false;
        pending = false; empty = failure == null && noOwner;
        error = failure == null ? "" : failureDetail(failure);
        return true;
    }
    public void cancel() {
        var previous = future; future = null; pending = false; empty = false; error = "";
        if (previous != null) previous.cancel(false);
    }
    public static String failureDetail(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof CompletionException || failure instanceof ExecutionException))
            failure = failure.getCause();
        String message = failure.getMessage();
        String detail = (failure.getClass().getSimpleName() + ": " + (message == null ? "no details" : message))
                .replace('\n', ' ').replace('\r', ' ');
        return detail.substring(0, Math.min(240, detail.length()));
    }
}
