package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.navigation.NaturalLandingPolicy;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Physical premise of natural landing: resting contact is only measured on VISIBLE custom terrain. */
public final class NaturalLandingContactTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static final TerrainMeshFrame.Material STONE = new TerrainMeshFrame.Material("minecraft:stone", Map.of());

    public static void main(String[] args) {
        restingContact();
        airborneAbove();
        fadedTerrainIsNotSupport();
        naturalLandingGateUsesWalkAcquisitionNormal();
        System.out.println("natural landing contact checks: " + checks);
    }

    private static TerrainMeshFrame.Cell cell(long key, Vec3d min, Vec3d axisX, Vec3d axisY, float opacity) {
        return new TerrainMeshFrame.Cell(key, 0, "landmark-a", new Vec3d(0, 0, 0), min, new Vec3d(1, 1, 1),
                axisX, axisY, new Vec3d(0, 0, 1), 0xFF88CC00, 0, opacity, List.of(new Box(0, 0, 0, 1, 1, 1)));
    }

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO, List.of(STONE), List.of(cells));
    }

    /** Exact MeshCollision.ground query a deep player's landing check performs. */
    private static MeshCollision.Hit contact(MeshCollision.Index index, double feetY) {
        return contactAt(index, feetY, .2);
    }

    private static MeshCollision.Hit contactAt(MeshCollision.Index index, double feetY, double minX) {
        Box body = new Box(minX, feetY, .2, minX + .6, feetY + 1.8, .8);
        return index.sweep(body.offset(0, .025, 0), new Vec3d(0, -.15, 0))
                .filter(hit -> hit.normal().y > .3).orElse(null);
    }

    private static void restingContact() {
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 128, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 1f)));
        var hit = contact(index, 129);
        check(hit != null, "a deep player settled on a visible floor measures resting contact");
        check(hit.normal().y >= NaturalLandingPolicy.MIN_GROUND_NORMAL_Y,
                "flat floor contact satisfies the natural-landing normal gate, got " + hit.normal().y);
        // Walk acquisition additionally requires normal.y >= .99; both paths agree on flat floors.
        check(hit.normal().y >= .99, "flat floor contact also admits walk acquisition");
    }

    private static void airborneAbove() {
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 128, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 1f)));
        check(contact(index, 132) == null, "a player three blocks above the floor is airborne, arming natural landing");
        check(contact(index, 129.3) == null, "within half a block of the floor the ground sweep has not armed yet");
    }

    private static void fadedTerrainIsNotSupport() {
        // A fog-faded region has neither rendering nor collision; resting "contact" must never appear.
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 128, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 0f)));
        check(contact(index, 129) == null, "a fully faded floor is invisible and non-solid for landing");
    }

    private static void naturalLandingGateUsesWalkAcquisitionNormal() {
        // A 45-degree ramp (top face tilted around Z): contact exists but its surface normal is
        // far from horizontal-up, so neither natural landing nor walk acquisition may commit.
        double a = Math.sqrt(.5);
        var index = new MeshCollision.Index(frame(cell(1, new Vec3d(0, 127, 0), new Vec3d(a, a, 0), new Vec3d(0, 1, 0), 1f)));
        // The tilted top face satisfies y = 128 + x for x in [0, .7071]. The body stays fully on the
        // face (x in [.05,.65], peak surface 128.65), so the corner-face contact is governed by the
        // face normal, like a real resting player on a slope.
        var hit = contactAt(index, 128.75, .05);
        check(hit != null, "sloped terrain still stops a descending player");
        check(hit.normal().y > .3, "the ramp registers as generic ground contact");
        check(hit.normal().y < NaturalLandingPolicy.MIN_GROUND_NORMAL_Y,
                "steep contact refuses natural landing like walk acquisition, got " + hit.normal().y);
    }
}
