package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Actual affine collision -> entry stance regressions and production lifetime wiring contracts.
 * Does not boot Minecraft, fabricate embeddings, or claim a live client/server playthrough. */
public final class SupportedWalkingIntegrationTest {
    private static int checks;
    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        supportedEntryUsesRealMeshBeforeOwnership();
        missingMeshContactCannotHoldWalking();
        hiddenGeometryIsNotSupport();
        retainedProofAndCancellationWireUp();
        System.out.println("SupportedWalkingIntegrationTest: " + checks + " checks passed (actual collision + source contracts)");
    }

    private static MeshCollision.Index floor(double x, double y, double z, double height, float opacity) {
        Vec3d origin = new Vec3d(x, y, z);
        var cell = new TerrainMeshFrame.Cell(1, 0, "", origin, origin,
                new Vec3d(1, 1, 1), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1),
                0xffffff, 0, opacity, List.of(new Box(0, 0, 0, 1, height, 1)));
        var frame = new TerrainMeshFrame(1, true, "minecraft:overworld", origin, origin,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cell));
        return new MeshCollision.Index(frame);
    }

    private static boolean grounded(MeshCollision.Index collision, Vec3d feet) {
        Box body = new Box(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + 1.8, feet.z + .3);
        // Same ground sweep as MeshCollision.ground(player).
        return collision.sweep(body.offset(0, .025, 0), new Vec3d(0, -.15, 0))
                .filter(hit -> hit.normal().y > .3).isPresent();
    }

    private static void supportedEntryUsesRealMeshBeforeOwnership() {
        // Integer floor and exact fractional slab feet, including negative coordinates.
        for (double height : new double[]{1, .5}) {
            MeshCollision.Index collision = floor(-18, 64, -35, height, 1);
            Vec3d feet = new Vec3d(-17.5, 64 + height, -34.5);
            check(grounded(collision, feet), "source-identical entry mesh must support exact source feet");
            int unsupported = 0, entrySupportTicks = 0;
            for (int tick = 0; tick < 30; tick++) {
                var step = SpiritNavigationService.entryStance(unsupported, entrySupportTicks, grounded(collision, feet), false, true);
                check(step.stance() == SpiritNavigationService.Stance.HOLD_ENTRY_SUPPORT,
                        "real unowned source floor must keep walking while ownership publishes");
                unsupported = step.unsupportedTicks(); entrySupportTicks = step.entrySupportTicks();
            }
            // The accepted collision frame is unchanged when ownership confirms: no pose/basis snap needed.
            check(grounded(collision, feet), "publishing ownership must not require a replacement floor");
            var lost = SpiritNavigationService.entryStance(0, 0, grounded(collision, feet.add(2, 0, 0)), false, true);
            check(lost.stance() == SpiritNavigationService.Stance.FREE_FALL,
                    "walking off a once-confirmed source floor must not regain entry grace");
        }
    }

    private static void missingMeshContactCannotHoldWalking() {
        MeshCollision.Index collision = floor(0, 0, 0, 1, 1);
        for (Vec3d feet : List.of(new Vec3d(.5, 2, .5), new Vec3d(2, 1, .5))) {
            boolean contact = grounded(collision, feet);
            check(!contact, "hovering/off-edge pose must have no actual mesh support");
            check(SpiritNavigationService.entryStance(0, 0, contact, false, true).stance()
                    == SpiritNavigationService.Stance.FREE_FALL,
                    "zero delta or stale vanilla onGround cannot authorize walking on air");
        }
        check(SpiritNavigationService.entryStance(0, 0, grounded(collision, new Vec3d(.5, 1.05, .5)), true, true).stance()
                == SpiritNavigationService.Stance.WALK, "ascending residual ground contact keeps ordinary jump grace");
    }

    private static void hiddenGeometryIsNotSupport() {
        check(!grounded(floor(0, 0, 0, 1, 0), new Vec3d(.5, 1, .5)),
                "fully faded geometry must not hold a supported walking stance");
    }

    private static String method(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, a + start.length());
        check(a >= 0 && b > a, "missing production method boundary " + start);
        return source.substring(a, b);
    }

    private static void retainedProofAndCancellationWireUp() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java")))
            root = root.getParent();
        check(root != null, "could not locate checkout source root");
        String nav = Files.readString(root.resolve("src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java"));
        String terrain = Files.readString(root.resolve("src/main/java/io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService.java"));
        String expiry = method(nav, "private static void expireSupport(", "public static boolean update(");
        check(!expiry.contains("cancelCurrentSupport"), "expiry must retain already-started terrain proof for retry");
        check(expiry.contains("s.alignment.reset()") && expiry.contains("s.walkIntent = null")
                        && expiry.contains("setSupportApproach(false)"),
                "expiry must clear navigation and client prediction, not stick in pending flight");
        check(expiry.contains("flight(p, true, true)"), "expiry must explicitly acknowledge usable client flight");
        String cancel = method(nav, "private static void endSupportApproach(", "private static void expireSupport(");
        check(cancel.contains("SpiritTerrainService.cancelCurrentSupport(p);")
                        && !cancel.contains("if (s.alignment.pending())"),
                "explicit cancellation must release even a proof retained AFTER expiry");
        String clear = method(nav, "private static void clear(", "private static void sync(");
        check(clear.contains("SpiritTerrainService.cancelCurrentSupport(p);")
                        && !clear.contains("removed.supportPending"),
                "disconnect/respawn cleanup must release retained proof without a live intent");
        String request = method(nav, "public static void onFlightToggle(", "private static void endSupportApproach(");
        check(request.indexOf("s.walk.pending() || request != WalkIntentTracker.FlightRequest.WALK || !s.walk.startWalk()") >= 0
                        && request.indexOf("!s.walk.startWalk()") < request.indexOf("s.alignment.begin()")
                        && request.indexOf("s.alignment.begin()") < request.indexOf("new WalkIntent()"),
                "duplicate flight-off packets must not reset request budgets");
        check(!request.contains("cancelCurrentSupport"), "a retry must reuse retained proof, not restart it");
        String attempt = method(nav, "private static void attemptSupport(", "private static float basisError(");
        check(attempt.indexOf("s.walk.superseded()") >= 0
                        && attempt.indexOf("WalkIntentTracker.attunementSupersedes(s.supportTargetSnapshot") >= 0
                        && attempt.indexOf("WalkIntentTracker.attunementSupersedes(s.supportTargetSnapshot") < attempt.indexOf("if (!state(p).semanticReady())"),
                "invalid retargets must cancel even while late source discovery is pending");
        String discovery = method(attempt, "if (!state(p).semanticReady())", "var found = SpiritTerrainService.currentSupport(p)");
        check(!discovery.contains("SpiritTerrainService.currentSupport(p)"), "no source queries before a real semantic anchor");
        check(attempt.contains("boolean proofPending = SpiritTerrainService.currentSupportProofPending(p, WALK_APPROACH_DEPTH)")
                        && attempt.contains("s.walkIntent.tick(true, support != null, proofPending)")
                        && attempt.contains("destination), proofPending)"),
                "actual source-proof latency must participate in the intent's lifetime");
        check(attempt.contains("acquireCurrentSupport(p, support)") && attempt.contains("s.confirmedOwned = true"),
                "successful walk retry must use real guarded acquisition and end fresh-entry grace");
        String pending = method(terrain, "public static boolean currentSupportProofPending(", "private static Optional<WalkCandidate> walkCandidate(");
        check(pending.contains("MeshCollision.ground(p,depth)") && pending.contains("windowFor(s,hit.get().cell())")
                        && pending.contains("s.walkField==field.identity") && pending.contains("!s.walkFuture.isDone()"),
                "pending validity must be tied to producing geometry in the real approach column and in-flight work");
        check(terrain.contains("s.walkProbe.dimension.equals(dimension)") && terrain.contains("cancelCurrentSupport(p); // Includes proof retained"),
                "source-world unload must clear even a retained proof from a different local source window");
    }
}
