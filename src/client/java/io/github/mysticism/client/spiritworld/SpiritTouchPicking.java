package io.github.mysticism.client.spiritworld;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Projected interaction geometry only. Never query carrier blocks or world entities. */
public final class SpiritTouchPicking {
    public static final double REACH = 4.0;
    public record Peer(UUID id, Box bounds) {}
    public record Touch(UUID id, Vec3d point) {}

    private SpiritTouchPicking() {}

    public static Optional<Touch> pick(TerrainMeshFrame mesh, List<Peer> peers,
            Vec3d from, Vec3d direction, double reach) {
        if (!finite(from) || !finite(direction) || !Double.isFinite(reach) || reach <= 0
                || direction.lengthSquared() < 1e-12) return Optional.empty();
        Vec3d end = from.add(direction.normalize().multiply(Math.min(REACH, reach)));
        Touch selected = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Peer peer : peers) {
            var hit = peer.bounds().contains(from) ? Optional.of(from) : peer.bounds().raycast(from, end);
            if (hit.isEmpty()) continue;
            double distance = hit.get().squaredDistanceTo(from);
            if (distance < nearest) {
                nearest = distance;
                selected = new Touch(peer.id(), hit.get());
            }
        }
        return selected != null && clearMeshRay(mesh, from, selected.point())
                ? Optional.of(selected) : Optional.empty();
    }

    /** Inverse-affine ray against the very same LOCAL shapes used by mesh collision. */
    public static boolean clearMeshRay(TerrainMeshFrame frame, Vec3d from, Vec3d to) {
        if (!finite(from) || !finite(to)) return false;
        Box rayBounds = new Box(from, to).expand(.0005);
        for (var cell : frame.cells()) {
            if (cell.opacity() <= 0 || cell.collision().isEmpty()) continue;
            // Collision may protrude beyond the unit baked block model. Do not cull it
            // against cell.bounds(), which covers only [0,1]^3.
            boolean intersects = false;
            for (Box shape : cell.collision()) if (cell.bounds(shape).intersects(rayBounds)) {
                intersects = true;
                break;
            }
            if (!intersects) continue;
            Vec3d r0 = cell.axisY().crossProduct(cell.axisZ());
            double determinant = cell.axisX().dotProduct(r0);
            if (Math.abs(determinant) < 1e-9) return false; // visible degenerate surface: fail closed
            Vec3d r1 = cell.axisZ().crossProduct(cell.axisX());
            Vec3d r2 = cell.axisX().crossProduct(cell.axisY());
            Vec3d a = from.subtract(cell.min()), b = to.subtract(cell.min());
            Vec3d localA = new Vec3d(a.dotProduct(r0) / determinant, a.dotProduct(r1) / determinant,
                    a.dotProduct(r2) / determinant);
            Vec3d localB = new Vec3d(b.dotProduct(r0) / determinant, b.dotProduct(r1) / determinant,
                    b.dotProduct(r2) / determinant);
            for (var shape : cell.collision())
                if (shape.contains(localA) || shape.raycast(localA, localB).isPresent()) return false;
        }
        return true;
    }

    private static boolean finite(Vec3d point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
