package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.navigation.DeepWalkAttachment;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Physical premise of deep walk attachment, measured against the real swept-AABB mesh index:
 * the contacts a moving deep walker grazes split into acquisition-compatible floors (normal .99+)
 * and steep contacts that the old ungated attachment locked onto even though walk acquisition's
 * own normal gate can never accept them. */
public final class DeepWalkContactPremiseTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final TerrainMeshFrame.Material STONE = new TerrainMeshFrame.Material("minecraft:stone", Map.of());

    public static void main(String[] args) {
        flatFloorIsAttachable();
        steepGrazeIsGroundButNotAttachable();
        gentleRampIsAttachable();
        System.out.println("deep walk contact premise checks: " + checks);
    }

    private static TerrainMeshFrame.Cell cell(long key, Vec3d min, Vec3d axisX, Vec3d axisY, float opacity) {
        return new TerrainMeshFrame.Cell(key, 0, "landmark-a", new Vec3d(0, 0, 0), min, new Vec3d(1, 1, 1),
                axisX, axisY, new Vec3d(0, 0, 1), 0xFF88CC00, 0, opacity, List.of(new Box(0, 0, 0, 1, 1, 1)));
    }

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO, List.of(STONE), List.of(cells));
    }

    /** Exact MeshCollision.ground query a deep walker's support candidate performs. */
    private static MeshCollision.Hit ground(MeshCollision.Index index, double feetY, double minX) {
        Box body = new Box(minX, feetY, .2, minX + .6, feetY + 1.8, .8);
        return index.sweep(body.offset(0, .025, 0), new Vec3d(0, -.15, 0))
                .filter(hit -> hit.normal().y > .3).orElse(null);
    }

    private static void flatFloorIsAttachable() {
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 128, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 1f)));
        var hit = ground(index, 129, .2);
        check(hit != null, "a deep walker resting on a visible floor measures ground contact");
        check(hit.normal().y >= DeepWalkAttachment.MIN_WALK_NORMAL_Y,
                "flat floor contact may anchor a deep walk request, got " + hit.normal().y);
        check(DeepWalkAttachment.evaluate(false, false, true, hit.normal().y) == DeepWalkAttachment.Decision.ATTACH,
                "the real flat-floor contact attaches a moving deep walker's request");
    }

    private static void steepGrazeIsGroundButNotAttachable() {
        // 45-degree ramp (top face tilted around Z): the generic ground filter still reports the
        // graze a moving deep flyer makes, but walk acquisition refuses normal.y < .99, so the old
        // ungated first-contact lock anchored requests that could never complete.
        double a = Math.sqrt(.5);
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 127, 0), new Vec3d(a, a, 0), new Vec3d(0, 1, 0), 1f)));
        var hit = ground(index, 128.75, .05);
        check(hit != null, "the ramp registers as generic ground contact for a moving deep flyer");
        check(hit.normal().y > .3, "the graze passes the ground filter that feeds support candidates");
        check(hit.normal().y < DeepWalkAttachment.MIN_WALK_NORMAL_Y,
                "steep graze is not acquisition-compatible, got " + hit.normal().y);
        check(DeepWalkAttachment.evaluate(false, false, true, hit.normal().y) == DeepWalkAttachment.Decision.WAIT,
                "the policy keeps the request pending instead of locking onto the unacquirable graze");
        check(DeepWalkAttachment.evaluate(true, false, true, hit.normal().y) == DeepWalkAttachment.Decision.WAIT,
                "a previously attached request is not cancelled by a transient steep graze while moving");
    }

    private static void gentleRampIsAttachable() {
        // An 8-degree ramp: normal.y = cos(8) ≈ .9903 stays above the .99 acquisition boundary, so
        // attachment follows the real sloped surface; 10 degrees (normal.y ≈ .9848) would not.
        double angle = Math.toRadians(8);
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 127, 0),
                new Vec3d(Math.cos(angle), Math.sin(angle), 0), new Vec3d(0, 1, 0), 1f)));
        // The standing face spans the tilted source axis and Z; its top surface is y = 128 + x·tan(8°),
        // reaching 128.091 under the body's right edge. A body resting at 128.2 sweeps down to 128.075.
        var hit = ground(index, 128.2, .05);
        check(hit != null, "the gentle ramp stops a descending deep walker");
        check(hit.normal().y >= DeepWalkAttachment.MIN_WALK_NORMAL_Y,
                "gentle slope contact is acquisition-compatible, got " + hit.normal().y);
        check(DeepWalkAttachment.evaluate(false, false, true, hit.normal().y) == DeepWalkAttachment.Decision.ATTACH,
                "the real gentle-slope contact attaches a moving deep walker's request");
    }
}
