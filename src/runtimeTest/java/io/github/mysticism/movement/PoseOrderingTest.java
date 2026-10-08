package io.github.mysticism.movement;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.client.spiritworld.ClientPoseSync;
import io.github.mysticism.component.LatentBasis;
import io.github.mysticism.component.LatentPos;
import io.github.mysticism.component.LatentSync;
import io.github.mysticism.component.PoseSyncReconciliation;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Regressions for delayed/reordered authoritative pose synchronization. The server rebuilds the
 * movement-integrated semantic pose (q/basis) from movement packets it has already received and
 * re-broadcasts them on a fixed cadence; those snapshots lag the locally predicted pose by the
 * in-flight movement, and applying them verbatim rolled the predicted spirit pose back to a stale
 * snapshot on every cadence tick (movement jitter / rubber banding of the projected world).
 *
 * <p>Ordering is enforced by a per-component monotonic wire sequence
 * ({@code writeSyncPacket}/{@code applySyncPacket}): reordered or duplicated snapshots are rejected
 * outright (P2). A fresh snapshot is held if it lies on the unacknowledged predicted trajectory,
 * even after a reversal (P1), or inside the direct frontier distance. Neither distance to the
 * frontier alone nor an accumulated per-step squared budget bounds arbitrary pending movement.
 */
