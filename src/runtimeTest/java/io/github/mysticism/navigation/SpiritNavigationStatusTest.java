package io.github.mysticism.navigation;

import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.dimension.spiritworld.terrain.*;
import io.github.mysticism.vector.Basis384f;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.concurrent.*;

/** Status consumes the same controller facts as runtime; discovery tracks server delivery, not guessed success. */
public final class SpiritNavigationStatusTest {
    private static int checks;
    private static void check(boolean condition, String message) { ++checks; if (!condition) throw new AssertionError(message); }
    private static String describe(SpiritNavigation nav, SourceDiscoveryStatus.Phase phase,
                                   SpiritNavigationStatus.WalkPhase walk, int ticks, String transition) {
        return String.join("\n", SpiritNavigationStatus.describe(nav, true, new SourceDiscoveryStatus(phase, "provider offline"),
                Optional.of(new SpiritTerrainService.SourcePosition("minecraft:overworld", new Vec3d(2.25, 64.5, -3.75), "source-owner")),
                walk, ticks, transition, false));
    }
    private static void status() {
        var nav = new SpiritNavigation(); nav.enterDeep();
        String unready = describe(nav, SourceDiscoveryStatus.Phase.WAITING_EMBEDDINGS, SpiritNavigationStatus.WalkPhase.WAITING_COORDINATE, 80, "Flight requested");
        check(unready.contains("deep flight") && unready.contains("Semantic travel: unavailable"), "Physical flight and semantic readiness must not be conflated");
        check(unready.contains("waiting for real embedding/index readiness") && unready.contains("/myst embed status"), "Actionable actual embedding stage");
        check(unready.contains("NOT current flight location"), "Old source window must never masquerade as current deep location");
        check(unready.contains("waiting for a real semantic coordinate") && unready.contains("4.0s remaining"), "Pending walk reason/time is separate from flight");
        check(unready.contains("Target: unset"), "Default ZERO target is not a captured concept destination");
        nav.anchored(SpiritNavigation.AnchorOrigin.CONCEPT);
        String concept = describe(nav, SourceDiscoveryStatus.Phase.UNOWNED, SpiritNavigationStatus.WalkPhase.NONE, 0, "");
        check(concept.contains("Semantic travel: ready (explicit concept initialization; not landing proof)"), "Concept-ready flight does not claim source acquisition");
        check(concept.contains("no landmark owns") && concept.contains("/spirit discover"), "Ready coordinate and undiscovered/unowned source remain independent");
        check(!concept.contains("waiting for real embedding"), "Completed empty discovery isn't reported as embedding wait");
        String expired = describe(nav, SourceDiscoveryStatus.Phase.READY, SpiritNavigationStatus.WalkPhase.NONE, 0, "Walk request expired; still deep");
        check(expired.contains("Last transition: Walk request expired") && !expired.contains("Walk request:"), "Expired intent isn't displayed as in progress");
        check(expired.contains("toggle flight off") && expired.contains("source-owned terrain"), "Flight shows a concrete, guarded walking action");
        nav.target("minecraft:the_nether", "target-owner", new Vec3d(-2.125, 10.5, 4.875), new Basis384f());
        String captured = describe(nav, SourceDiscoveryStatus.Phase.DISCOVERING, SpiritNavigationStatus.WalkPhase.NONE, 0, "");
        check(captured.contains("captured source feet minecraft:the_nether target-owner feet -2.125 10.5 4.875"), "Status must not floor the real captured slab/fractional feet");
        check(captured.contains("snapshot, not live following"), "Captured destinations remain snapshots");
        nav.setLandingApproach(true);
        check(describe(nav, SourceDiscoveryStatus.Phase.READY, SpiritNavigationStatus.WalkPhase.NONE, 0, "").contains("Captured landing: aligning"), "Captured landing approach is not a current-floor walk request");
        nav.shallow("minecraft:overworld", "source-owner", new Vec3d(2.25, 64.5, -3.75));
        String shallow = describe(nav, SourceDiscoveryStatus.Phase.READY, SpiritNavigationStatus.WalkPhase.NONE, 0, "");
        check(shallow.contains("shallow walking/jumping") && !shallow.contains("NOT current flight location"), "Shallow native pose is distinguishable from retained deep source");
        check(shallow.contains("pending discovery is not exit permission"), "Source status does not bypass current exit ownership/clearance guards");
        nav.clearTarget();
        String outside = String.join("\n", SpiritNavigationStatus.describe(nav, false, new SourceDiscoveryStatus(SourceDiscoveryStatus.Phase.READY, ""),
                Optional.empty(), SpiritNavigationStatus.WalkPhase.NONE, 0, "", true));
        check(outside.contains("outside") && outside.contains("Target: concept coordinate") && !outside.contains("shallow walking"), "Leaving cannot display retained active mode as current runtime mode");
        var noSource = new SpiritNavigation(); noSource.enterDeep();
        String direct = String.join("\n", SpiritNavigationStatus.describe(noSource, true, new SourceDiscoveryStatus(SourceDiscoveryStatus.Phase.NO_SOURCE, ""),
                Optional.empty(), SpiritNavigationStatus.WalkPhase.NONE, 0, "", false));
        check(direct.contains("No source to discover here") && direct.contains("/latent set pos item"), "Direct spirit arrival offers a real coordinate/re-entry action, not an impossible discovery wait");
        String failed = describe(nav, SourceDiscoveryStatus.Phase.FAILED, SpiritNavigationStatus.WalkPhase.NONE, 0, "");
        check(failed.contains("source discovery failed: provider offline") && failed.contains("/spirit discover"), "Real worker failure is surfaced with retry rather than endless awaiting");
    }
    private static void discoveryAttempts() {
        var first = new SourceDiscoveryAttempt(); var second = new SourceDiscoveryAttempt();
        var old = new CompletableFuture<>(); first.begin(old);
        old.complete(Optional.empty());
        check(first.pending(), "Completed worker future remains pending until server-thread delivery");
        boolean rejected = false;
        try { first.begin(new CompletableFuture<>()); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "One bounded discovery per observer including completion-delivery race");
        check(!second.pending() && second.error().isEmpty(), "Another player's pending request cannot leak into status");
        check(first.complete(old, true, null) && first.empty() && !first.pending(), "Empty source result is UNOWNED, not an everlasting wait");
        check(!first.complete(old, false, null) && first.empty(), "Duplicate callbacks cannot rewrite delivered state");
        var next = new CompletableFuture<>(); first.begin(next);
        check(!first.empty() && first.error().isEmpty(), "Explicit retry clears the prior attempt's outcome");
        check(!first.complete(old, false, null) && first.pending(), "Replaced callback cannot deliver an old source");
        first.complete(next, false, new CompletionException(new IllegalStateException("model unavailable\nretry later")));
        check(first.error().equals("IllegalStateException: model unavailable retry later") && !first.empty(), "Unwrap/sanitize real failure; not an empty ownership result");
        var cancelled = new CompletableFuture<>(); first.begin(cancelled); first.cancel();
        check(cancelled.isCancelled() && !first.pending() && first.error().isEmpty(), "Acquisition/exit cancels request and clears ephemeral diagnostics");
        check(!first.complete(cancelled, false, null), "Cancelled callback cannot attach ownership");
        var ready = CompletableFuture.completedFuture(Optional.of("owner")); first.begin(ready); first.complete(ready, false, null);
        check(!first.pending() && !first.empty() && first.error().isEmpty(), "Discovery completion is distinct from exact ownership validation");
    }
    public static void main(String[] args) {
        status(); discoveryAttempts(); System.out.println("PASS navigation diagnostics/discovery: " + checks + " checks");
    }
}
