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
 * re-broadcasts it on a fixed cadence; those snapshots lag the locally predicted pose by the
 * in-flight movement, and applying them verbatim rolled the predicted spirit pose back to a stale
 * snapshot on every cadence tick (movement jitter / rubber banding of the projected world).
 * These tests pin the client ordering guard: lagging syncs are held, genuine corrections
 * (anchors, captures, touch blends, teleports, epoch transitions) are always applied.
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

    private static RegistryByteBuf serialized(LatentPos pos) {
        NbtCompound tag = new NbtCompound();
        pos.writeToNbt(tag, null);
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        buf.writeNbt(tag);
        return buf;
    }

    private static RegistryByteBuf serialized(LatentBasis basis) {
        NbtCompound tag = new NbtCompound();
        basis.writeToNbt(tag, null);
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        buf.writeNbt(tag);
        return buf;
    }

    /** Decision core: only unacknowledged movement can justify holding a sync. */
    private static void reconciliationDecisions() {
        // A sync whose lag is fully explained by in-flight movement is held.
        check(!PoseSyncReconciliation.acceptPosition(0.0416, 0.05), "lagging position sync within pending movement must be held");
        // Any divergence beyond the pending movement is a genuine correction.
        check(PoseSyncReconciliation.acceptPosition(0.06, 0.05), "position divergence beyond pending movement must be accepted");
        check(PoseSyncReconciliation.acceptPosition(0.5, 0), "large stationary divergence must be accepted");
        // Non-finite input is never a reason to hold an authoritative value.
        check(PoseSyncReconciliation.acceptPosition(Double.NaN, 0), "non-finite divergence must be accepted");
        check(PoseSyncReconciliation.acceptBasis(Double.POSITIVE_INFINITY, 1), "infinite basis divergence must be accepted");
        // Basis metric analogue.
        check(!PoseSyncReconciliation.acceptBasis(5e-5, 1e-3), "lagging basis sync within pending movement must be held");
        check(PoseSyncReconciliation.acceptBasis(1e-2, 1e-3), "basis divergence beyond pending movement must be accepted");
        // Float noise below the tolerance is held even with no pending movement (values equal in practice).
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
            component.applySyncPacket(serialized(incoming));
            check(component.get().squareDistance(unit(1, 2)) == 0, "unfiltered position sync must apply verbatim");

            LatentBasis basis = new LatentBasis();
            Basis384f rotated = new Basis384f(unit(0, 1), unit(2, 1), unit(1, 1));
            basis.set(rotated);
            LatentBasis basisIncoming = new LatentBasis(new Basis384f());
            basis.applySyncPacket(serialized(basisIncoming));
            check(basis.get().i.squareDistance(new Basis384f().i) == 0, "unfiltered basis sync must apply verbatim");
        } finally {
            LatentSync.clear();
        }
    }

    /** Full prediction/sync simulation: lagging cadence syncs are held, corrections accepted. */
    private static void predictionHoldsLaggingSyncsAndAcceptsCorrections() {
        LatentPos pos = new LatentPos();
        LatentBasis basis = new LatentBasis();
        SpiritNavigation nav = new SpiritNavigation();
        Vec384f q0 = Vec384f.ZERO();
        pos.set(q0);
        ClientPoseSync.install();
        try {
            ClientPoseSync.beginPrediction(pos, basis, nav, true);

            // One server cadence of ordinary walking: the client integrates ~4 blocks immediately.
            Vec384f predicted = advanced(q0, 4);
            Basis384f basisBefore = basis.get().clone();
            pos.set(predicted);
            ClientPoseSync.noteIntegration(q0, pos.get(), basisBefore, basis.get());

            // The cadence sync still carries the pose integrated BEFORE this movement: it must be
            // held, not applied as a rollback of the predicted spirit pose.
            LatentPos lagging = new LatentPos();
            lagging.set(q0);
            pos.applySyncPacket(serialized(lagging));
            check(pos.get().squareDistance(predicted) == 0, "lagging cadence sync must not roll the predicted pose back");

            // Partial catch-up keeps holding with a decaying unacknowledged remainder.
            LatentPos halfway = new LatentPos();
            halfway.set(advanced(q0, 2));
            pos.applySyncPacket(serialized(halfway));
            check(pos.get().squareDistance(predicted) == 0, "partially lagging sync must still be held");

            // The server caught up: applying it is a no-op, and pending decays to zero.
            LatentPos caughtUp = new LatentPos();
            caughtUp.set(predicted);
            pos.applySyncPacket(serialized(caughtUp));
            check(pos.get().squareDistance(predicted) == 0, "caught-up sync keeps the predicted pose");

            // A genuine correction far beyond any in-flight movement is always applied.
            LatentPos corrected = new LatentPos();
            corrected.set(unit(5, 0.5f));
            pos.applySyncPacket(serialized(corrected));
            check(pos.get().squareDistance(unit(5, 0.5f)) == 0, "authoritative correction must be applied");
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
            Vec384f moved = advanced(pos.get(), 4);
            pos.set(moved);
            ClientPoseSync.noteIntegration(stale, pos.get(), basis.get(), basis.get());

            check(!ClientPoseSync.consumeTeleportCorrection(), "no teleport mark before position-look");
            ClientPoseSync.markTeleport();
            // Without the teleport mark this stale pose would be held as in-flight lag; the pending
            // position-look teleport invalidates the prediction context, so it must be accepted.
            LatentPos teleported = new LatentPos();
            teleported.set(stale);
            pos.applySyncPacket(serialized(teleported));
            check(pos.get().squareDistance(stale) == 0, "teleported pose sync must be accepted while unprocessed");
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
            Vec384f predicted = pos.get().clone();
            Vec384f moved = advanced(pos.get(), 4);
            pos.set(moved);
            ClientPoseSync.noteIntegration(predicted, pos.get(), basis.get(), basis.get());
            nav.setSemanticReady(true); // epoch reset accompanies the anchor correction

            LatentPos anchored = new LatentPos();
            anchored.set(advanced(unit(0, 1), 0.5));
            pos.applySyncPacket(serialized(anchored));
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
            peer.applySyncPacket(serialized(peerIncoming));
            check(peer.get().squareDistance(unit(1, 3)) == 0, "peer sync must always apply");

            // Free flight / unanchored contexts do not predict; nothing may be held.
            ClientPoseSync.beginPrediction(pos, basis, nav, false);
            LatentPos idleIncoming = new LatentPos();
            idleIncoming.set(unit(2, 4));
            pos.applySyncPacket(serialized(idleIncoming));
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
            // Deep movement rotates the predicted basis; record the integration.
            Basis384f further = new Basis384f(unit(0, 1), unit(2, 1), unit(1, 1));
            Basis384f before = basis.get().clone();
            basis.set(further);
            ClientPoseSync.noteIntegration(Vec384f.ZERO(), Vec384f.ZERO(), before, basis.get());

            // A sync still carrying the pre-rotation basis is held.
            LatentBasis stale = new LatentBasis(rotated);
            basis.applySyncPacket(serialized(stale));
            check(basis.get().i.squareDistance(further.i) == 0 && basis.get().j.squareDistance(further.j) == 0,
                    "lagging basis sync must not roll the predicted basis back");

            // A genuine authoritative basis correction (e.g. touch blend target) is applied.
            Basis384f blended = new Basis384f(unit(1, 1), unit(0, 1), unit(2, 1));
            LatentBasis authoritative = new LatentBasis(blended);
            basis.applySyncPacket(serialized(authoritative));
            check(basis.get().i.squareDistance(blended.i) == 0, "authoritative basis correction must be applied");
        } finally {
            ClientPoseSync.clear();
            LatentSync.clear();
        }
    }

    public static void main(String[] args) {
        reconciliationDecisions();
        unfilteredSyncAppliesVerbatim();
        predictionHoldsLaggingSyncsAndAcceptsCorrections();
        teleportCorrectionsAreNeverHeld();
        reorderedEpochTransitionIsAccepted();
        peersAndIdleContextsTakeAuthoritativeValue();
        basisSyncsFollowOrderingRule();
        System.out.println("Pose ordering regressions passed: " + checks + " checks");
    }
}
