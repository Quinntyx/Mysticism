package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Contact alignment through the production continuous mesh guard, not a component-distance proxy.
 * Source ownership/publication and the flight-mode write still require a live game to validate. */
public final class MeshTransitionAlignmentTest {
    private static int checks;
    private static final Box BODY = new Box(-.3, 0, -.3, .3, 1.8, .3);
    private static final Box UNIT = new Box(0, 0, 0, 1, 1, 1);
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static Vec384f vector(float x, float y, float z) {
        float[] data = Vec384f.ZERO().data(); data[0] = x; data[1] = y; data[2] = z;
        return new Vec384f(data);
    }
    private static Basis384f rotated(double angle) {
        float c = (float)Math.cos(angle), s = (float)Math.sin(angle);
        return new Basis384f(vector(c, s, 0), vector(-s, c, 0), vector(0, 0, 1));
    }
    private static TerrainMeshFrame frame(Vec3d min, Vec3d x, Vec3d y, Vec3d z, List<Box> collision) {
        var cell = new TerrainMeshFrame.Cell(1, 0, "source-floor", Vec3d.ZERO, min,
                new Vec3d(1, 1, 1), x, y, z, 0xffffff, 0, 1, collision);
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cell));
    }
    /** Same source-to-observer dot-product projection as SpiritTerrainService.append. q/feet stay fixed.
     * Initial contact is at the uphill body edge; flattening lowers that surface safely away from the body. */
    private static TerrainMeshFrame floor(Basis384f observer, double sourceFeetY) {
        var x = new Vec3d(observer.i.data()[0], observer.j.data()[0], observer.k.data()[0]);
        var y = new Vec3d(observer.i.data()[1], observer.j.data()[1], observer.k.data()[1]);
        var z = new Vec3d(observer.i.data()[2], observer.j.data()[2], observer.k.data()[2]);
        return frame(x.multiply(-2).add(y.multiply(-1-sourceFeetY)).add(z.multiply(-2)),
                x.multiply(4), y, z.multiply(4), List.of(UNIT));
    }
    private static float error(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }
    private static void contactAlignmentCompletes() {
        for (int publishEvery : new int[]{1, 2}) for (double angle : new double[]{.12, .25, .4, -.12, -.25, -.4}) {
            var current = rotated(angle);
            var alignment = new WalkingModePolicy.Alignment();
            alignment.begin();
            var destination = new Basis384f();
            double sourceFeetY = .3 * Math.abs(Math.sin(angle));
            var visible = floor(current, sourceFeetY);
            check(visible.cells().getFirst().bounds().intersects(BODY), "premise: old tilted AABB overlaps the body");
            check(MeshCollision.bodyClear(visible, BODY), "premise: actual affine floor does not penetrate");
            check(MeshCollision.ground(new MeshCollision.Index(visible), BODY).isPresent(), "premise: real contact exists before alignment");
            int ticks = 0, holds = 0;
            while (error(current, destination) >= 1e-8f && ticks++ < 200) {
                alignment.bind(current, destination, 1, "source-floor", "minecraft:overworld");
                var proposed = alignment.proposed(current, destination);
                var next = floor(proposed, sourceFeetY);
                check(!SupportApproachAlignment.discontinuous(current, proposed), "alignment remains continuous");
                // This is the real guard used by canAlign and acquireCurrentSupport.
                if (!MeshCollision.transitionClear(visible, next, BODY)) {
                    alignment.held(current); holds++;
                    continue;
                }
                check(MeshCollision.bodyClear(next, BODY), "accepted frame remains affine-body clear");
                current = proposed; alignment.applied();
                // Terrain publishes every second tick; canAlign must test against the displayed
                // frame, not assume the previous accepted basis has already replaced collision.
                if (ticks % publishEvery == 0) visible = next;
                check(!alignment.stalled(error(current, destination)), "real progress keeps request live");
            }
            check(error(current, destination) < 1e-8f, "contact alignment reaches source grid before walk expiry; angle=" + angle + ", holds=" + holds);
            check(ticks <= 41, "safe contact alignment is not needlessly ratcheted: " + ticks);
            var acquired = floor(destination, sourceFeetY);
            check(MeshCollision.bodyClear(acquired, BODY) && MeshCollision.transitionClear(visible, acquired, BODY),
                    "final source-grid replacement passes the actual acquisition clearance/transition gates");
            visible = acquired;
            check(MeshCollision.ground(new MeshCollision.Index(visible), BODY).filter(h -> h.normal().y > .99).isPresent(),
                    "real upright support is available for acquisition after alignment");
            Vec3d accepted = MeshCollision.move(new MeshCollision.Index(visible), BODY, new Vec3d(0, -.3, 0), true, 0, false);
            check(accepted.y >= -sourceFeetY-1e-5, "arrival movement cannot tunnel through the aligned displayed floor");
            check(MeshCollision.bodyClear(visible, BODY.offset(accepted)), "arrival preserves body clearance");
        }
    }
    private static void unsafeTransitionsStillRefused() {
        Vec3d x = new Vec3d(.02, 0, 0), y = new Vec3d(0, 3, 0), z = new Vec3d(0, 0, 2);
        var left = frame(new Vec3d(-2, -.5, -1), x, y, z, List.of(UNIT));
        for (double end : new double[]{2, 14, 40}) {
            var right = frame(new Vec3d(end, -.5, -1), x, y, z, List.of(UNIT));
            check(MeshCollision.bodyClear(left, BODY) && MeshCollision.bodyClear(right, BODY), "both thin-wall endpoints are clear");
            check(!MeshCollision.transitionClear(left, right, BODY), "continuous guard refuses an intervening crossing, including off-midpoint contact");
        }
        var distant = frame(new Vec3d(-20, -.5, -1), x, y, z, List.of(UNIT));
        var distantEnd = frame(new Vec3d(40, -.5, -1), x, y, z, List.of(UNIT));
        check(!MeshCollision.transitionClear(distant, distantEnd, BODY),
                "two distant endpoints cannot bypass the swept guard through a near-body shortcut");
        var buried = frame(new Vec3d(0, 0, 0), x, y, z, List.of(UNIT));
        check(!MeshCollision.transitionClear(left, buried, BODY), "unsafe final overlap cannot complete alignment");
        var first = new TerrainMeshFrame(0, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                left.materials(), List.of());
        check(!MeshCollision.transitionClear(first, buried, BODY), "new colliding geometry is refused");
        check(MeshCollision.transitionClear(left, left, BODY), "unchanged distant geometry remains accepted");
    }
    private static void fractionalShapeContact() {
        // A tilted slab can have an overlapping model AABB even though its local collision top is clear.
        var old = frame(new Vec3d(-2, -.980001, -2), new Vec3d(4, .4, 0), new Vec3d(0, 1, 0),
                new Vec3d(0, 0, 4), List.of(new Box(0, .25, 0, 1, .75, 1)));
        var next = frame(new Vec3d(-2, -.980001, -2), new Vec3d(4, .39, 0), new Vec3d(0, 1, 0),
                new Vec3d(0, 0, 4), old.cells().getFirst().collision());
        check(old.cells().getFirst().bounds(old.cells().getFirst().collision().getFirst()).intersects(BODY),
                "premise: fractional slab collision AABB overlaps the body");
        check(MeshCollision.bodyClear(old, BODY) && MeshCollision.ground(new MeshCollision.Index(old), BODY).isPresent(),
                "premise: real fractional affine slab safely supports the body");
        check(MeshCollision.transitionClear(old, next, BODY), "local slab collision, not full rendered model bounds, controls clearance");
    }
    private static Vec3d lerp(Vec3d a, Vec3d b, double t) { return a.multiply(1-t).add(b.multiply(t)); }
    private static void certificatesCoverWholeInterval() {
        var random = new java.util.Random(17);
        int accepted = 0;
        for (int i = 0; i < 128; i++) {
            var from = frame(new Vec3d(random.nextDouble()*6-3, random.nextDouble()*5-2, -.5),
                    new Vec3d(.1, random.nextDouble()*.5, 0), new Vec3d(random.nextDouble()*2-1, 2, 0),
                    new Vec3d(0, 0, 1), List.of(UNIT));
            var to = frame(new Vec3d(random.nextDouble()*6-3, random.nextDouble()*5-2, -.5),
                    new Vec3d(.1, random.nextDouble()*.5, 0), new Vec3d(random.nextDouble()*2-1, 2, 0),
                    new Vec3d(0, 0, 1), List.of(UNIT));
            if (!MeshCollision.transitionClear(from, to, BODY)) continue;
            accepted++;
            var a = from.cells().getFirst(); var b = to.cells().getFirst();
            // Independent SAT spot checks are an additional regression, not the production proof:
            // accepting two endpoint poses (or a midpoint) alone is never sufficient in the guard.
            for (int sample = 0; sample <= 64; sample++) {
                double t = sample/64.0;
                var pose = frame(lerp(a.min(), b.min(), t), lerp(a.axisX(), b.axisX(), t),
                        lerp(a.axisY(), b.axisY(), t), lerp(a.axisZ(), b.axisZ(), t), List.of(UNIT));
                check(MeshCollision.bodyClear(pose, BODY), "a certified sheared transition stays clear throughout its interval");
            }
        }
        check(accepted >= 20, "stress regression exercises enough accepted continuous certificates: " + accepted);
    }
    public static void main(String[] args) {
        contactAlignmentCompletes();
        unsafeTransitionsStillRefused();
        fractionalShapeContact();
        certificatesCoverWholeInterval();
        System.out.println("MeshTransitionAlignmentTest: " + checks + " checks passed");
    }
}
