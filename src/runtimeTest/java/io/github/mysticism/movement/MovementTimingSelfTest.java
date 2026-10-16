package io.github.mysticism.movement;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.client.spiritworld.ClientPoseSync;
import io.github.mysticism.component.LatentBasis;
import io.github.mysticism.component.LatentPos;
import io.github.mysticism.component.LatentSync;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.navigation.MovementIntegration;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.BasisIntegrator384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.math.Vec3d;

/** Runs real steering/classification and component packets with unequal client/server windows. */
// These regressions start from an explicitly anchored native origin. A default/reset
// LatentPos has NO coordinate; unavailable/reset packet behavior is covered separately
// by CoordinateAvailabilitySyncTest and must never be used as a usable movement fixture.
public final class MovementTimingSelfTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
    private static Vec384f axis(int axis, float value) {
        float[] values = new float[EmbeddingSpace.DIMENSIONS]; values[axis] = value;
        return new Vec384f(values);
    }
    private static double basisError(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }
    private static void distanceComposableTurning() {
        Basis384f burst = new Basis384f(), small = new Basis384f();
        Vec384f direction = axis(EmbeddingSpace.DIMENSIONS - 1, 1);
        BasisIntegrator384f.step(burst, direction, 6, 0, 0, .3f);
        for (int tick = 0; tick < 120; tick++)
            BasisIntegrator384f.step(small, direction, .05, 0, 0, .3f);
        check(basisError(burst, small) < 1e-7, "angle decay must compose across accumulated movement windows");
        check(burst.i.dot(direction) < .999f, "large windows must not instantly saturate steering");
        Basis384f rest = burst.clone();
        BasisIntegrator384f.step(burst, direction, 0, 0, 0, .3f);
        check(basisError(burst, rest) == 0, "idle elapsed ticks cannot rotate the pose");
    }
    private static void deepMovementWindowAgreement(boolean captured) {
        Vec384f target = axis(EmbeddingSpace.DIMENSIONS - 1, captured ? .001f : 1f);
        Vec384f burst = Vec384f.ZERO(), small = Vec384f.ZERO(), irregular = Vec384f.ZERO();
        Basis384f burstBasis = new Basis384f(), smallBasis = new Basis384f(), irregularBasis = new Basis384f();
        TraversalSteering.deepStep(burst, burstBasis, target, 6, 0, 0, captured);
        for (int tick = 0; tick < 120; tick++)
            TraversalSteering.deepStep(small, smallBasis, target, .05, 0, 0, captured);
        double[] windows = {.03, .47, 1.5, .2, 3.1, .7};
        for (double distance : windows) {
            var sample = MovementIntegration.server(new Vec3d(distance, 0, 0), Vec3d.ZERO, false);
            Vec3d delta = sample.semanticDelta();
            TraversalSteering.deepStep(irregular, irregularBasis, target, delta.x, delta.y, delta.z, captured);
        }
        double positionBlocks = Math.sqrt(burst.squareDistance(small)) * TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT;
        double irregularBlocks = Math.sqrt(irregular.squareDistance(small)) * TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT;
        check(positionBlocks < .025, "burst/small path error must stay below .025 blocks; got " + positionBlocks);
        check(irregularBlocks < .025, "irregular-window path error must stay below .025 blocks; got " + irregularBlocks);
        check(basisError(burstBasis, smallBasis) < .001, "burst/small basis must not diverge by tick grouping");
        check(basisError(irregularBasis, smallBasis) < .001, "irregular-window basis must not diverge by tick grouping");
        Vec384f idle = small.clone(); Basis384f idleBasis = smallBasis.clone();
        for (int tick = 0; tick < 300; tick++)
            TraversalSteering.deepStep(small, smallBasis, target, 0, 0, 0, captured);
        check(small.squareDistance(idle) == 0 && basisError(smallBasis, idleBasis) == 0, "stationary time has no semantic drift");
        check(small.data()[EmbeddingSpace.DIMENSIONS - 1] > 0, "steering retains the last native coordinate");
    }
    private static void approachWindowAgreement() {
        Vec384f target = axis(EmbeddingSpace.DIMENSIONS - 1, .01f);
        // Exercise both the speed-limited far region and terminal exponential decay.
        for (float remaining : new float[] {.1f, .05f, .0005f}) {
            Vec384f start = target.clone().add(axis(0, remaining));
            Vec384f burst = start.clone(), small = start.clone();
            TraversalSteering.approachStep(burst, target, 6, 0, 0);
            for (int tick = 0; tick < 120; tick++) TraversalSteering.approachStep(small, target, .05, 0, 0);
            check(burst.squareDistance(small) < 1e-12, "terminal convergence must compose by movement, not 25% per tick");
            check(burst.squareDistance(target) < start.squareDistance(target), "continued movement reaches the full residual");
            check(burst.squareDistance(target) > 0, "approach must not assign the captured target");
            check(Math.sqrt(burst.squareDistance(start)) <= 6 / 96.0 + 1e-6, "approach cannot invent semantic travel");
        }
    }
    private static RegistryByteBuf packet(LatentPos snapshot) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        snapshot.writeSyncPacket(buf, null); return buf;
    }
    private static void accumulatedPredictionStillRejectsDelayedAndReorderedPackets() {
        LatentPos client = new LatentPos(Vec384f.ZERO()), server = new LatentPos(Vec384f.ZERO());
        LatentBasis basis = new LatentBasis(); SpiritNavigation nav = new SpiritNavigation();
        ClientPoseSync.install();
        RegistryByteBuf first = null, delayed = null, newer = null;
        try {
            ClientPoseSync.beginPrediction(client, basis, nav, true);
            first = packet(server); client.applySyncPacket(first);
            Vec3d delta = MovementIntegration.clientSemantic(new Vec3d(6, .1, 0), new Vec3d(0, .1, 0), false);
            TraversalSteering.advance(client.get(), basis.get(), delta.x, delta.y, delta.z);
            ClientPoseSync.beginPrediction(client, basis, nav, true);
            TraversalSteering.advance(server.get(), basis.get(), 4, 0, 0);
            delayed = packet(server);
            TraversalSteering.advance(client.get(), basis.get(), -5, 0, 0);
            ClientPoseSync.beginPrediction(client, basis, nav, true);
            Vec384f predicted = client.get().clone();
            client.applySyncPacket(delayed);
            check(client.get().squareDistance(predicted) == 0, "delayed outbound burst must not roll back a reversed client pose");
            server.set(predicted); newer = packet(server); client.applySyncPacket(newer);
            delayed.readerIndex(0); client.applySyncPacket(delayed);
            check(client.get().squareDistance(predicted) == 0, "reordered burst snapshot stays rejected after catch-up");
            ClientPoseSync.markTeleport();
            boolean corrected = ClientPoseSync.consumeTeleportCorrection();
            Vec3d correction = MovementIntegration.clientSemantic(new Vec3d(.2, 0, 0), Vec3d.ZERO, corrected);
            TraversalSteering.advance(client.get(), basis.get(), correction.x, correction.y, correction.z);
            ClientPoseSync.beginPrediction(client, basis, nav, !corrected);
            check(client.get().squareDistance(predicted) == 0, "sub-block correction cannot become travel in the merged predictor handoff");
        } finally {
            if (first != null) first.release(); if (delayed != null) delayed.release(); if (newer != null) newer.release();
            ClientPoseSync.clear(); LatentSync.clear();
        }
    }
    public static void main(String[] args) {
        distanceComposableTurning();
        deepMovementWindowAgreement(false);
        deepMovementWindowAgreement(true);
        approachWindowAgreement();
        accumulatedPredictionStillRejectsDelayedAndReorderedPackets();
        System.out.println("MovementTimingSelfTest: " + checks + " checks passed");
    }
}
