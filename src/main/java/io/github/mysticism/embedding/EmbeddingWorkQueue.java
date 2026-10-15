package io.github.mysticism.embedding;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-thread bounded admission. Model-independent source reads/topology never sit behind
 * a pending model probe. Terminal model errors drain dependent requests, not physical work. */
public final class EmbeddingWorkQueue<T> {
    private record Entry<T>(T work, boolean needsModel, CompletableFuture<?> result, Consumer<Throwable> reject) {}
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();
    private final int capacity;
    public EmbeddingWorkQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive work capacity required");
        this.capacity = capacity;
    }
    public void offer(T work, boolean needsModel, boolean priority, CompletableFuture<?> result, Consumer<Throwable> reject,
                      EmbeddingReadiness readiness) {
        Objects.requireNonNull(work); Objects.requireNonNull(result); Objects.requireNonNull(reject);
        if (result.isDone()) return;
        if (needsModel && readiness.unavailable()) { reject.accept(readiness.failure()); return; }
        entries.removeIf(entry -> entry.result.isDone());
        if (entries.size() >= capacity) { reject.accept(new RejectedExecutionException("source request budget")); return; }
        var entry = new Entry<>(work, needsModel, result, reject);
        if (priority) entries.addFirst(entry); else entries.addLast(entry);
    }
    /** Detach before future delivery: a source callback may reenter admission. */
    public void settle(EmbeddingReadiness readiness) {
        var failures = new ArrayDeque<Entry<T>>();
        entries.removeIf(entry -> {
            if (entry.result.isDone()) return true;
            if (entry.needsModel && readiness.unavailable()) { failures.add(entry); return true; }
            return false;
        });
        for (var entry : failures) entry.reject.accept(readiness.failure());
    }
    public T poll(EmbeddingReadiness readiness) {
        settle(readiness);
        var iterator = entries.iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.needsModel || readiness.ready()) { iterator.remove(); return entry.work; }
        }
        return null;
    }
    public void cancelWhere(Predicate<T> affected, Throwable failure) {
        var failures = new ArrayDeque<Entry<T>>();
        entries.removeIf(entry -> { if (!affected.test(entry.work)) return false; failures.add(entry); return true; });
        for (var entry : failures) entry.reject.accept(failure);
    }
    public int size() { return entries.size(); }
}
