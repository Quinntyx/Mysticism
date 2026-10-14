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

    public static void main(String[] args) {
        tiltedSupportStartsValidation();
        contactAndPlayerIsolation();
        System.out.println("natural landing trigger checks: " + checks);
    }
}
