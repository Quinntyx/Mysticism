package io.github.mysticism.client.spiritworld;

import io.github.mysticism.component.LatentBasis;
import io.github.mysticism.component.LatentPos;
import io.github.mysticism.component.LatentSync;
import io.github.mysticism.component.PoseSyncReconciliation;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client ordering guard for authoritative semantic-pose synchronization.
 *
 * <p>The server rebuilds the local player's movement-integrated q/basis and re-broadcasts them on a
 * fixed cadence (and at correction events). Those snapshots lag the locally predicted pose by the
 * in-flight movement, so applying them verbatim rolls the predicted spirit pose back on every
 * cadence tick — the visible rubber banding of the projected world.
 *
 * <p>Reconciliation is ordering-first. Each sync carries the server's monotonic wire sequence
 * ({@link LatentPos#writeSyncPacket}); a snapshot that is not newer than everything already
 * received is a reorder/duplicate and is never adopted — its content cannot be fresher authority
 * than the newest snapshot already seen, whatever its divergence from the prediction. A fresh
 * snapshot is held only while it stays inside the lag of the acknowledged frontier: the direct,
 * composable distance from the prediction to the freshest previously received pose. A fresh
 * snapshot moving away from the prediction beyond that lag is a genuine authoritative correction
 * (anchor/capture, touch blend, support alignment) and is applied immediately. Unhealthy contexts —
 * epoch transitions, unprocessed position-look teleports, free flight, peers — always take the
 * authoritative value.
 */
@Environment(EnvType.CLIENT)
public final class ClientPoseSync {
    private ClientPoseSync() {}

    private static LatentPos localPos;
    private static LatentBasis localBasis;
    private static SpiritNavigation localNavigation;
    private static long predictedEpoch;
    private static boolean predicting;
    /** A position-look teleport was applied to the local player since the last prediction tick. */
    private static boolean teleported;

    // Wire ordering state: persists across prediction segments, reset only with the connection.
    private static int positionSequence;
    private static int basisSequence;

    // Freshest authoritative pose received (null only before the first sync of the connection).
    private static Vec384f positionFrontier;
    private static Basis384f basisFrontier;

    /** Installs the component sync filters. Called once from {@link ClientLatentPredictor#init()}. */
    public static void install() {
        LatentSync.install(ClientPoseSync::reconcilePosition, ClientPoseSync::reconcileBasis);
    }

    /**
     * Publishes the current prediction context from the predictor tick. The acknowledgment frontier
     * (freshest received pose) stays valid across prediction breaks — the unhealthy-accept path
     * keeps advancing it while no prediction is running, so resuming prediction inherits the correct
     * lag budget instead of snapping to the first arriving snapshot.
     */
    public static void beginPrediction(LatentPos pos, LatentBasis basis, SpiritNavigation navigation, boolean predicting) {
        localPos = pos;
        localBasis = basis;
        localNavigation = navigation;
        predictedEpoch = navigation.motionEpoch();
        ClientPoseSync.predicting = predicting;
    }

    /** Clears the local-player context (disconnect, world change, no local player). */
    public static void clear() {
        localPos = null;
        localBasis = null;
        localNavigation = null;
        predicting = false;
        teleported = false;
        positionSequence = 0;
        basisSequence = 0;
        positionFrontier = null;
        basisFrontier = null;
    }

    /** Marks that an authoritative position-look teleport was applied to the local player. */
    public static void markTeleport() {
        teleported = true;
    }

    /**
     * Consumed once per predictor tick: a teleported pose is a correction, never chosen movement,
     * so the prediction baseline is reset and the tick's delta is not integrated. The acknowledgment
     * frontier stays valid — a teleport moves the carrier, not the semantic pose.
     */
    public static boolean consumeTeleportCorrection() {
        boolean corrected = teleported;
        teleported = false;
        return corrected;
    }

    private static boolean healthy(LatentPos posTarget, LatentBasis basisTarget) {
        if (!predicting || localNavigation == null) return false;
        // An unprocessed position-look teleport invalidates the prediction context until the
        // predictor re-baselines: the authoritative value already contains the correction.
        if (teleported) return false;
        // A nav sync that reordered ahead of this pose sync marks a mode/anchor correction: accept.
        if (localNavigation.motionEpoch() != predictedEpoch) return false;
        if (posTarget != null && posTarget != localPos) return false;
        if (basisTarget != null && basisTarget != localBasis) return false;
        return true;
    }

    private static Vec384f reconcilePosition(LatentPos target, int sequence, Vec384f current, Vec384f incoming) {
        if (target != localPos) return incoming; // peer component: always the authoritative value
        boolean stale = positionSequence != 0 && sequence <= positionSequence;
        if (sequence > positionSequence) positionSequence = sequence;
        if (stale) return current; // reordered/duplicate snapshot: never adopt, whatever its divergence
        if (!healthy(target, null) || positionFrontier == null) {
            positionFrontier = incoming.clone();
            return incoming;
        }
        double divergence = Math.sqrt((double) incoming.squareDistance(current));
        // Composable lag bound: direct distance from the prediction to the freshest previously
        // received snapshot. Never accumulated from per-step distances.
        double lagBound = Math.sqrt((double) current.squareDistance(positionFrontier));
        positionFrontier = incoming.clone();
        if (PoseSyncReconciliation.acceptPosition(divergence, lagBound)) return incoming;
        return current;
    }

    private static Basis384f reconcileBasis(LatentBasis target, int sequence, Basis384f current, Basis384f incoming) {
        if (target != localBasis) return incoming; // peer component: always the authoritative value
        boolean stale = basisSequence != 0 && sequence <= basisSequence;
        if (sequence > basisSequence) basisSequence = sequence;
        if (stale) return current; // reordered/duplicate snapshot: never adopt, whatever its divergence
        if (!healthy(null, target) || basisFrontier == null) {
            basisFrontier = incoming.clone();
            return incoming;
        }
        double divergence = basisError(current, incoming);
        double lagBound = basisError(current, basisFrontier);
        basisFrontier = incoming.clone();
        if (PoseSyncReconciliation.acceptBasis(divergence, lagBound)) return incoming;
        return current;
    }

    /** Navigation's basis error metric: summed per-axis square distances. */
    private static double basisError(Basis384f a, Basis384f b) {
        return (double) a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }
}
