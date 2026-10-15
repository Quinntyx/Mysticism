package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Regressions for supported walking across deep/shallow switches: entry from supported ground must
 * keep walking (never reset into deep flight), a changing support must re-base the same walk request
 * instead of failing it, expiry must clear transient state, and rotated projection must use actual
 * mesh-inverse feet rather than attracting q to a fictional fixed source coordinate. */
public final class WalkingModePolicyTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static Vec384f axis(int index) {
        float[] v = new float[Vec384f.ZERO().data().length];
        v[index] = 1;
        return new Vec384f(v);
    }
    private static Vec384f unitInPlane(double a, double b) {
        float[] v = new float[Vec384f.ZERO().data().length];
        v[0] = (float) a; v[1] = (float) b;
        return new Vec384f(v).mul(1 / (float) Math.sqrt(a * a + b * b));
    }
    /** The live acquisition gate: joint q/basis scene error projected on the current basis, in
     * physical blocks (semantic error x SCALE=96), must stay under 1e-3 blocks. */
    private static boolean acquisitionGate(Vec384f q, Vec384f coordinate, Basis384f basis) {
        Vec384f error = coordinate.clone().sub(q);
        double x = error.dot(basis.i) * TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT;
        double y = error.dot(basis.j) * TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT;
        double z = error.dot(basis.k) * TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT;
        return x * x + y * y + z * z <= 1e-6;
    }

    private static void shallowMode() {
        // Regression (4): entering from supported ground. Ownership is still resolving (ground
        // pending), the player is standing, no support identity yet: must keep WALK, never DEEP.
        check(WalkingModePolicy.shallowSupport(false, true, false, 0, false, 0)
                == WalkingModePolicy.Shallow.WALK, "pending ground ownership must hold shallow walking");
        for (int tick = 0; tick < WalkingModePolicy.GROUND_PENDING_LIMIT; tick++)
            check(WalkingModePolicy.shallowSupport(false, true, false, 0, false, tick)
                    == WalkingModePolicy.Shallow.WALK, "pending ground ownership must keep holding walking");
        check(WalkingModePolicy.shallowSupport(false, true, false, 0, false, WalkingModePolicy.GROUND_PENDING_LIMIT)
                == WalkingModePolicy.Shallow.DEEP, "never-finishing ownership must release walking at the bound");
        // Real support holds walking too.
        check(WalkingModePolicy.shallowSupport(true, false, false, 7, true, 0)
                == WalkingModePolicy.Shallow.WALK, "real support must hold shallow walking");
        // Walking off an edge: no support, no pending ground, not rising -> immediate deep free flight.
        check(WalkingModePolicy.shallowSupport(false, false, false, 0, false, 0)
                == WalkingModePolicy.Shallow.DEEP, "unsupported standing must fall to deep flight");
        // Ascending takeoff keeps bounded jump grace, then deep.
        check(WalkingModePolicy.shallowSupport(false, false, true, 0, false, 0)
                == WalkingModePolicy.Shallow.JUMP_GRACE, "ascending takeoff gets jump grace");
        for (int tick = 1; tick < 14; tick++)
            check(WalkingModePolicy.shallowSupport(false, false, true, tick, true, 0)
                    == WalkingModePolicy.Shallow.JUMP_GRACE, "jump grace continues while ascending");
        check(WalkingModePolicy.shallowSupport(false, false, true, 14, true, 0)
                == WalkingModePolicy.Shallow.DEEP, "jump grace expires after 14 ticks");
        check(WalkingModePolicy.shallowSupport(false, false, false, 3, true, 0)
                == WalkingModePolicy.Shallow.JUMP_GRACE,
                "jump grace captured at takeoff continues while unsupported");
        check(WalkingModePolicy.shallowSupport(false, false, false, 0, false, 0)
                == WalkingModePolicy.Shallow.DEEP,
                "a fall that never rose never gets grace");
    }

    private static void stallBudget() {
        var alignment = new WalkingModePolicy.Alignment();
        // A request with no convergence progress expires after exactly the bounded window.
        int expiryTick = -1;
        for (int tick = 0; tick < 500; tick++) if (alignment.stalled(5f)) { expiryTick = tick; break; }
        check(expiryTick == WalkingModePolicy.Alignment.STALL_LIMIT,
                "stalled request must expire after exactly the bounded window, expired at " + expiryTick);
        // Regression (3): real convergence progress re-arms the window, so an actively aligning
        // request is never killed mid-approach.
        alignment = new WalkingModePolicy.Alignment();
        float error = 4f;
        for (int tick = 0; tick < 2000; tick++) {
            error = 4f / (1 + tick * 0.01f); // strictly converging, never stalls on an equal value
            check(!alignment.stalled(error), "converging request must not expire");
        }
        // Stalling again after progress still expires.
        alignment = new WalkingModePolicy.Alignment();
        for (int tick = 0; tick < 150; tick++) alignment.stalled(3f - tick * 0.01f);
        boolean expiredAfterProgress = false;
        for (int tick = 0; tick < 500; tick++) { if (alignment.stalled(1f)) { expiredAfterProgress = true; break; } }
        check(expiredAfterProgress, "a request that stops converging must still expire");
    }

    /** Composition regression: an OWNED contact can exist before its real material/clearance
     * proof. Never let the alignment stall timer reintroduce the old 200-tick IO expiry loop. */
    private static void slowProofAndLifetimeCompose() {
        var lifetime = new WalkIntent();
        var alignment = new WalkingModePolicy.Alignment(); alignment.begin();
        for (int tick = 0; tick < 300; tick++)
            check(lifetime.tick(false, false).outcome() != WalkIntent.Outcome.EXPIRED,
                    "late discovery survives the former raw timeout");
        for (int tick = 0; tick < 600; tick++) {
            check(lifetime.tick(true, true, true).outcome() != WalkIntent.Outcome.EXPIRED,
                    "owned contact with slow proof remains pending-valid");
            check(!alignment.stalled(0, true), "already-aligned support must not stall on real proof IO");
        }
        check(alignment.stall == 0, "proof waiting cannot consume or re-arm alignment stall");
        for (int tick = 0; tick < WalkingModePolicy.Alignment.STALL_LIMIT; tick++) {
            check(lifetime.tick(true, true, false).outcome() != WalkIntent.Outcome.EXPIRED,
                    "completed proof permits acquisition under the same request");
            check(!alignment.stalled(0, false), "unsafe acquisition receives only its bounded stall budget");
        }
        check(alignment.stalled(0, false), "settled but unusable aligned support eventually fails truthfully");
        alignment.reset();
        check(alignment.begin(), "expiry leaves walking retryable");
        lifetime = new WalkIntent();
        for (int tick = 0; tick < WalkIntent.TOTAL_TICKS; tick++) {
            check(!alignment.stalled(0, true), "IO never masquerades as alignment stall");
            check(lifetime.tick(true, true, true).outcome() != WalkIntent.Outcome.EXPIRED,
                    "never-finishing proof remains within absolute bound only");
        }
        check(lifetime.tick(true, true, true).reason() == WalkIntent.Reason.UNRESOLVABLE_SUPPORT,
                "proof plus candidate cannot bypass absolute expiry");
        // Rebinding/continued tiny progress can re-arm the alignment budget but NOT the lifetime.
        lifetime = new WalkIntent(); alignment.reset(); alignment.begin();
        for (int tick = 0; tick < WalkIntent.TOTAL_TICKS; tick++) {
            alignment.bind(unitBasis(.3), new Basis384f(), tick, "a", "d");
            check(!alignment.stalled(1f / (tick + 1)), "genuine convergence may re-arm local stall");
            check(lifetime.tick(true, true).outcome() != WalkIntent.Outcome.EXPIRED,
                    "convergence is valid within absolute bound");
        }
        check(lifetime.tick(true, true).reason() == WalkIntent.Reason.UNRESOLVABLE_SUPPORT,
                "tiny progress/rebinding cannot make a request immortal");
    }

    private static void rebinding() {
        var alignment = new WalkingModePolicy.Alignment();
        var source = new Basis384f();
        var current = unitBasis(0.3);
        check(alignment.bind(current, source, 11, "landmark-a", "minecraft:overworld"),
                "first contact must bind the request");
        long window = alignment.window;
        check(!alignment.bind(current, source, window, "landmark-a", "minecraft:overworld"),
                "unchanged support must keep the binding");
        // Regression (3): a changed support window re-bases the SAME request instead of failing it.
        check(alignment.bind(unitBasis(0.5), source, window + 1, "landmark-a", "minecraft:overworld"),
                "changed window must re-base the request");
        check(alignment.alignTick == 0, "re-basing must restart alignment from the live basis");
        check(alignment.bind(unitBasis(0.7), source, alignment.window, "landmark-b", "minecraft:overworld"),
                "changed landmark must re-base the request");
        // A rotated support grid (ownership refresh) also re-bases instead of failing.
        check(alignment.bind(unitBasis(0.9), unitBasis(0.2), alignment.window, "landmark-b", "minecraft:overworld"),
                "changed support basis must re-base the request");
    }

    private static Basis384f unitBasis(double turn) {
        // Deterministic orthonormal basis rotated by `turn` radians from the canonical grid in the
        // e0/e1 plane, exercising real rotated deep-flight frames.
        double cos = Math.cos(turn), sin = Math.sin(turn);
        return new Basis384f(
                unitInPlane(cos, sin),
                unitInPlane(-sin, cos),
                axis(2));
    }

    private static void alignmentBlending() {
        var alignment = new WalkingModePolicy.Alignment();
        var destination = new Basis384f();
        var current = unitBasis(1.2);
        alignment.bind(current, destination, 3, "l", "d");
        // Aligned bases need no blending.
        check(alignment.proposed(destination, destination).i.squareDistance(destination.i) == 0f,
                "aligned basis must be returned unchanged");
        // Steps approach the destination and cap at the last step.
        Basis384f previous = current;
        for (int step = 0; step < WalkingModePolicy.Alignment.ALIGN_STEPS; step++) {
            var proposed = alignment.proposed(previous, destination);
            check(!WalkingModePolicy.Alignment.discontinuous(previous, proposed),
                    "blend steps must stay continuous");
            alignment.applied();
            previous = proposed;
        }
        check(previous.i.squareDistance(destination.i) + previous.j.squareDistance(destination.j)
                + previous.k.squareDistance(destination.k) < 1e-8f, "blend must reach the destination");
        var capped = alignment.proposed(previous, destination);
        check(capped.i.squareDistance(destination.i) == 0f, "completed alignment must hold the destination");
        // A reachable alignment restarts from the live basis; antipodal interpolation must not jump.
        var mid = unitBasis(0.6);
        check(alignment.restartFrom(mid, destination), "reachable alignment must restart from the live basis");
        var antipodal = new Basis384f(destination.i.clone().mul(-1), destination.j, destination.k);
        check(alignment.restartFrom(antipodal, destination),
                "a single minimal blend step is always continuous");
        // The antipodal flip surfaces mid-blend (blend normalization holds one pole until the step
        // crosses the degenerate midpoint), and the controller must detect it there instead of
        // forcing the final basis.
        var live = antipodal;
        boolean detected = false;
        for (int step = 0; step < WalkingModePolicy.Alignment.ALIGN_STEPS; step++) {
            var proposed = alignment.proposed(live, destination);
            if (WalkingModePolicy.Alignment.discontinuous(live, proposed)) { detected = true; break; }
            live = proposed; alignment.applied();
        }
        check(detected, "antipodal alignment must be detected discontinuous before completing");
    }

    private static void requestLifecycle() {
        var request = new WalkingModePolicy.Alignment();
        check(request.begin() && request.pending(), "first flight-off begins a request");
        request.bind(unitBasis(.3), new Basis384f(), 1, "a", "d");
        request.applied(); request.stalled(1);
        check(!request.begin() && request.alignTick == 1 && request.stall == 1,
                "duplicate flight-off must preserve the alignment and deadline");
        for (int tick = 0; tick < 200; tick++) {
            request.begin(); // repeated packets cannot hide expiry
            request.bind(unitBasis(.3), new Basis384f(), tick + 2, "a", "d");
            boolean expired = request.stalled(tick % 2 == 0 ? 2 : 1);
            check(expired == (tick == 199), "oscillation/rebinding must not re-arm the stall budget");
        }
        request.reset(); // same reset used on expiry, cancellation and successful acquisition
        check(!request.pending() && request.from == null && request.grid == null
                && request.dimension.isEmpty() && request.id.isEmpty(), "expiry clears transient request state");
        check(request.begin() && request.stall == 0 && request.alignTick == 0,
                "walking is retryable after expiry, with a fresh budget");
        var other = new WalkingModePolicy.Alignment();
        other.begin(); other.bind(new Basis384f(), new Basis384f(), 5, "b", "d");
        request.reset();
        check(other.pending() && other.window == 5, "another player's intent is independent");
    }

    private static void prediction() {
        var basis = unitBasis(.7);
        var q = axis(3).mul(.03f);
        var original = q.clone();
        WalkingModePolicy.advanceSupport(q, basis, 0, 0, 0);
        check(q.squareDistance(original) == 0, "stationary support approach cannot attract q");
        WalkingModePolicy.advanceSupport(q, basis, Double.NaN, 0, 0);
        WalkingModePolicy.advanceSupport(q, basis, Double.POSITIVE_INFINITY, 0, 0);
        WalkingModePolicy.advanceSupport(q, basis, 4.01, 0, 0);
        check(q.squareDistance(original) == 0, "invalid/teleport delta is not chosen movement");
        var expected = original.clone();
        TraversalSteering.advance(expected, basis, .25, -.02, .15);
        WalkingModePolicy.advanceSupport(q, basis, .25, -.02, .15);
        check(q.squareDistance(expected) == 0, "support prediction is exactly ordinary actual translation");
        check(WalkingModePolicy.Alignment.error(basis, unitBasis(.7)) == 0,
                "support movement cannot rotate the source grid");
    }

    /** Exercise the SAME accepted-cell inverse and SAT used by terrain. Projection moves the
     * source feet when q/basis change; full q-source residual is NOT the acquisition criterion.
     * Orthogonal semantic components must survive walking and must not trigger target homing. */
    private static void rotatedFlightStillWalkable() {
        var source = new Basis384f();
        var q = axis(0).mul(.015f).add(axis(3).mul(.03f));
        var expectedQ = q.clone();
        var observer = new Basis384f(axis(0).mul((float)Math.cos(.3)).add(axis(3).mul((float)Math.sin(.3))),
                axis(1), axis(2));
        var request = new WalkingModePolicy.Alignment(); request.begin();
        request.bind(observer, source, 1, "l", "d");
        Vec3d feet = Vec3d.ZERO;
        TerrainMeshFrame previous = null;
        Vec384f coordinate = null;
        for (int step = 0; step <= WalkingModePolicy.Alignment.ALIGN_STEPS; step++) {
            if (step > 0) {
                feet = feet.add(.05, 0, .025);
                WalkingModePolicy.advanceSupport(q, observer, .05, 0, .025);
                TraversalSteering.advance(expectedQ, observer, .05, 0, .025);
                var proposed = request.proposed(observer, source);
                check(!WalkingModePolicy.Alignment.discontinuous(observer, proposed), "walk alignment stays continuous");
                observer = proposed; request.applied();
            }
            // Production append projection: root = feet + project(window.semantic - q),
            // columns = dot(source axes, observer axes). The window semantic/origin are ZERO.
            Vec3d root = feet.add(project(q.clone().mul(-1), observer));
            Vec3d ax = projectionAxis(source.i, observer), ay = projectionAxis(source.j, observer), az = projectionAxis(source.k, observer);
            Vec3d sourceMin = new Vec3d(-8, -1, -8);
            var cell = new TerrainMeshFrame.Cell(1, 0, "l", sourceMin,
                    root.add(ax.multiply(-8)).add(ay.multiply(-1)).add(az.multiply(-8)),
                    new Vec3d(16, 1, 16), ax.multiply(16), ay, az.multiply(16),
                    -1, 0, 1, List.of(new Box(0, 0, 0, 1, 1, 1)));
            var frame = new TerrainMeshFrame(step, false, "minecraft:overworld", Vec3d.ZERO, feet,
                    List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cell));
            var body = new Box(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + 1.8, feet.z + .3);
            check(MeshCollision.bodyClear(frame, body), "aligned scene does not intersect the body");
            check(MeshCollision.transitionClear(previous, frame, body), "real whole-frame continuity accepts this floor");
            check(new MeshCollision.Index(frame).sweep(body.offset(0, .025, 0), new Vec3d(0, -.15, 0))
                    .filter(hit -> hit.normal().y > .99).isPresent(), "real projected mesh supports the feet");
            Vec3d sourceFeet = cell.sourcePoint(feet);
            coordinate = Vec384f.ZERO();
            TraversalSteering.advance(coordinate, source, sourceFeet.x, sourceFeet.y, sourceFeet.z);
            previous = frame;
        }
        check(WalkingModePolicy.Alignment.error(observer, source) < WalkingModePolicy.Alignment.ALIGNED,
                "rotated support basis aligns completely");
        check(acquisitionGate(q, coordinate, observer), "LIVE inverse support satisfies the projected 1mm acquisition gate");
        check(q.squareDistance(coordinate) > 1e-4,
                "unprojected semantic residual must remain real, not be artificially converged");
        check(q.squareDistance(expectedQ) == 0,
                "walking preserves every semantic component except exact movement-driven translation");
    }

    private static Vec3d project(Vec384f v, Basis384f observer) {
        return projectionAxis(v, observer).multiply(TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT);
    }
    private static Vec3d projectionAxis(Vec384f v, Basis384f observer) {
        return new Vec3d(v.dot(observer.i), v.dot(observer.j), v.dot(observer.k));
    }

    private static void modeSwitchState() {
        var nav = new SpiritNavigation();
        var other = new SpiritNavigation();
        var target = new Vec3d(17.25, 63.5, -2.125);
        nav.target("minecraft:overworld", "captured", target, unitBasis(.1));
        nav.shallow("minecraft:overworld", "current", Vec3d.ZERO); nav.setSemanticReady(true);
        long epoch = nav.motionEpoch();
        nav.enterDeep(); nav.setSupportApproach(true);
        check(nav.deep() && nav.supportApproach() && nav.motionEpoch() > epoch,
                "deep/support transition resets prediction baseline");
        epoch = nav.motionEpoch();
        nav.setSupportApproach(true);
        check(nav.motionEpoch() == epoch, "duplicate support flag cannot reset prediction");
        nav.setSupportApproach(false); // expiry stays deep, not a spurious shallow toggle
        check(nav.deep() && !nav.supportApproach(), "expiry leaves deep mode retryable");
        nav.setSupportApproach(true);
        nav.shallow("minecraft:overworld", "actual-support", new Vec3d(.25, 64.5, -.125));
        check(!nav.deep() && !nav.supportApproach(), "successful switch clears pending flight intent");
        check(nav.targetPosition().equals(target) && nav.targetLandmarkId().equals("captured"),
                "current support never overwrites the captured destination");
        check(!other.active(), "another player's mode is untouched");
    }

    public static void main(String[] args) {
        shallowMode();
        stallBudget();
        slowProofAndLifetimeCompose();
        rebinding();
        alignmentBlending();
        requestLifecycle();
        prediction();
        rotatedFlightStillWalkable();
        modeSwitchState();
        System.out.println("WalkingModePolicyTest passed: " + checks + " checks");
    }
}