public final class PoseOrderingTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static Vec384f unit(int axis, float value) {
        float[] v = new float[EmbeddingSpace.DIMENSIONS];
        v[axis] = value;
        return new Vec384f(v);
    }

    private static Vec384f advanced(Vec384f from, double blocks) {
        Vec384f result = from.clone();
        TraversalSteering.advance(result, new Basis384f(), blocks, 0, 0);
        return result;
    }

    private static double basisError(Basis384f a, Basis384f b) {
        return (double) a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }

    private static RegistryByteBuf serialized(LatentPos pos, int sequence) {
        NbtCompound tag = new NbtCompound();
        pos.writeToNbt(tag, null);
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        buf.writeVarInt(sequence);
        buf.writeNbt(tag);
        return buf;
    }

    private static RegistryByteBuf serialized(LatentBasis basis, int sequence) {
        NbtCompound tag = new NbtCompound();
        basis.writeToNbt(tag, null);
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        buf.writeVarInt(sequence);
        buf.writeNbt(tag);
        return buf;
    }

    /** Decision core: only unacknowledged in-flight lag (direct distance) justifies holding a fresh sync. */
    private static void reconciliationDecisions() {
        // A fresh snapshot whose lag is within the direct frontier distance is held.
        check(!PoseSyncReconciliation.acceptPosition(0.0416, 0.05), "lagging position sync within direct lag bound must be held");
        // Any divergence beyond the direct lag bound is a genuine correction.
        check(PoseSyncReconciliation.acceptPosition(0.06, 0.05), "position divergence beyond the lag bound must be accepted");
        check(PoseSyncReconciliation.acceptPosition(0.5, 0), "divergence beyond a fully-acknowledged frontier must be accepted");
        // Non-finite input is never a reason to hold an authoritative value.
        check(PoseSyncReconciliation.acceptPosition(Double.NaN, 0), "non-finite divergence must be accepted");
        check(PoseSyncReconciliation.acceptBasis(Double.POSITIVE_INFINITY, 1), "infinite basis divergence must be accepted");
        // Basis metric analogue.
        check(!PoseSyncReconciliation.acceptBasis(5e-5, 1e-3), "lagging basis sync within direct lag bound must be held");
        check(PoseSyncReconciliation.acceptBasis(1e-2, 1e-3), "basis divergence beyond the lag bound must be accepted");
        // Float noise below the tolerance is held even against a fully-acknowledged frontier.
        check(!PoseSyncReconciliation.acceptPosition(1e-9, 0), "float-noise divergence must be held");
    }

    /** Wire round trip is unchanged when no client filter is installed (server/peer behaviour). */
    private static void unfilteredSyncAppliesVerbatim() {
        try {
            LatentSync.clear();
            LatentPos component = new LatentPos();
            component.set(unit(0, 1));
            LatentPos incoming = new LatentPos();
            incoming.set(unit(1, 2));
            component.applySyncPacket(serialized(incoming, 1));
            check(component.get().squareDistance(unit(1, 2)) == 0, "unfiltered position sync must apply verbatim");

            LatentBasis basis = new LatentBasis();
            basis.set(new Basis384f(unit(0, 1), unit(2, 1), unit(1, 1)));
            LatentBasis basisIncoming = new LatentBasis(new Basis384f());
            basis.applySyncPacket(serialized(basisIncoming, 1));
            check(basis.get().i.squareDistance(new Basis384f().i) == 0, "unfiltered basis sync must apply verbatim");
        } finally {
            LatentSync.clear();
        }
    }

    /** Production write path embeds a strictly increasing per-instance sequence. */
    private static void writeSyncPacketEmbedsMonotonicSequence() {
        LatentPos pos = new LatentPos();
        pos.set(unit(0, 1));
        int first = readEmbeddedSequence(pos);
        int second = readEmbeddedSequence(pos);
        check(first == 1 && second == 2, "position writeSyncPacket must embed increasing sequences");
        LatentBasis basis = new LatentBasis();
        basis.set(new Basis384f());
        int basisFirst = readEmbeddedSequence(basis);
        int basisSecond = readEmbeddedSequence(basis);
        check(basisFirst == 1 && basisSecond == 2, "basis writeSyncPacket must embed increasing sequences");
    }

    private static int readEmbeddedSequence(LatentPos pos) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        pos.writeSyncPacket(buf, null);
        return buf.readVarInt();
    }

    private static int readEmbeddedSequence(LatentBasis basis) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        basis.writeSyncPacket(buf, null);
        return buf.readVarInt();
    }

    /** Cadence syncs lagging the prediction are held; corrections beyond the frontier lag are applied. */
    private static void predictionHoldsLaggingSyncsAndAcceptsCorrections() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Vec384f q0 = Vec384f.ZERO();
        pos.set(q0);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);

            // At rest the first cadence sync is a caught-up no-op and establishes the frontier.
            LatentPos rest = new LatentPos();
            rest.set(q0);
            pos.applySyncPacket(serialized(rest, 1));
            check(pos.get().squareDistance(q0) == 0, "caught-up sync at rest must be a no-op");

            // One cadence of ordinary walking: the client integrates ~4 blocks immediately.
            Vec384f predicted = advanced(q0, 4);
            pos.set(predicted);

            // The cadence sync still carries the pose integrated BEFORE this movement: held.
            LatentPos lagging = new LatentPos();
            lagging.set(q0);
            pos.applySyncPacket(serialized(lagging, 2));
            check(pos.get().squareDistance(predicted) == 0, "lagging cadence sync must not roll the predicted pose back");

            // Partial catch-up keeps holding; the frontier advances to the freshest authority.
            LatentPos halfway = new LatentPos();
            halfway.set(advanced(q0, 2));
            pos.applySyncPacket(serialized(halfway, 3));
            check(pos.get().squareDistance(predicted) == 0, "partially lagging sync must still be held");

            // A fresh snapshot moving AWAY from the prediction beyond the frontier lag is a genuine
            // authoritative correction (e.g. a re-anchor) and must be applied, not held.
            Vec384f anchored = unit(5, 0.5f);
            LatentPos corrected = new LatentPos();
            corrected.set(anchored);
            pos.applySyncPacket(serialized(corrected, 4));
            check(pos.get().squareDistance(anchored) == 0, "authoritative correction beyond the lag bound must be applied");

            // After the accepted correction the client moves again; the pre-move server pose lags
            // inside the direct frontier lag and must be held, not applied as a rollback.
            Vec384f walked = advanced(anchored, 0.5);
            pos.set(walked);
            LatentPos preMove = new LatentPos();
            preMove.set(anchored);
            pos.applySyncPacket(serialized(preMove, 5));
            check(pos.get().squareDistance(walked) == 0, "lagging sync after an accepted correction must be held");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /**
     * P2 regression: same-epoch snapshot reorder. Prediction is four blocks ahead; a fresher sync
     * acknowledges two blocks (frontier advances), and the staler zero-block snapshot then arrives
     * out of order. Its divergence (four blocks) exceeds the shrunken frontier lag, but it is an
     * OLDER snapshot: adopting it would roll the prediction backward four blocks. The wire sequence
     * identifies it as stale and it must be held.
     */
    private static void sameEpochReorderIsNeverAdopted() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Vec384f q0 = Vec384f.ZERO();
        pos.set(q0);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);

            // At rest the cadence sync is caught up and establishes the acknowledgment frontier.
            LatentPos rest = new LatentPos();
            rest.set(q0);
            pos.applySyncPacket(serialized(rest, 10));

            // The client integrates four blocks the server has not acknowledged yet.
            Vec384f predicted = advanced(q0, 4);
            pos.set(predicted);

            // Newer snapshot first: acknowledges half the movement, held inside the direct lag bound.
            LatentPos twoBlocks = new LatentPos();
            twoBlocks.set(advanced(q0, 2));
            pos.applySyncPacket(serialized(twoBlocks, 11));
            check(pos.get().squareDistance(predicted) == 0, "fresher partial-catch-up sync must be held");

            // Older snapshot arrives after the newer one (reordered): must NOT be adopted even
            // though its divergence (four blocks) now exceeds the frontier lag (two blocks).
            LatentPos staleZero = new LatentPos();
            staleZero.set(q0);
            pos.applySyncPacket(serialized(staleZero, 9));
            check(pos.get().squareDistance(predicted) == 0, "reordered stale snapshot must never roll the prediction back");

            // A duplicate of the stale snapshot is likewise held.
            pos.applySyncPacket(serialized(staleZero, 9));
            check(pos.get().squareDistance(predicted) == 0, "duplicate stale snapshot must never roll the prediction back");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /**
     * P1 regression: multi-tick deep steering. The lag budget must be the direct distance from the
     * prediction to the acknowledged frontier — accumulated per-step squared distances do not bound
     * cumulative divergence, so the pre-fix guard accepted a stale basis (carrying the pre-rotation
     * pose) and rolled the predicted basis backward after sustained same-plane deep steering.
     */
    private static void multiTickDeepSteeringUsesComposableBound() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Vec384f target = unit(3, 1);
        Basis384f start = new Basis384f();
        basis.set(start);
        Vec384f q = Vec384f.ZERO();
        pos.set(q);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);

            // At rest a fresh caught-up sync establishes the acknowledgment frontier at the
            // pre-rotation basis.
            LatentBasis rest = new LatentBasis(start);
            basis.applySyncPacket(serialized(rest, 1));
            check(basisError(basis.get(), start) == 0, "caught-up basis sync at rest must be a no-op");

            // Sustained deep movement rotates the predicted basis toward the attunement direction.
            for (int tick = 0; tick < 24; tick++) {
                TraversalSteering.deepStep(q, basis.get(), target, 0.05, 0, 0, false);
                pos.set(q);
                ClientPoseSync.beginPrediction(pos, basis, nav, true);
            }
            Basis384f rotated = basis.get().clone();
            double directLag = basisError(start, rotated);
            check(directLag > 0.05, "multi-tick deep steering must accumulate a measurable direct basis divergence");

            // A stale-order snapshot carrying the ORIGINAL pre-rotation basis must be held. Its
            // divergence equals the full cumulative rotation, which the old accumulated per-step
            // squared budget (~1/24 of it here) wrongly classified as a genuine correction.
            LatentBasis staleStart = new LatentBasis(start);
            basis.applySyncPacket(serialized(staleStart, 0));
            check(basisError(basis.get(), rotated) == 0, "reordered pre-rotation basis must never roll the predicted basis back");

            // A fresh snapshot that acknowledged only PART of the rotation stays inside the direct
            // cumulative lag bound and is held.
            Basis384f partial = TraversalSteering.blend(start, rotated, 0.5f);
            LatentBasis partialSnapshot = new LatentBasis(partial);
            basis.applySyncPacket(serialized(partialSnapshot, 2));
            check(basisError(basis.get(), rotated) == 0, "fresh partial-acknowledgment basis sync must be held");

            // A fresh snapshot moving away beyond the cumulative rotation is a genuine correction.
            Basis384f away = new Basis384f(unit(1, 1), unit(0, 1), unit(2, 1));
            LatentBasis authoritative = new LatentBasis(away);
            basis.applySyncPacket(serialized(authoritative, 3));
            check(basisError(basis.get(), away) == 0, "fresh basis correction beyond the cumulative lag must be applied");

            // Position ordering analogue: a multi-block movement history cannot be rolled back by
            // a stale-order snapshot either. Establish the frontier first, as a real session would.
            Vec384f travelled = advanced(Vec384f.ZERO(), 4);
            pos.set(travelled);
            LatentPos positionRest = new LatentPos();
            positionRest.set(travelled);
            pos.applySyncPacket(serialized(positionRest, 1));
            LatentPos stalePose = new LatentPos();
            stalePose.set(Vec384f.ZERO());
            pos.applySyncPacket(serialized(stalePose, 0));
            check(pos.get().squareDistance(travelled) == 0, "reordered stale position snapshot must never roll the prediction back");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** P1: a newer wire sequence can still carry the outbound pose after a direction reversal. */
    private static void backtrackingDoesNotAdoptDelayedOutboundPoses() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Vec384f origin = Vec384f.ZERO();
        pos.set(origin);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            LatentPos snapshot = new LatentPos();
            snapshot.set(origin);
            pos.applySyncPacket(serialized(snapshot, 1));

            // The real predictor publishes after every integrated tick. The server has not yet
            // acknowledged any of the outbound movement when the player reverses direction.
            pos.set(advanced(origin, 4));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            Vec384f returned = advanced(origin, 1);
            pos.set(returned);
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            // Stationary ticks must not evict real pending movement from the bounded trail.
            for (int tick = 0; tick < 300; tick++) ClientPoseSync.beginPrediction(pos, basis, nav, true);

            snapshot.set(advanced(origin, 4));
            pos.applySyncPacket(serialized(snapshot, 2));
            check(pos.get().squareDistance(returned) == 0,
                    "fresh delayed +4 snapshot must not overwrite the returned +1 prediction");

            // A server tick can land BETWEEN predicted endpoints, not only exactly at +4.
            snapshot.set(advanced(origin, 2.5));
            pos.applySyncPacket(serialized(snapshot, 3));
            check(pos.get().squareDistance(returned) == 0, "delayed return-leg intermediate pose must be held");
            snapshot.set(origin);
            pos.applySyncPacket(serialized(snapshot, 1));
            check(pos.get().squareDistance(returned) == 0, "reordered snapshot after backtracking must still be rejected");

            // Acknowledgment of the return leg must retire the outbound leg: a fresh jump back
            // to +4 now has no explanation in pending prediction and remains a real correction.
            snapshot.set(advanced(origin, 4));
            pos.applySyncPacket(serialized(snapshot, 4));
            check(pos.get().squareDistance(snapshot.get()) == 0,
                    "acknowledged outbound history must not mask a subsequent authoritative correction");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** Overshoot can occur in deep steering's basis too; use many real orthonormal small turns. */
    private static void backtrackingBasisDoesNotAdoptDelayedTurns() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            basis.applySyncPacket(serialized(new LatentBasis(basis.get()), 1));
            for (int tick = 1; tick <= 8; tick++) {
                basis.set(turned(tick * .05));
                ClientPoseSync.beginPrediction(pos, basis, nav, true);
            }
            for (int tick = 7; tick >= 2; tick--) {
                basis.set(turned(tick * .05));
                ClientPoseSync.beginPrediction(pos, basis, nav, true);
            }
            Basis384f returned = basis.get().clone();
            basis.applySyncPacket(serialized(new LatentBasis(turned(.375)), 2));
            check(basisError(basis.get(), returned) == 0, "delayed outbound intermediate basis must survive a reversal");
            basis.applySyncPacket(serialized(new LatentBasis(turned(.4)), 3));
            check(basisError(basis.get(), returned) == 0, "delayed outbound basis endpoint must not roll back a reversed turn");
            basis.applySyncPacket(serialized(new LatentBasis(turned(.2)), 4));
            check(basisError(basis.get(), returned) == 0, "delayed basis on the return leg must be held");
            basis.applySyncPacket(serialized(new LatentBasis(turned(.4)), 5));
            check(basisError(basis.get(), turned(.4)) == 0, "acknowledged turn history must not mask a fresh basis correction");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    private static Basis384f turned(double radians) {
        Vec384f i = unit(0, (float) Math.cos(radians)).add(unit(3, (float) Math.sin(radians)));
        return new Basis384f(i, unit(1, 1), unit(2, 1));
    }

    /** Matching a revisited pose must not acknowledge the entire loop or the other component. */
    private static void closedLoopRetainsPendingSuffixAndIndependentBasis() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        pos.set(Vec384f.ZERO());
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            LatentPos snapshot = new LatentPos();
            snapshot.set(Vec384f.ZERO());
            pos.applySyncPacket(serialized(snapshot, 1));
            basis.applySyncPacket(serialized(new LatentBasis(basis.get()), 1));
            // Mutate q in place, just as the production predictor does. History must own copies.
            TraversalSteering.advance(pos.get(), basis.get(), 4, 0, 0);
            basis.set(turned(.4));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            pos.set(Vec384f.ZERO());
            basis.set(turned(.1));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            Basis384f returnedBasis = basis.get().clone();

            pos.applySyncPacket(serialized(snapshot, 2));
            snapshot.set(advanced(Vec384f.ZERO(), 3.5));
            pos.applySyncPacket(serialized(snapshot, 3));
            check(pos.get().squareDistance(Vec384f.ZERO()) == 0,
                    "matching the loop's origin must retain delayed outbound intermediate poses");
            snapshot.set(advanced(Vec384f.ZERO(), 4));
            pos.applySyncPacket(serialized(snapshot, 4));
            check(pos.get().squareDistance(Vec384f.ZERO()) == 0, "delayed loop endpoint must not undo the completed return");
            basis.applySyncPacket(serialized(new LatentBasis(turned(.4)), 2));
            check(basisError(basis.get(), returnedBasis) == 0, "position acknowledgment must not retire pending basis history");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** The trail is not an inflated scalar radius: off-path corrections still apply and reset it. */
    private static void offPathCorrectionsResetOnlyTheirOwnTrail() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Vec384f origin = Vec384f.ZERO();
        pos.set(origin);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            LatentPos snapshot = new LatentPos();
            snapshot.set(origin);
            pos.applySyncPacket(serialized(snapshot, 1));
            basis.applySyncPacket(serialized(new LatentBasis(basis.get()), 1));
            pos.set(advanced(origin, 4));
            basis.set(turned(.4));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            Vec384f returned = advanced(origin, 1);
            pos.set(returned);
            basis.set(turned(.1));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            Basis384f returnedBasis = basis.get().clone();

            // Divergence is smaller than distance to the outbound pose, but outside the actual
            // path. Use the LAST native v2 coordinate to catch accidental history truncation.
            Vec384f corrected = returned.clone().add(unit(EmbeddingSpace.DIMENSIONS - 1, .02f));
            snapshot.set(corrected);
            pos.applySyncPacket(serialized(snapshot, 2));
            check(pos.get().squareDistance(corrected) == 0, "off-path native-coordinate correction must not be hidden by the excursion radius");
            basis.applySyncPacket(serialized(new LatentBasis(turned(.4)), 2));
            check(basisError(basis.get(), returnedBasis) == 0, "position correction must not clear pending basis rotations");

            // With the position frontier fully caught up, the discarded pre-correction path must
            // not hold a new correction that happens to use one of its old points.
            snapshot.set(advanced(origin, 4));
            pos.applySyncPacket(serialized(snapshot, 3));
            check(pos.get().squareDistance(snapshot.get()) == 0, "accepted correction must retire obsolete position history");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** Prediction breaks must not carry an old excursion into a new healthy segment. */
    private static void predictionBreakDiscardsExcursionHistory() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        pos.set(Vec384f.ZERO());
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            LatentPos snapshot = new LatentPos();
            snapshot.set(Vec384f.ZERO());
            pos.applySyncPacket(serialized(snapshot, 1));
            pos.set(advanced(Vec384f.ZERO(), 4));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            pos.set(Vec384f.ZERO());
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            ClientPoseSync.beginPrediction(pos, basis, nav, false);
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            snapshot.set(advanced(Vec384f.ZERO(), 4));
            pos.applySyncPacket(serialized(snapshot, 2));
            check(pos.get().squareDistance(snapshot.get()) == 0, "prediction break must discard the previous segment's trail");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** Teleported poses are corrections: the next syncs are never misjudged as lagging movement. */
    private static void teleportCorrectionsAreNeverHeld() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        pos.set(unit(0, 1));
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            Vec384f stale = pos.get().clone();
            LatentPos rest = new LatentPos();
            rest.set(stale);
            pos.applySyncPacket(serialized(rest, 1));
            Vec384f moved = advanced(stale, 4);
            pos.set(moved);
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            pos.set(advanced(stale, 1));
            ClientPoseSync.beginPrediction(pos, basis, nav, true);

            check(!ClientPoseSync.consumeTeleportCorrection(), "no teleport mark before position-look");
            ClientPoseSync.markTeleport();
            // Without the teleport mark this stale pose would be held as in-flight lag; the pending
            // position-look teleport invalidates the prediction context, so it must be accepted.
            LatentPos teleported = new LatentPos();
            teleported.set(moved);
            pos.applySyncPacket(serialized(teleported, 2));
            check(pos.get().squareDistance(moved) == 0, "teleported pose sync must be accepted even when it matches the pending trail");
            check(ClientPoseSync.consumeTeleportCorrection(), "teleport mark is consumed exactly once");
            check(!ClientPoseSync.consumeTeleportCorrection(), "teleport mark does not linger");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** Nav syncs that reordered ahead of the pose sync (epoch change) mark real corrections. */
    private static void reorderedEpochTransitionIsAccepted() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        pos.set(unit(0, 1));
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            // Late anchor discovery re-anchors q with a small offset and resets the motion epoch in
            // the same transaction; the nav sync arrives before the q sync on the wire.
            LatentPos rest = new LatentPos();
            rest.set(pos.get());
            pos.applySyncPacket(serialized(rest, 1));
            Vec384f moved = advanced(pos.get(), 4);
            pos.set(moved);
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            nav.setSemanticReady(true); // epoch reset accompanies the anchor correction

            LatentPos anchored = new LatentPos();
            anchored.set(advanced(unit(0, 1), 0.5));
            pos.applySyncPacket(serialized(anchored, 2));
            check(pos.get().squareDistance(anchored.get()) == 0,
                    "anchor correction with reordered epoch transition must never be held as lag");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** Peers and prediction-less contexts always take the authoritative value. */
    private static void peersAndIdleContextsTakeAuthoritativeValue() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        pos.set(unit(0, 1));
        ClientPoseSync.install();
        LatentPos peer = new LatentPos();
        peer.set(unit(0, 1));
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            // A peer component instance is never the predicted local pose.
            LatentPos peerIncoming = new LatentPos();
            peerIncoming.set(unit(1, 3));
            peer.applySyncPacket(serialized(peerIncoming, 1));
            check(peer.get().squareDistance(unit(1, 3)) == 0, "peer sync must always apply");

            // Free flight / unanchored contexts do not predict; nothing may be held.
            ClientPoseSync.beginPrediction(pos, basis, nav, false);
            LatentPos idleIncoming = new LatentPos();
            idleIncoming.set(unit(2, 4));
            pos.applySyncPacket(serialized(idleIncoming, 2));
            check(pos.get().squareDistance(unit(2, 4)) == 0, "prediction-less sync must always apply");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    /** Basis syncs follow the same ordering rule with the navigation basis-error metric. */
    private static void basisSyncsFollowOrderingRule() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Basis384f rotated = new Basis384f(unit(0, 1), unit(1, 1), unit(2, 1));
        basis.set(rotated);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);
            // Establish the frontier with the pre-rotation basis, then rotate further.
            LatentBasis rest = new LatentBasis(rotated);
            basis.applySyncPacket(serialized(rest, 1));
            Basis384f further = new Basis384f(unit(0, 1), unit(2, 1), unit(1, 1));
            basis.set(further);

            // A sync still carrying the pre-rotation basis is held inside the direct lag bound.
            LatentBasis stale = new LatentBasis(rotated);
            basis.applySyncPacket(serialized(stale, 2));
            check(basis.get().i.squareDistance(further.i) == 0 && basis.get().j.squareDistance(further.j) == 0,
                    "lagging basis sync must not roll the predicted basis back");

            // A fresh authoritative basis correction is applied.
            Basis384f blended = new Basis384f(unit(1, 1), unit(0, 1), unit(2, 1));
            LatentBasis authoritative = new LatentBasis(blended);
            basis.applySyncPacket(serialized(authoritative, 3));
            check(basis.get().i.squareDistance(blended.i) == 0, "authoritative basis correction must be applied");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    public static void main(String[] args) {
        reconciliationDecisions();
        unfilteredSyncAppliesVerbatim();
        writeSyncPacketEmbedsMonotonicSequence();
        predictionHoldsLaggingSyncsAndAcceptsCorrections();
        sameEpochReorderIsNeverAdopted();
        multiTickDeepSteeringUsesComposableBound();
        backtrackingDoesNotAdoptDelayedOutboundPoses();
        backtrackingBasisDoesNotAdoptDelayedTurns();
        closedLoopRetainsPendingSuffixAndIndependentBasis();
        offPathCorrectionsResetOnlyTheirOwnTrail();
        predictionBreakDiscardsExcursionHistory();
        teleportCorrectionsAreNeverHeld();
        reorderedEpochTransitionIsAccepted();
        peersAndIdleContextsTakeAuthoritativeValue();
        basisSyncsFollowOrderingRule();
        System.out.println("Pose ordering regressions passed: " + checks + " checks");
    }
}
