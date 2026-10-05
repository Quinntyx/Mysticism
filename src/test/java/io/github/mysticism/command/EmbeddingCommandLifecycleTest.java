package io.github.mysticism.command;

import io.github.mysticism.vector.Vec384f;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the actual command lifecycle/admission gate, with no fake server/world APIs. */
public final class EmbeddingCommandLifecycleTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var queries = new EmbeddingCommand.Queries<Object>();
        Object server = new Object(), otherServer = new Object(); UUID id = UUID.randomUUID();
        var first = new CompletableFuture<Vec384f>(); var next = new CompletableFuture<Vec384f>();
        check(queries.track(server, id, first), "Admitted");
        first.whenComplete((v, error) -> check(!queries.finish(server, id, first), "Reentrant cancellation removed replacement"));
        check(queries.track(server, id, next), "Replacement admitted");
        check(first.isCancelled() && queries.size(server) == 1, "Replacement leaked");
        check(!queries.finish(server, id, first), "Stale callback consumed new query");
        check(queries.finish(server, id, next) && queries.size(server) == 0, "Completion leaked");
        var elsewhere = new CompletableFuture<Vec384f>();
        queries.track(otherServer, id, elsewhere);
        var ids = new ArrayList<UUID>();
        var pending = new ArrayList<CompletableFuture<Vec384f>>();
        for (int i = 0; i < EmbeddingCommand.Queries.MAX_PER_SERVER; i++) {
            UUID player = UUID.randomUUID(); ids.add(player);
            var f = new CompletableFuture<Vec384f>(); pending.add(f);
            check(queries.track(server, player, f), "Premature budget rejection");
        }
        check(!queries.track(server, UUID.randomUUID(), new CompletableFuture<>()), "Unbounded admission");
        check(queries.cancel(server, ids.getFirst()), "Disconnect/dimension/respawn must cancel");
        check(pending.getFirst().isCancelled(), "Lifecycle request still pending");
        check(!queries.finish(server, ids.getFirst(), pending.getFirst()), "Late lifecycle result accepted");
        var reopened = new CompletableFuture<Vec384f>();
        check(queries.track(server, ids.getFirst(), reopened), "Canceled slot not recovered");
        queries.stop(server);
        check(queries.size(server) == 0 && reopened.isCancelled(), "Shutdown leaked");
        check(pending.stream().allMatch(CompletableFuture::isCancelled), "Outstanding caller not canceled");
        check(queries.size(otherServer) == 1 && !elsewhere.isDone(), "Shutdown crossed server lifecycle");
        queries.stop(server); check(queries.cancel(otherServer, id), "Other server cleanup failed");
        check(queries.size(otherServer) == 0, "Other server leaked");
        System.out.println("EmbeddingCommandLifecycleTest passed: " + checks + " checks");
    }
}
