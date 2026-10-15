package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.navigation.WalkRequestClock;
import java.util.concurrent.CompletableFuture;
import static io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService.WalkSourceStatus.*;

/** Runs the actual terrain proof classifier and navigation clock; no Fabric/server bootstrap or IO. */
public final class WalkSourceReadinessTest {
    public static void main(String[] args) {
        CompletableFuture<Void> proof = new CompletableFuture<>();
        check(SpiritTerrainService.proofReadiness(true, false, proof) == PENDING, "inflight proof treated as alignment");
        proof.complete(null);
        check(SpiritTerrainService.proofReadiness(true, false, proof) == PENDING, "completion raced server publication");
        check(SpiritTerrainService.proofReadiness(true, true, proof) == READY, "published samples not ready");
        check(SpiritTerrainService.proofReadiness(false, true, proof) == UNAVAILABLE, "stale contact/window proof accepted");
        check(SpiritTerrainService.proofReadiness(true, false, null) == UNAVAILABLE, "no proof reported pending");
        CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(new IllegalStateException("source unavailable"));
        check(SpiritTerrainService.proofReadiness(true, false, failed) == UNAVAILABLE, "failed proof remains pending forever");
        CompletableFuture<Void> cancelled = new CompletableFuture<>(); cancelled.cancel(false);
        check(SpiritTerrainService.proofReadiness(true, false, cancelled) == UNAVAILABLE, "cancelled proof auto-recovered");

        // Contact can exist while its material proof is late. Its absence must not be the only
        // reason the controller pauses the alignment clock (the previous 200-tick failure).
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        CompletableFuture<Void> late = new CompletableFuture<>();
        for (int tick = 0; tick < WalkRequestClock.SOURCE_WAIT_TICKS; tick++) {
            var source = SpiritTerrainService.proofReadiness(true, false, late);
            check(clock.tick(source == READY, source == PENDING) == WalkRequestClock.Phase.WAIT_SOURCE, "contact with slow proof consumed alignment");
        }
        check(clock.tick(false, true) == WalkRequestClock.Phase.EXPIRE_RECOVERABLE, "late proof did not suspend intent");
        late.complete(null);
        check(!clock.recover(SpiritTerrainService.proofReadiness(true, false, late) == READY), "resumed before source publication");
        check(clock.recover(SpiritTerrainService.proofReadiness(true, true, late) == READY), "publication failed to recover without reissuing");
        check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "published proof did not resume bounded alignment");
        check(!clock.recover(true), "publication resumed same intent twice");
        System.out.println("WalkSourceReadinessTest: actual proof readiness/publication and late-recovery regressions passed (no game bootstrap)");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
