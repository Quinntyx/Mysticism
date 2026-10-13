package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.LandmarkMetadata;
import java.util.function.Function;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;

/** Source-grid walking patch with observer-local semantic distortion outside it.
 * This produces geometry, not a renderer-only effect: model vertices, transport and
 * SAT consume the returned columns unchanged. No source coordinates/ownership move. */
final class ShallowTerrainProjection {
    static final double PROTECTED_RADIUS = 5, BLEND_RADIUS = 24, MAX_DISPLACEMENT = 4;
    private ShallowTerrainProjection() {}

    /** Resolve the actual owner only in the producing source namespace, before evaluating its
     * effective embedding. An explicit foreign target is projected in ITS namespace, not admitted
     * as a local owner. Source offsets use the retained source basis; projection uses the observer. */
    static Vec3d ownerDisplacement(String dimension, LandmarkMetadata owner,
                                  Function<LandmarkMetadata, Vec384f> effective,
                                  Vec3d sourceOrigin, Basis384f sourceBasis, Vec384f q,
                                  Basis384f observer, Vec3d player, Vec3d nativeRoot) {
        if (!owner.header().dimension().equals(dimension)) return Vec3d.ZERO;
        var anchor = owner.header().anchor();
        Vec3d offset = sourceOrigin.subtract(anchor.x(), anchor.y(), anchor.z());
        Vec384f location = effective.apply(owner).clone()
                .add(sourceBasis.i.clone().mul((float)(offset.x / SpiritTerrainService.SCALE)))
                .add(sourceBasis.j.clone().mul((float)(offset.y / SpiritTerrainService.SCALE)))
                .add(sourceBasis.k.clone().mul((float)(offset.z / SpiritTerrainService.SCALE)));
        return displacement(location, q, observer, player, nativeRoot);
    }

    /** Project a source window origin as a LOCATION (never normalize a semantic position).
     * The native root is the player's own source/carrier binding, not a shared world anchor. */
    static Vec3d displacement(Vec384f location, Vec384f q, Basis384f observer,
                               Vec3d player, Vec3d nativeRoot) {
        Vec384f delta = location.clone().sub(q);
        Vec3d projected = player.add(delta.dot(observer.i) * SpiritTerrainService.SCALE,
                delta.dot(observer.j) * SpiritTerrainService.SCALE,
                delta.dot(observer.k) * SpiritTerrainService.SCALE);
        Vec3d shift = projected.subtract(nativeRoot);
        double length = shift.length();
        return length > MAX_DISPLACEMENT ? shift.multiply(MAX_DISPLACEMENT / length) : shift;
    }

    static TerrainMeshFrame.Cell distort(TerrainMeshFrame.Cell nativeCell, Vec3d shift,
                                         Vec3d player, double strength) {
        Box bounds = nativeCell.bounds();
        for (Box shape : nativeCell.collision()) bounds = bounds.union(nativeCell.bounds(shape));
        double distance = Math.sqrt(SourceMeshBuilder.distanceSquared(bounds, player));
        if (strength <= 0 || shift.lengthSquared() < 1e-12 || distance <= PROTECTED_RADIUS)
            return nativeCell;
        // Fade the whole-cell protection too: point-only fading would snap its far vertices
        // when the closest model/collision point crosses the protected walking boundary.
        strength = Math.clamp(strength, 0, 1) * blend(distance);
        // Protect the ENTIRE accepted near cell, including compressed cells that straddle the patch.
        // Outside it, approximate the smooth displacement field with one affine parallelepiped.
        // Never transform a model without also transforming its real local slab/stair/fence shapes.
        Vec3d min = warp(nativeCell.min(), shift, player, strength);
        Vec3d x = warp(nativeCell.point(1, 0, 0), shift, player, strength).subtract(min);
        Vec3d y = warp(nativeCell.point(0, 1, 0), shift, player, strength).subtract(min);
        Vec3d z = warp(nativeCell.point(0, 0, 1), shift, player, strength).subtract(min);
        double old = nativeCell.axisX().dotProduct(nativeCell.axisY().crossProduct(nativeCell.axisZ()));
        double next = x.dotProduct(y.crossProduct(z));
        // A malformed/coarse approximation must not invert a model or manufacture collision.
        if (next * old <= 0 || Math.abs(next) < Math.abs(old) * .5) return nativeCell;
        return new TerrainMeshFrame.Cell(nativeCell.key(), nativeCell.material(), nativeCell.landmarkId(),
                nativeCell.sourceMin(), min, nativeCell.size(), x, y, z, nativeCell.color(),
                nativeCell.light(), nativeCell.opacity(), nativeCell.collision());
    }

    private static Vec3d warp(Vec3d point, Vec3d shift, Vec3d player, double strength) {
        return point.add(shift.multiply(blend(point.distanceTo(player)) * strength));
    }

    private static double blend(double distance) {
        double t = Math.clamp((distance - PROTECTED_RADIUS) / (BLEND_RADIUS - PROTECTED_RADIUS), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
