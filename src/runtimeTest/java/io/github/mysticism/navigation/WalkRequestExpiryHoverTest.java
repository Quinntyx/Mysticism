package io.github.mysticism.navigation;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Merged-scope regressions for the walk-request lifecycle: a request that ends without
 * acquisition (stall, budget expiry, bounded acquire failures) must leave a stable usable
 * hover delivered to the controlling client, never drifting residual flight, and the merged
 * shallow decision must expose exactly one airborne policy (the physical landing envelope).
 * Offline contract checks only: no live server, terrain or client. */
public final class WalkRequestExpiryHoverTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void budgetShape() {
        check(WalkPolicy.LANDING_ENVELOPE > 0, "Landing envelope must be a positive step height");
        check(WalkPolicy.AIR_GRACE_TICKS > 0, "Air budget must be bounded and positive");
        check(WalkPolicy.STALL_CANCEL_TICKS > 0, "Stall cancellation must be bounded");
        check(WalkPolicy.MAX_PENDING_TICKS > WalkPolicy.STALL_CANCEL_TICKS,
                "Total request budget outlives a stall so slow real validations still finish");
        check(WalkPolicy.ACQUIRE_RETRY_TICKS > 0 && WalkPolicy.MAX_ACQUIRE_FAILURES > 1,
                "Acquire retries must be paced and bounded, never a per-tick busy loop");
        check(WalkPolicy.SLIDE_FRACTION > 0 && WalkPolicy.SLIDE_FRACTION < 1,
                "Residual slide converges by a bounded fraction without overshoot");
        check(WalkPolicy.worldErrorSquared(Vec384f.ZERO(), new Basis384f()) == 0,
                "A zero semantic residual has zero world error");
    }

    private static void elapsedExpiry() throws Exception {
        long started = 100;
        int stall = 0, contactTicks = 0;
        for (int elapsed = 0; elapsed <= WalkPolicy.MAX_PENDING_TICKS; elapsed++) {
            // This intermittent pattern never reaches either the old stall or contact budget.
            boolean contact = elapsed % 199 == 198;
            stall = contact ? 0 : stall + 1;
            if (contact) contactTicks++;
            check(stall <= WalkPolicy.STALL_CANCEL_TICKS, "Fixture must evade the missing-support stall budget");
            check(WalkPolicy.requestExpired(started, started + elapsed) == (elapsed >= WalkPolicy.MAX_PENDING_TICKS),
                    "Intermittent/missing support must not extend elapsed intent lifetime");
        }
        check(contactTicks < 10, "Fixture would have kept the old contact-only request pending for hours");
        check(!WalkPolicy.requestExpired(1300, 1300), "A fresh request must receive a fresh budget");
        check(WalkPolicy.requestExpired(started, started + 10000), "Skipped validation ticks must still expire elapsed intent");
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java"));
        int attempt = service.indexOf("private static void attemptSupport");
        int expiry = service.indexOf("WalkPolicy.requestExpired(s.supportStartedTick", attempt);
        int lookup = service.indexOf("SpiritTerrainService.currentSupport(p)", attempt);
        check(expiry > attempt && expiry < lookup, "Elapsed expiry must run before missing support/readiness can return");
        check(service.indexOf("if (s.supportPending || s.walk.armed()) return;") < service.indexOf("s.supportStartedTick = p.getServer().getTicks()"),
                "Repeated entry packets must not renew the request deadline");
    }

    private static void singleAirborneDecision() {
        // Supported and pending phases never convert; only AIRBORNE reaches the envelope decision.
        check(WalkPolicy.phase(true, false, false, false) == WalkPolicy.SupportPhase.SUPPORTED,
                "Semantic support is SUPPORTED regardless of mesh ground");
        check(WalkPolicy.phase(false, true, true, false) == WalkPolicy.SupportPhase.PENDING,
                "Ownership-pending mesh ground stays PENDING, not a takeoff");
        check(WalkPolicy.phase(false, true, false, true) == WalkPolicy.SupportPhase.PENDING,
                "A blank but grounded source id stays PENDING while ownership publishes");
        check(WalkPolicy.phase(false, false, true, false) == WalkPolicy.SupportPhase.AIRBORNE,
                "No physical ground is AIRBORNE even while ownership is pending");
        // The superseded rising-latch boundary must not be layered back onto the envelope decision.
        check(WalkPolicy.takeoffDeep(1, false), "Falling without takeoff intent converts within the legacy boundary");
        check(WalkPolicy.takeoffDeep(WalkPolicy.JUMP_GRACE_TICKS + 1, true), "Legacy grace bounds an ascending takeoff");
        check(!WalkPolicy.takeoffDeep(1, true), "Legacy grace still spares an early ascending tick");
    }

    private static void navigationSeams() throws Exception {
        Class<?> navigation = Class.forName("io.github.mysticism.navigation.SpiritNavigationService");
        // Merged constants must be backed by the shared policy, not redefined locally.
        check(SpiritNavigationService.LANDING_ENVELOPE == WalkPolicy.LANDING_ENVELOPE,
                "Navigation landing envelope delegates to WalkPolicy");
        check(SpiritNavigationService.AIR_GRACE_TICKS == WalkPolicy.AIR_GRACE_TICKS,
                "Navigation air budget delegates to WalkPolicy");
        Method hold = navigation.getDeclaredMethod("holdShallow", boolean.class, double.class, double.class, int.class);
        check(Modifier.isStatic(hold.getModifiers()) && !Modifier.isPublic(hold.getModifiers()),
                "holdShallow stays the package-private merged shallow decision");
        check(SpiritNavigationService.holdShallow(true, 64, 64, WalkPolicy.AIR_GRACE_TICKS),
                "holdShallow matches WalkPolicy.landingEnvelopeHold (clamped body holds)");
        check(!SpiritNavigationService.holdShallow(false, 64 - WalkPolicy.LANDING_ENVELOPE - .01, 64, 1),
                "holdShallow matches WalkPolicy.landingEnvelopeHold (envelope breach converts)");

        // The expiry hover seam: every no-acquisition exit delivers a stop through one method.
        Method hover = navigation.getDeclaredMethod("deliverStableHover", ServerPlayerEntity.class,
                Class.forName("io.github.mysticism.navigation.SpiritNavigationService$Session"));
        check(Modifier.isStatic(hover.getModifiers()) && !Modifier.isPublic(hover.getModifiers()),
                "deliverStableHover stays the package-private expiry delivery seam");

        // The merged evolver entry keeps both delta roles and the single-delta compatibility path.
        check(navigation.getDeclaredMethod("update", ServerPlayerEntity.class, Vec3d.class).getReturnType() == boolean.class,
                "Single-delta update overload stays available for pre-split callers");
        check(navigation.getDeclaredMethod("update", ServerPlayerEntity.class, Vec3d.class, Vec3d.class).getReturnType() == boolean.class,
                "Two-delta update keeps physical decisions separate from semantic integration");
    }

    public static void main(String[] args) throws Exception {
        budgetShape();
        elapsedExpiry();
        singleAirborneDecision();
        navigationSeams();
        System.out.println("WalkRequestExpiryHoverTest: " + checks + " checks passed (merged walk-request expiry/hover contracts)");
    }
}
