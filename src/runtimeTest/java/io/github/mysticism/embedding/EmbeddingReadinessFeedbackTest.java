package io.github.mysticism.embedding;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual lifecycle gate/diagnostics with a deterministic UI mailbox; no client bootstrap. */
public final class EmbeddingReadinessFeedbackTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        EmbeddingReadinessFeedback<Object> feedback = new EmbeddingReadinessFeedback<>();
        Queue<Runnable> mailbox = new ConcurrentLinkedQueue<>();
        List<Throwable> notices = new ArrayList<>();
        Thread ui = Thread.currentThread();
        Object first = new Object(), second = new Object();
        CompletableFuture<Void> preparing = new CompletableFuture<>();
        AtomicBoolean wrongThread = new AtomicBoolean();
        feedback.watch(first, preparing, mailbox::add, error -> {
            wrongThread.set(Thread.currentThread() != ui); notices.add(error);
        });
        Thread provider = new Thread(() -> preparing.complete(null), "fixture-inference");
        provider.start(); provider.join(2000);
        check(!provider.isAlive() && notices.isEmpty(), "Provider callback accessed UI instead of enqueueing");
        check(mailbox.size() == 1, "Readiness completion not enqueued exactly once");
        mailbox.remove().run();
        check(notices.size() == 1 && notices.getFirst() == null && !wrongThread.get(), "Ready notice wrong thread/value");

        // Completed futures must marshal too (not execute UI code on the server-start caller).
        feedback.watch(first, CompletableFuture.completedFuture(null), mailbox::add, notices::add);
        feedback.stop(first); mailbox.remove().run();
        check(notices.size() == 1, "Queued notice survived world stop");
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        feedback.watch(first, stopped, mailbox::add, notices::add);
        feedback.stop(first); stopped.complete(null); mailbox.remove().run();
        check(notices.size() == 1, "Late native readiness survived world stop");

        CompletableFuture<Void> old = new CompletableFuture<>(), current = new CompletableFuture<>();
        feedback.watch(first, old, mailbox::add, notices::add);
        feedback.watch(second, current, mailbox::add, notices::add);
        feedback.stop(first); // late old-server stop must not invalidate replacement
        old.completeExceptionally(new IllegalStateException("old model failed"));
        Throwable actual = new IllegalStateException("Pinned model checksum mismatch");
        current.completeExceptionally(actual);
        while (!mailbox.isEmpty()) mailbox.remove().run();
        check(notices.size() == 2 && notices.getLast() == actual, "Stale failure/replacement identity gate broken");
        feedback.watch(second, CompletableFuture.failedFuture(actual), mailbox::add, notices::add);
        feedback.clear(); mailbox.remove().run();
        check(notices.size() == 2, "Client stop did not invalidate completed failure");

        // Invalidating the gate also releases the callback that captures the old game/server.
        CompletableFuture<Void> neverReturns = new CompletableFuture<>();
        feedback.watch(first, neverReturns, mailbox::add, notices::add);
        var ticketField = EmbeddingReadinessFeedback.class.getDeclaredField("current"); ticketField.setAccessible(true);
        Object ticket = ticketField.get(feedback);
        var noticeField = ticket.getClass().getDeclaredField("notice"); noticeField.setAccessible(true);
        feedback.stop(first);
        check(noticeField.get(ticket) == null, "Stopped world retained its callback through pending inference");
        check(ticketField.get(feedback) == null, "Stopped world retained its ticket");
        feedback.watch(first, CompletableFuture.completedFuture(null), ignored -> {
            throw new RejectedExecutionException("Fixture UI is closed");
        }, notices::add);
        check(ticketField.get(feedback) == null && notices.size() == 2, "Rejected UI delivery retained world or notified inline");
        feedback.watch(second, CompletableFuture.completedFuture(null), mailbox::add, notices::add);
        mailbox.remove().run();
        check(notices.size() == 3, "Closed executor poisoned later world feedback");

        check(EmbeddingFailures.describe(new CompletionException(new ExecutionException(actual)))
                .equals("IllegalStateException: Pinned model checksum mismatch"), "Actionable cause lost in wrappers");
        check(EmbeddingFailures.describe(new CancellationException("world closed")).contains("world closed"), "Cancellation reason lost");
        check(EmbeddingFailures.describe(new IllegalArgumentException("x".repeat(1000))).length() == 240, "Unbounded player diagnostic");
        check(!EmbeddingFailures.describe(new IllegalStateException("bad\n\r\tasset")).contains("\n"), "Diagnostic has control characters");
        check(EmbeddingFailures.describe(new CompletionException((Throwable) null)).equals("CompletionException"), "Null cause failed");
        check(EmbeddingFailures.describe(null).equals("Unknown failure"), "Null diagnostic failed");
        System.out.println("EmbeddingReadinessFeedbackTest passed: " + checks + " checks");
    }
}
