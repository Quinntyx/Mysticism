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
 * cadence tick — the visible rubber banding of the projected world. This guard holds a sync whose
 * divergence from the prediction is fully explained by unacknowledged movement, and accepts every
 * sync that is not (anchor/capture corrections, touch blends, teleported corrections, peers).
 *
 * <p>The {@link ClientLatentPredictor} keeps this state current: it reports the live prediction
 * context each tick, accumulates unacknowledged movement as it integrates, and marks pose
 * corrections (position-look teleports) so the next syncs are never misjudged as lagging movement.
 */
@Environment(EnvType.CLIENT)
public final class ClientPoseSync {
    private ClientPoseSync() {}

    private static LatentPos localPos;
    private static LatentBasis localBasis;
    private static SpiritNavigation localNavigation;
    private static long predictedEpoch;
    private static boolean predicting;
    /** Semantic movement integrated client-side that no accepted authoritative sync has acknowledged. */
    private static double pendingPosition;
    /** Integrated basis divergence (sum of per-axis square distances) not yet acknowledged. */
    private static double pendingBasis;
    /** A position-look teleport was applied to the local player since the last prediction tick. */
    private static boolean teleported;

    /** Installs the component sync filters. Called once from {@link ClientLatentPredictor#init()}. */
    public static void install() {
        LatentSync.install(ClientPoseSync::reconcilePosition, ClientPoseSync::reconcileBasis);
    }

    /**
     * Publishes the current prediction context from the predictor tick. When the predictor is not
     * maintaining a movement-integrated pose (free flight, unanchored, mode transition, correction
     * tick), unacknowledged movement is dropped: without fresh prediction there is nothing to hold
     * a sync against, and the authoritative value is always the best available pose.
     */
    public static void beginPrediction(LatentPos pos, LatentBasis basis, SpiritNavigation navigation, boolean predicting) {
        localPos = pos;
        localBasis = basis;
        localNavigation = navigation;
        predictedEpoch = navigation.motionEpoch();
        ClientPoseSync.predicting = predicting;
        if (!predicting) resetUnacknowledged();
    }

    /** Clears the local-player context (disconnect, world change, no local player). */
    public static void clear() {
        localPos = null;
        localBasis = null;
        localNavigation = null;
        predicting = false;
        resetUnacknowledged();
        teleported = false;
    }

    /** Records one accepted prediction integration of the live component pose. */
    public static void noteIntegration(Vec384f qBefore, Vec384f qAfter, Basis384f basisBefore, Basis384f basisAfter) {
        pendingPosition += Math.sqrt((double) qBefore.squareDistance(qAfter));
        pendingBasis += basisError(basisBefore, basisAfter);
    }

    /** Marks that an authoritative position-look teleport was applied to the local player. */
    public static void markTeleport() {
        teleported = true;
    }

    /**
     * Consumed once per predictor tick: a teleported pose is a correction, never chosen movement,
     * so the prediction baseline is reset and the tick's delta is not integrated.
     */
    public static boolean consumeTeleportCorrection() {
        boolean corrected = teleported;
        teleported = false;
        if (corrected) resetUnacknowledged();
        return corrected;
    }

    private static void resetUnacknowledged() {
        pendingPosition = 0;
        pendingBasis = 0;
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

    private static Vec384f reconcilePosition(LatentPos target, Vec384f current, Vec384f incoming) {
        if (!healthy(target, null)) return incoming;
        double divergence = Math.sqrt((double) incoming.squareDistance(current));
        if (!PoseSyncReconciliation.acceptPosition(divergence, pendingPosition)) {
            pendingPosition = divergence; // only the unacknowledged remainder can still be in flight
            return current;
        }
        pendingPosition = 0;
        return incoming;
    }

    private static Basis384f reconcileBasis(LatentBasis target, Basis384f current, Basis384f incoming) {
        if (!healthy(null, target)) return incoming;
        double divergence = basisError(current, incoming);
        if (!PoseSyncReconciliation.acceptBasis(divergence, pendingBasis)) {
            pendingBasis = divergence;
            return current;
        }
        pendingBasis = 0;
        return incoming;
    }

    /** Navigation's basis error metric: summed per-axis square distances. */
    private static double basisError(Basis384f a, Basis384f b) {
        return (double) a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }
}
