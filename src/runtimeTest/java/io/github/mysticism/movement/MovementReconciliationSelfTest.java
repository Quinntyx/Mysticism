package io.github.mysticism.movement;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.client.spiritworld.ClientPoseSync;
import io.github.mysticism.component.*;
import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.vector.*;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.math.Vec3d;

/** Real native-profile steering, movement classifier and component packets; no game or model bootstrap. */
public final class MovementReconciliationSelfTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static Vec384f target(float distance) {
        float[] coordinates = new float[EmbeddingSpace.DIMENSIONS];
        coordinates[coordinates.length - 1] = distance;
        return new Vec384f(coordinates);
    }
    private static double error(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }
    private static RegistryByteBuf packet(LatentPos server) {
        RegistryByteBuf packet = new RegistryByteBuf(Unpooled.buffer(), null);
        server.writeSyncPacket(packet, null); return packet;
    }
    private static RegistryByteBuf packet(LatentBasis server) {
        RegistryByteBuf packet = new RegistryByteBuf(Unpooled.buffer(), null);
        server.writeSyncPacket(packet, null); return packet;
    }
    private static void deliver(LatentPos server, LatentPos client) {
        RegistryByteBuf packet = packet(server);
        try { client.applySyncPacket(packet); } finally { packet.release(); }
    }
    private static void deliver(LatentBasis server, LatentBasis client) {
        RegistryByteBuf packet = packet(server);
        try { client.applySyncPacket(packet); } finally { packet.release(); }
    }
    /** Same pre-window context, substep callback and final recording as ClientLatentPredictor. */
    private static void predict(LatentPos q, LatentBasis b, SpiritNavigation nav, Vec384f target,
                                Vec3d delta, boolean captured) {
        ClientPoseSync.preparePrediction(q, b, nav, true);
        TraversalSteering.deepStep(q.get(), b.get(), target, delta.x, delta.y, delta.z, captured,
                ClientPoseSync::recordPredictionPose);
        ClientPoseSync.recordPredictionPose(q.get(), b.get());
    }
    private static void beforeContext() {
        // Old entity had a high wire frontier. Replacement packets arrive BEFORE predictor context.
        var oldQ = new LatentPos(); var oldB = new LatentBasis(); var nav = new SpiritNavigation();
        var oldServerQ = new LatentPos(); var oldServerB = new LatentBasis();
        ClientPoseSync.beginPrediction(oldQ, oldB, nav, true);
        for (int i = 0; i < 30; i++) { deliver(oldServerQ, oldQ); deliver(oldServerB, oldB); }
        var q = new LatentPos(); var b = new LatentBasis();
        var serverQ = new LatentPos(); var serverB = new LatentBasis();
        deliver(serverQ, q); deliver(serverB, b);
        ClientPoseSync.preparePrediction(q, b, nav, false); // replacement baseline tick
        predict(q, b, nav, target(1), new Vec3d(1, 0, 0), false);
        Vec384f predictedQ = q.get().clone(); Basis384f predictedB = b.get().clone();
        // Delayed periodic snapshot of the baseline, not a new correction.
        deliver(serverQ, q); deliver(serverB, b);
        check(q.get().squareDistance(predictedQ) == 0, "pre-context position snapshot must seed authority, not overwrite later prediction");
        check(error(b.get(), predictedB) == 0, "pre-context basis snapshot must seed authority, not overwrite later prediction");
        check(q.receivedSequence() == 2 && b.receivedSequence() == 2, "replacement owns independent restarted wire streams");
    }
    private static void firstPacketAfterPrediction() {
        var q = new LatentPos(); var b = new LatentBasis(); var nav = new SpiritNavigation();
        predict(q, b, nav, target(1), new Vec3d(1, 0, 0), false);
        Vec384f predictedQ = q.get().clone(); Basis384f predictedB = b.get().clone();
        deliver(new LatentPos(), q); deliver(new LatentBasis(), b);
        check(q.get().squareDistance(predictedQ) == 0, "first-ever delayed position packet must use pre-integration baseline");
        check(error(b.get(), predictedB) == 0, "first-ever delayed basis packet must use pre-integration baseline");
    }
    private static void curvedReversal() {
        for (boolean captured : new boolean[] {false, true}) {
            for (Vec3d direction : new Vec3d[] {new Vec3d(1, 0, 0), new Vec3d(.8, .36, .48)}) {
                ClientPoseSync.clear();
                var q = new LatentPos(); var b = new LatentBasis(); var nav = new SpiritNavigation();
                var serverQ = new LatentPos(); var serverB = new LatentBasis();
                Vec384f target = target(captured ? .005f : 1f);
                ClientPoseSync.preparePrediction(q, b, nav, true);
                deliver(serverQ, q); deliver(serverB, b);
                predict(q, b, nav, target, direction, captured);
                predict(q, b, nav, target, direction.multiply(-.7), captured);
                Vec384f predictedQ = q.get().clone(); Basis384f predictedB = b.get().clone();
                // Snapshots computed with unequal, non-grid server windows along outbound motion.
                for (double distance : new double[] {.07, .29, .5, .73, .95, 1}) {
                    Vec384f partialQ = Vec384f.ZERO(); Basis384f partialB = new Basis384f();
                    Vec3d delta = direction.multiply(distance);
                    TraversalSteering.deepStep(partialQ, partialB, target, delta.x, delta.y, delta.z, captured);
                    serverQ.set(partialQ); serverB.set(partialB);
                    deliver(serverQ, q); deliver(serverB, b);
                    check(q.get().squareDistance(predictedQ) == 0, "delayed deep outbound position must not undo reversal at " + distance);
                    check(error(b.get(), predictedB) == 0, "intermediate CURVED rotation must not become authority at " + distance);
                }
                serverQ.set(predictedQ); serverB.set(predictedB);
                deliver(serverQ, q); deliver(serverB, b);
                check(error(b.get(), predictedB) == 0, "deep reversed server catch-up leaves prediction intact");
                check(predictedQ.data()[EmbeddingSpace.DIMENSIONS - 1] != 0, "curved prediction preserves native final coordinate");
            }
        }
    }
    private static void suppressedWindowConvergesAtRest() {
        var q = new LatentPos(); var b = new LatentBasis(); var nav = new SpiritNavigation();
        var serverQ = new LatentPos(); var serverB = new LatentBasis();
        ClientPoseSync.preparePrediction(q, b, nav, true);
        deliver(serverQ, q); deliver(serverB, b);
        Vec3d measured = new Vec3d(.45, .02, 0), correction = new Vec3d(0, .02, 0);
        Vec3d chosen = MovementIntegration.clientSemantic(measured, correction, false);
        predict(q, b, nav, target(1), chosen, false);
        var sample = MovementIntegration.server(measured, correction, false);
        check(sample.requiresReconciliation(), "suppressed overlap window must carry correction provenance");
        check(sample.semanticDelta().equals(Vec3d.ZERO) && sample.stateDelta().equals(measured), "disputed travel remains excluded but physical state stays live");
        check(q.get().squareDistance(serverQ.get()) > 0 && error(b.get(), serverB.get()) > 0, "fixture really diverges in both pose streams");
        RegistryByteBuf oldQ = packet(serverQ), oldB = packet(serverB);
        try {
            // A regular sync is correctly held as lag before the dispute is marked.
            q.applySyncPacket(oldQ); b.applySyncPacket(oldB);
            check(q.get().squareDistance(serverQ.get()) > 0, "unmarked unchanged server pose is indistinguishable from pending movement");
            check(SpiritBasisEvolver.reconcileSuppressedWindow(sample, serverQ, serverB), "real server hook requests immediate correction sync");
            // Advance context repeatedly before correction delivery: no nav-epoch timing dependency.
            for (int tick = 0; tick < 20; tick++) predict(q, b, nav, target(1), Vec3d.ZERO, false);
            deliver(serverQ, q);
            check(q.get().squareDistance(serverQ.get()) == 0, "marked suppressed position converges instead of being held forever");
            check(error(b.get(), serverB.get()) > 0, "position correction does not silently retire independent basis stream");
            predict(q, b, nav, target(1), Vec3d.ZERO, false);
            deliver(serverB, b);
            check(error(b.get(), serverB.get()) == 0, "marked suppressed basis converges after later independent delivery");
            for (int tick = 0; tick < 300; tick++) {
                predict(q, b, nav, target(1), Vec3d.ZERO, false);
                if (tick % 4 == 0) { deliver(serverQ, q); deliver(serverB, b); }
            }
            check(q.get().squareDistance(serverQ.get()) == 0 && error(b.get(), serverB.get()) == 0, "resting cadence stays converged after correction");
            check(!SpiritBasisEvolver.reconcileSuppressedWindow(MovementIntegration.Sample.ZERO, serverQ, serverB), "clean idle windows do not spuriously force correction");
            predict(q, b, nav, target(1), new Vec3d(.2, 0, 0), false);
            Vec384f movingQ = q.get().clone(); Basis384f movingB = b.get().clone();
            oldQ.readerIndex(0); oldB.readerIndex(0);
            q.applySyncPacket(oldQ); b.applySyncPacket(oldB);
            check(q.get().squareDistance(movingQ) == 0 && error(b.get(), movingB) == 0, "reordered pre-correction packets cannot roll back resumed movement");
            deliver(serverQ, q); deliver(serverB, b);
            check(q.get().squareDistance(movingQ) == 0 && error(b.get(), movingB) == 0, "same revision periodic packets resume ordinary lag filtering");
            check(MovementIntegration.server(Vec3d.ZERO, correction, false).requiresReconciliation(), "zero-displacement overlap still reconciles earlier prediction");
            check(!MovementIntegration.server(measured, Vec3d.ZERO, false).requiresReconciliation(), "matched clean chosen movement has no correction revision");
            NbtCompound tag = new NbtCompound(); serverQ.writeToNbt(tag, null);
            check(!tag.contains("poseCorrectionRevision"), "wire correction provenance is not embedding persistence or migration");
            NbtCompound basisTag = new NbtCompound(); serverB.writeToNbt(basisTag, null);
            check(!basisTag.contains("poseCorrectionRevision"), "basis correction provenance also remains session-local");
        } finally { oldQ.release(); oldB.release(); }
    }
    public static void main(String[] args) {
        int failures = 0;
        for (Runnable regression : new Runnable[] {MovementReconciliationSelfTest::beforeContext,
                MovementReconciliationSelfTest::firstPacketAfterPrediction,
                MovementReconciliationSelfTest::curvedReversal,
                MovementReconciliationSelfTest::suppressedWindowConvergesAtRest}) {
            ClientPoseSync.clear(); ClientPoseSync.install();
            try { regression.run(); }
            catch (AssertionError failed) { failures++; System.err.println(failed.getMessage()); }
            finally { ClientPoseSync.clear(); LatentSync.clear(); }
        }
        if (failures != 0) throw new AssertionError(failures + " movement reconciliation regressions failed");
        System.out.println("MovementReconciliationSelfTest: " + checks + " checks passed");
    }
}
