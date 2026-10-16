package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Combined movement/arrival regressions against the real SAT/prediction/intent cores.
 * No live terrain ownership acquisition or network execution is claimed by these fixtures. */
public final class MeshMovementArrivalTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static final Box BODY = new Box(-.3, 0, -.3, .3, 1.8, .3);
    private static TerrainMeshFrame.Cell cell(long key, Vec3d min, Vec3d x, Vec3d y, Vec3d z) {
        return new TerrainMeshFrame.Cell(key, 0, "actual-source", Vec3d.ZERO, min, new Vec3d(1, 1, 1),
                x, y, z, 0xffffff, 0, 1, List.of(new Box(0, 0, 0, 1, 1, 1)));
    }
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1, false, "minecraft:overworld", Vec3d.ZERO, Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cells));
    }
    private static TerrainMeshFrame floor() {
        return frame(cell(1, new Vec3d(-8, -1, -8), new Vec3d(16, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 16)));
    }
    private static void packetAcceptanceCannotUndoTheSweep() {
        var floor = floor();
        var wall = frame(cell(2, new Vec3d(.4, -1, -8), new Vec3d(.1, .03, 0), new Vec3d(0, 4, 0), new Vec3d(0, 0, 16)));
        for (var geometry : List.of(floor, wall)) {
            var index = new MeshCollision.Index(geometry);
            Vec3d position = Vec3d.ZERO;
            Vec3d wanted = geometry == floor ? new Vec3d(0, -.08, 0) : new Vec3d(.08, 0, 0);
            for (int tick = 0; tick < 100; tick++) {
                Vec3d resolved = position.add(MeshCollision.move(index, BODY.offset(position), wanted, true, 0, false));
                Vec3d requested = position.add(wanted);
                var accepted = SpiritMovementAcceptance.accept(requested, resolved);
                // Both examples pass vanilla's .0625 horizontal residual gate; Y is ignored entirely.
                check(requested.squaredDistanceTo(resolved) < .0625, "fixture reaches vanilla's unchecked acceptance path");
                check(accepted.position().equals(resolved), "packet acceptance must retain even tiny sweep clipping");
                position = accepted.position();
                check(MeshCollision.bodyClear(geometry, BODY.offset(position)), "accepted packets never put the body inside the displayed surface");
            }
            check(geometry == floor ? position.y >= 0 : position.x < .10001, "repeated small packets cannot tunnel through the surface");
        }
        var resolved = new Vec3d(0, .00001, 0);
        var tiny = SpiritMovementAcceptance.accept(Vec3d.ZERO, resolved);
        check(!tiny.correctionRequired() && tiny.position().equals(resolved), "packet epsilon never weakens the collision epsilon");
        check(SpiritMovementAcceptance.accept(new Vec3d(0, -1, 0), resolved).correctionRequired(), "vertical mismatch requests correction to the accepted surface");
        check(!SpiritMovementAcceptance.accept(resolved, resolved).correctionRequired(), "matching client prediction does not trigger a teleport correction");
    }
    private static void descentPredictionAndLatestArrival() {
        var frame = floor();
        var clientIndex = new MeshCollision.Index(frame);
        var serverIndex = new MeshCollision.Index(frame);
        var basis = new Basis384f();
        Vec384f clientQ = Vec384f.ZERO(), serverQ = Vec384f.ZERO();
        Vec3d client = new Vec3d(0, 12, 0), server = client;
        var reconciliation = new SpiritPoseReconciliation.Session();
        reconciliation.reconcilePosition(serverQ.clone(), null);
        var walk = new WalkIntentTracker();
        check(walk.flightRequest(false) == WalkIntentTracker.FlightRequest.WALK && walk.startWalk(), "descent starts one current-support intent");
        check(MeshCollision.ground(BODY.offset(client), clientIndex, 24).isPresent(), "support discovery works before collision contact");
        check(MeshCollision.ground(clientIndex, BODY.offset(client)).isEmpty(), "discovery is not arrival authority");
        for (int tick = 0; tick < 40; tick++) {
            Vec3d predicted = MeshCollision.move(clientIndex, BODY.offset(client), new Vec3d(0, -.8, 0), true, 0, false);
            client = client.add(predicted);
            Vec3d acceptedDelta = MeshCollision.move(serverIndex, BODY.offset(server), new Vec3d(0, -.8, 0), true, 0, false);
            var accepted = SpiritMovementAcceptance.accept(server.add(predicted), server.add(acceptedDelta));
            check(!accepted.correctionRequired(), "identical visible frames accept prediction without rubber banding");
            server = accepted.position();
            TraversalSteering.advance(clientQ, basis, predicted.x, predicted.y, predicted.z);
            TraversalSteering.advance(serverQ, basis, acceptedDelta.x, acceptedDelta.y, acceptedDelta.z);
            if (tick % 4 == 3) clientQ = reconciliation.reconcilePosition(serverQ.clone(), clientQ);
            check(clientQ.squareDistance(serverQ) < 1e-12, "pending walk predicts the same accepted semantic movement");
            check(MeshCollision.bodyClear(frame, BODY.offset(server)), "descent remains outside the displayed floor");
        }
        check(MeshCollision.ground(serverIndex, BODY.offset(server)).isPresent(), "descent finishes at real contact");
        check(walk.pending(), "waiting for source proof cannot move the body through the contact");
        walk.bumpDestination();
        check(walk.superseded(), "new destination invalidates the older pending arrival");
        walk.endWalk();
        check(walk.flightRequest(false) == WalkIntentTracker.FlightRequest.IGNORE, "stale off packet cannot resurrect old arrival");
        var correction = walk.serverCorrected(true);
        check(walk.acknowledgeCorrection(correction), "flight restoration receipt allows a deliberate retry");
        check(walk.flightRequest(false) == WalkIntentTracker.FlightRequest.WALK && walk.startWalk() && !walk.superseded(), "retry belongs to the newest destination");
        var nav = new SpiritNavigation();
        nav.enterDeep(); nav.setSemanticReady(true); nav.setSupportApproach(true);
        long epoch = nav.motionEpoch();
        // Model the navigation mode write only after real contact and independently verified proof.
        nav.shallow("minecraft:overworld", "actual-source", server);
        walk.endWalk(); reconciliation.reset();
        check(!nav.deep() && !nav.supportApproach() && nav.motionEpoch() != epoch, "grounded handoff invalidates pending prediction mode");
        check(reconciliation.reconcilePosition(serverQ.clone(), clientQ).squareDistance(serverQ) == 0, "post-arrival semantic pose adopts authority");
        check(MeshCollision.ground(serverIndex, BODY.offset(server)).isPresent(), "mode handoff preserves the actual supported feet");
    }
    public static void main(String[] args) {
        packetAcceptanceCannotUndoTheSweep();
        descentPredictionAndLatestArrival();
        System.out.println("MeshMovementArrivalTest: " + checks + " checks passed");
    }
}
