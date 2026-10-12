package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.navigation.DeepWalkAttachment;
import io.github.mysticism.vector.Basis384f;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Real mesh contact normals, source-space classification and guarded changing-basis alignment.
 * No game/server bootstrap: these are the production geometry, policy, blend and transition guard. */
public final class DeepWalkContactPremiseTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static final TerrainMeshFrame.Material STONE = new TerrainMeshFrame.Material("minecraft:stone", Map.of());
    private static final Box BODY = new Box(-.18, 128.04, .2, .42, 129.84, .8);
    private static final Vec3d DOWN = new Vec3d(0, -.15, 0);

    public static void main(String[] args) {
        guardedAlignmentFollowsTiltedFloors();
        upwardProjectedWallDoesNotAttach();
        sourceNormalUsesCovectorPullback();
        blockedTransitionDoesNotAdvance();
        System.out.println("deep walk contact premise checks: " + checks);
    }

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell cell) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO, List.of(STONE), List.of(cell));
    }

    private static Basis384f rotated(double degrees) {
        Basis384f source = new Basis384f();
        float c = (float)Math.cos(Math.toRadians(degrees)), s = (float)Math.sin(Math.toRadians(degrees));
        return new Basis384f(source.i.clone().mul(c).sub(source.j.clone().mul(s)),
                source.i.clone().mul(s).add(source.j.clone().mul(c)), source.k);
    }

    /** Source columns projected by the real evolving observer basis. Keep the far/high top vertex
     * fixed just outside the body footprint, so the conservative transition guard has clearance
     * while the ground probe still reaches the tilted top face (not an AABB corner normal). */
    private static TerrainMeshFrame projected(Basis384f observer, String owner) {
        Basis384f source = new Basis384f();
        Vec3d x = new Vec3d(source.i.dot(observer.i), source.i.dot(observer.j), source.i.dot(observer.k));
        Vec3d y = new Vec3d(source.j.dot(observer.i), source.j.dot(observer.j), source.j.dot(observer.k));
        Vec3d z = new Vec3d(source.k.dot(observer.i), source.k.dot(observer.j), source.k.dot(observer.k));
        Vec3d min = new Vec3d(.5, 128, 0).subtract(x).subtract(y);
        return frame(new TerrainMeshFrame.Cell(1, 0, owner, Vec3d.ZERO, min, new Vec3d(1, 1, 1),
                x, y, z, 0xFF88CC00, 0, 1, List.of(new Box(0, 0, 0, 1, 1, 1))));
    }

    /** Exact ground probe/filter used by currentSupport. */
    private static MeshCollision.Hit ground(TerrainMeshFrame frame, Box body) {
        return new MeshCollision.Index(frame).sweep(body.offset(0, .025, 0), DOWN)
                .filter(hit -> hit.normal().y > .3).orElseThrow(() -> new AssertionError("fixture lost ground contact"));
    }

    private static void guardedAlignmentFollowsTiltedFloors() {
        Basis384f destination = new Basis384f(), current = rotated(35), from = null;
        String attachedOwner = "";
        int accepted = 0, follows = 0;
        double firstNormal = ground(projected(current, "a"), BODY).normal().y;
        check(firstNormal < .99 && firstNormal > .3, "regression must START with a tilted projected contact");
        for (int tick = 0; tick < 55; tick++) {
            // Cross a producing locality before source alignment has completed.
            String owner = tick < 15 ? "a" : "b";
            TerrainMeshFrame before = projected(current, owner);
            MeshCollision.Hit hit = ground(before, BODY);
            Vec3d sourceNormal = hit.cell().sourceNormal(hit.normal());
            check(sourceNormal.y > .999999, "the changing projected contact remains a source floor");
            var decision = DeepWalkAttachment.evaluate(from != null, owner.equals(attachedOwner), true,
                    hit.normal().y, sourceNormal.y);
            if (tick == 0) check(decision == DeepWalkAttachment.Decision.ATTACH, "tilted floor starts alignment instead of WAIT");
            else if (tick == 15) {
                check(hit.normal().y < .99, "FOLLOW regression also precedes projected acquisition eligibility");
                check(decision == DeepWalkAttachment.Decision.FOLLOW, "moving request follows the next tilted source locality");
                follows++;
            } else check(decision == DeepWalkAttachment.Decision.KEEP, "normal changes do not reset attached alignment");
            if (decision == DeepWalkAttachment.Decision.ATTACH || decision == DeepWalkAttachment.Decision.FOLLOW) {
                from = current.clone(); attachedOwner = owner; accepted = 0;
            }
            Basis384f proposed = TraversalSteering.blend(from, destination, (accepted + 1) / 40f);
            check(current.i.squareDistance(proposed.i) <= .01f && current.j.squareDistance(proposed.j) <= .01f
                    && current.k.squareDistance(proposed.k) <= .01f, "every basis step meets production discontinuity guard");
            TerrainMeshFrame next = projected(proposed, owner);
            check(MeshCollision.bodyClear(next, BODY), "proposed frame does not penetrate the player");
            check(MeshCollision.transitionClear(before, next, BODY), "real continuous transition guard accepts each step");
            current = proposed; accepted++;
        }
        check(follows == 1 && attachedOwner.equals("b"), "attachment retained the actual second locality");
        check(accepted == 40, "FOLLOW restarted only alignment; second floor completed forty accepted steps");
        check(current.i.squareDistance(destination.i) + current.j.squareDistance(destination.j)
                + current.k.squareDistance(destination.k) < 1e-8, "guarded alignment reached the source grid");
        double finalNormal = ground(projected(current, "b"), BODY).normal().y;
        check(finalNormal > firstNormal && DeepWalkAttachment.walkable(finalNormal),
                "measured projected normal changed into final acquisition eligibility");
        check(!DeepWalkAttachment.walkable(firstNormal), "attachment never authorizes premature tilted acquisition");
    }

    private static void upwardProjectedWallDoesNotAttach() {
        TerrainMeshFrame frame = projected(rotated(85), "wall");
        // To the right of the highest vertex: downward travel contacts the source +X face.
        MeshCollision.Hit hit = ground(frame, BODY.offset(.76, 0, 0));
        Vec3d sourceNormal = hit.cell().sourceNormal(hit.normal());
        check(hit.normal().y >= .99, "a source WALL can masquerade as a walkable projected floor");
        check(Math.abs(sourceNormal.y) < 1e-6 && sourceNormal.x > .999999, "pullback identifies the actual source wall");
        check(DeepWalkAttachment.evaluate(false, false, true, hit.normal().y, sourceNormal.y)
                == DeepWalkAttachment.Decision.WAIT, "upward-facing source wall never steals initial attachment");
        check(DeepWalkAttachment.evaluate(true, false, true, hit.normal().y, sourceNormal.y)
                == DeepWalkAttachment.Decision.WAIT, "unsupported wall does not FOLLOW an attached request");
    }

    private static void sourceNormalUsesCovectorPullback() {
        TerrainMeshFrame.Cell cell = new TerrainMeshFrame.Cell(2, 0, "sheared", Vec3d.ZERO, Vec3d.ZERO,
                new Vec3d(2, 4, 1), new Vec3d(2, 2, 0), new Vec3d(0, 4, 0), new Vec3d(0, 0, 1),
                0, 0, 1, List.of(new Box(0, 0, 0, 1, 1, 1)));
        Vec3d floor = cell.sourceNormal(new Vec3d(-1, 1, 0).normalize());
        check(floor.squaredDistanceTo(new Vec3d(0, 1, 0)) < 1e-12, "sheared source floor uses transpose, not inverse position math");
        Vec3d oblique = cell.sourceNormal(new Vec3d(1, 0, 1).normalize());
        check(oblique.squaredDistanceTo(new Vec3d(1, 0, 1).normalize()) < 1e-12, "pullback accounts for nonuniform source cell sizes");
        check(cell.sourceNormal(Vec3d.ZERO).equals(Vec3d.ZERO), "degenerate normal fails closed");
        check(cell.sourceNormal(new Vec3d(Double.NaN, 0, 0)).equals(Vec3d.ZERO), "nonfinite normal fails closed");
    }

    private static void blockedTransitionDoesNotAdvance() {
        Basis384f current = rotated(35), proposed = TraversalSteering.blend(current, new Basis384f(), 1 / 40f);
        TerrainMeshFrame before = projected(current, "a"), next = projected(proposed, "a");
        TerrainMeshFrame.Cell c = next.cells().getFirst();
        TerrainMeshFrame obstructed = frame(new TerrainMeshFrame.Cell(c.key(), c.material(), c.landmarkId(), c.sourceMin(),
                c.min().add(0, .3, 0), c.size(), c.axisX(), c.axisY(), c.axisZ(), c.color(), c.light(), c.opacity(), c.collision()));
        check(DeepWalkAttachment.alignable(ground(before, BODY).normal().y, 1), "blocked case is still an alignable source floor");
        check(!MeshCollision.transitionClear(before, obstructed, BODY), "eligibility does not bypass the actual transition guard");
        check(!DeepWalkAttachment.walkable(ground(before, BODY).normal().y), "held tilted contact still cannot acquire");
    }
}
