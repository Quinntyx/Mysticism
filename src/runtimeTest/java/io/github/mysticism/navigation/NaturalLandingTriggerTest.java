package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.github.mysticism.vector.Basis384f;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real affine mesh contact -> production navigation session/cadence, without booting a world. */
public final class NaturalLandingTriggerTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static final Vec3d STILL = Vec3d.ZERO;

    private static Optional<MeshCollision.Hit> contact(double degrees, float opacity) {
        double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
        // A source-flat cube viewed in a rotated observer grid. The AABB rests on the high
        // edge of its top face, not at a fabricated horizontal replacement floor.
        var cell = new TerrainMeshFrame.Cell(1, 0, "source-owner", Vec3d.ZERO, Vec3d.ZERO,
                new Vec3d(1, 1, 1), new Vec3d(c, s, 0), new Vec3d(-s, c, 0), new Vec3d(0, 0, 1),
                0xffffff, 0, opacity, List.of(new Box(0, 0, 0, 1, 1, 1)));
        var material = new TerrainMeshFrame.Material("minecraft:stone", Map.of());
        var frame = new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                List.of(material), List.of(cell));
        double feetY = .65 * Math.tan(angle) + 1 / c;
        return MeshCollision.ground(new MeshCollision.Index(frame), new Box(.05, feetY, .2, .65, feetY + 1.8, .8));
    }

    private static NaturalLandingPolicy.Action tick(SpiritNavigationService.Session session,
            Optional<MeshCollision.Hit> ground, Vec3d movement) {
        return SpiritNavigationService.naturalLandingAction(session, true, false, ground, movement);
    }

    private static void tiltedSupportStartsValidation() {
        var ground = contact(10, 1);
        check(ground.isPresent(), "real tilted source-flat terrain supports a resting AABB");
        check(Math.abs(ground.orElseThrow().normal().y - Math.cos(Math.toRadians(10))) < 1e-6,
                "regression uses the actual 10-degree affine surface normal");
        check(ground.orElseThrow().normal().y < .99, "tilted support cannot yet pass final walking acquisition");
        var session = new SpiritNavigationService.Session();
        check(tick(session, Optional.empty(), STILL) == NaturalLandingPolicy.Action.NONE && session.airborne,
                "real airtime arms this player's deep stretch");
        for (int i = 0; i < NaturalLandingPolicy.REST_TICKS - 1; i++)
            check(tick(session, ground, STILL) == NaturalLandingPolicy.Action.NONE,
                    "tilted resting contact waits for the full consecutive cadence: " + i);
        check(tick(session, ground, STILL) == NaturalLandingPolicy.Action.LAND,
                "resting airborne player must initiate discovery/alignment from tilted support");
        check(session.restingTicks == 0 && !session.supportPending,
                "the contact seam resets cadence but grants no walking or acquisition authority");
        session.supportPending = true; // caller now owns one bounded support approach
        for (int i = 0; i < NaturalLandingPolicy.REST_TICKS + 2; i++)
            check(tick(session, ground, STILL) == NaturalLandingPolicy.Action.NONE,
                    "pending natural approach never duplicates requests");
    }

    private static void contactAndPlayerIsolation() {
        var ground = contact(10, 1);
        var standing = new SpiritNavigationService.Session();
        var flying = new SpiritNavigationService.Session();
        tick(flying, Optional.empty(), STILL);
        for (int i = 0; i < NaturalLandingPolicy.REST_TICKS; i++) {
            check(tick(standing, ground, STILL) == NaturalLandingPolicy.Action.NONE,
                    "another player's airborne arm never reverses standing takeoff");
            tick(flying, ground, STILL);
        }
        check(!standing.airborne && standing.restingTicks == 0, "landing readiness remains per player");
        var session = new SpiritNavigationService.Session();
        tick(session, Optional.empty(), STILL);
        for (int i = 0; i < NaturalLandingPolicy.REST_TICKS - 1; i++) tick(session, ground, STILL);
        check(tick(session, ground, new Vec3d(0, .02, 0)) == NaturalLandingPolicy.Action.NONE
                && session.restingTicks == 0, "ascending takeoff resets the tilted contact cadence");
        check(tick(session, ground, STILL) == NaturalLandingPolicy.Action.NONE && session.restingTicks == 1,
                "rest starts a new full window after ascent");
        check(contact(10, 0).isEmpty(), "invisible terrain offers no landing contact");
        tick(session, contact(10, 0), STILL);
        check(session.restingTicks == 0, "lost or faded contact resets accumulated rest");
        session.blendTo = new Basis384f();
        session.restingTicks = NaturalLandingPolicy.REST_TICKS;
        check(tick(session, ground, STILL) == NaturalLandingPolicy.Action.NONE,
                "tilted support cannot interrupt a touch blend");
        session.blendTo = null;
        check(SpiritNavigationService.naturalLandingAction(session, true, true, ground, STILL)
                == NaturalLandingPolicy.Action.NONE, "tilted contact cannot divert a captured landing approach");
    }

    private static void asyncRecoveryCannotDuplicateNaturalRequests() {
        var ground = contact(10, 1);
        var suspended = new SpiritNavigationService.Session();
        var other = new SpiritNavigationService.Session();
        tick(suspended, Optional.empty(), STILL); tick(other, Optional.empty(), STILL);
        suspended.walk.reissue();
        for (int i = 0; i <= WalkRequestClock.SOURCE_WAIT_TICKS; i++) suspended.walk.tick(false, true);
        check(suspended.walk.armed() && !suspended.supportPending,
                "late-source suspension releases active alignment but retains exactly one intent");
        for (int i = 0; i < NaturalLandingPolicy.REST_TICKS * 2; i++)
            check(tick(suspended, ground, STILL) == NaturalLandingPolicy.Action.NONE,
                    "suspended request must not reissue a natural request or reset recovery allowance");
        for (int i = 0; i < NaturalLandingPolicy.REST_TICKS - 1; i++) tick(other, ground, STILL);
        check(tick(other, ground, STILL) == NaturalLandingPolicy.Action.LAND,
                "one player's suspended intent cannot prevent another player's natural landing");
        check(suspended.walk.recover(true), "actual late readiness resumes one existing request");
        suspended.supportPending = true;
        check(tick(suspended, ground, STILL) == NaturalLandingPolicy.Action.NONE,
                "resumed request remains exclusive with natural landing");
        check(suspended.walk.recoveries() == 1 && !suspended.walk.recover(true),
                "same ready geometry does not duplicate the request");
    }

    private static void expiredRecoveryReleasesAsyncResources() {
        var expired = new SpiritNavigationService.Session();
        var other = new SpiritNavigationService.Session();
        expired.supportTargetSnapshot = io.github.mysticism.vector.Vec384f.ZERO();
        other.supportTargetSnapshot = io.github.mysticism.vector.Vec384f.ZERO();
        expired.walk.reissue(); other.walk.reissue();
        for (int i = 0; i <= WalkRequestClock.SOURCE_WAIT_TICKS; i++) expired.walk.tick(false, true);
        for (int i = 0; i <= WalkRequestClock.RECOVERY_WAIT_TICKS; i++) expired.walk.recover(false);
        check(!expired.walk.armed() && !expired.supportPending,
                "deadline clears active/armed flags before resource teardown");
        int[] released = {0};
        SpiritNavigationService.releaseSupportResources(expired, () -> released[0]++);
        check(released[0] == 1, "expired intent must cancel retained async discovery/proof despite cleared flags");
        check(other.supportTargetSnapshot != null && other.walk.recoveries() == 0,
                "expired player cannot release another player's frozen target or proof");
        expired.supportTargetSnapshot = null; // endSupportApproach clears this immediately after cancellation
        SpiritNavigationService.releaseSupportResources(expired, () -> released[0]++);
        check(released[0] == 1, "completed teardown has no retained resources to cancel again");
    }

    public static void main(String[] args) {
        expiredRecoveryReleasesAsyncResources();
        asyncRecoveryCannotDuplicateNaturalRequests();
        tiltedSupportStartsValidation();
        contactAndPlayerIsolation();
        System.out.println("natural landing trigger checks: " + checks);
    }
}
