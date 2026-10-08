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

import java.util.ArrayDeque;
import java.util.Arrays;

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
 * snapshot on the unacknowledged prediction trail is movement lag even when the player has
 * reversed direction: distance to the last received pose alone cannot bound that lag. The trail
 * retains tick endpoints and matches intermediate server steps on their segments; acknowledgment
 * retires only the prefix actually reached, not later predicted movement. Otherwise the direct,
 * composable distance to the freshest previously received pose still bounds ordinary catch-up.
 * A snapshot outside both explanations is an authoritative correction and is applied immediately.
 * Unhealthy contexts —
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

    // Independent streams: acknowledging q must not retire pending basis rotations, or vice versa.
    private static final PredictionTrail positionTrail = new PredictionTrail(
            PoseSyncReconciliation.POSITION_LAG_TOLERANCE * PoseSyncReconciliation.POSITION_LAG_TOLERANCE);
    private static final PredictionTrail basisTrail = new PredictionTrail(PoseSyncReconciliation.BASIS_LAG_TOLERANCE);

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
        if (!predicting || !ClientPoseSync.predicting || localPos != pos || localBasis != basis
                || localNavigation != navigation || predictedEpoch != navigation.motionEpoch() || teleported) {
            positionTrail.clear();
            basisTrail.clear();
        }
        localPos = pos;
        localBasis = basis;
        localNavigation = navigation;
        predictedEpoch = navigation.motionEpoch();
        ClientPoseSync.predicting = predicting;
        // Called AFTER integration on every predictor tick, so an outbound endpoint survives a
        // reversal before the next cadence snapshot arrives. Stored coordinates never alias q/basis.
        if (predicting && !teleported) {
            positionTrail.record(pos.get().data());
            basisTrail.record(basisCoordinates(basis.get()));
        }
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
        positionTrail.clear();
        basisTrail.clear();
    }

    /** Marks that an authoritative position-look teleport was applied to the local player. */
    public static void markTeleport() {
        teleported = true;
        positionTrail.clear();
        basisTrail.clear();
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
            positionTrail.reset(incoming.data());
            return incoming;
        }
        boolean predictedSnapshot = positionTrail.acknowledge(incoming.data());
        double divergence = Math.sqrt((double) incoming.squareDistance(current));
        // Direct fallback for catch-up, not a sum of per-step squared distances. A reversal may
        // exceed it, but an acknowledged point on the pending trail is still delayed movement.
        double lagBound = Math.sqrt((double) current.squareDistance(positionFrontier));
        positionFrontier = incoming.clone();
        if (!predictedSnapshot && PoseSyncReconciliation.acceptPosition(divergence, lagBound)) {
            positionTrail.reset(incoming.data());
            return incoming;
        }
        return current;
    }

    private static Basis384f reconcileBasis(LatentBasis target, int sequence, Basis384f current, Basis384f incoming) {
        if (target != localBasis) return incoming; // peer component: always the authoritative value
        boolean stale = basisSequence != 0 && sequence <= basisSequence;
        if (sequence > basisSequence) basisSequence = sequence;
        if (stale) return current; // reordered/duplicate snapshot: never adopt, whatever its divergence
        if (!healthy(null, target) || basisFrontier == null) {
            basisFrontier = incoming.clone();
            basisTrail.reset(basisCoordinates(incoming));
            return incoming;
        }
        boolean predictedSnapshot = basisTrail.acknowledge(basisCoordinates(incoming));
        double divergence = basisError(current, incoming);
        double lagBound = basisError(current, basisFrontier);
        basisFrontier = incoming.clone();
        if (!predictedSnapshot && PoseSyncReconciliation.acceptBasis(divergence, lagBound)) {
            basisTrail.reset(basisCoordinates(incoming));
            return incoming;
        }
        return current;
    }

    /** Flatten without normalization/truncation: Euclidean squared error is the basis metric. */
    private static float[] basisCoordinates(Basis384f basis) {
        float[] i = basis.i.data(), j = basis.j.data(), k = basis.k.data();
        float[] coordinates = Arrays.copyOf(i, i.length + j.length + k.length);
        System.arraycopy(j, 0, coordinates, i.length, j.length);
        System.arraycopy(k, 0, coordinates, i.length + j.length, k.length);
        return coordinates;
    }

    /**
     * Bounded local tick history, not a movement-distance budget. At most 256 distinct endpoints
     * per component (~3 MiB combined at native v2 dimensions). At rest no new entry is needed.
     * Very old unacknowledged endpoints may be evicted; the direct frontier fallback remains.
     */
    private static final class PredictionTrail {
        private static final int MAX_ENDPOINTS = 256;
        private final ArrayDeque<float[]> points = new ArrayDeque<>();
        private final double toleranceSquared;

        private PredictionTrail(double toleranceSquared) { this.toleranceSquared = toleranceSquared; }

        private void clear() { points.clear(); }
        private void reset(float[] coordinates) { clear(); record(coordinates); }

        private void record(float[] coordinates) {
            if (!points.isEmpty() && Arrays.equals(points.getLast(), coordinates)) return;
            points.addLast(coordinates); // callers supply detached arrays
            if (points.size() > MAX_ENDPOINTS) points.removeFirst();
        }

        private boolean acknowledge(float[] incoming) {
            if (points.isEmpty()) return false;
            var iterator = points.iterator();
            float[] from = iterator.next();
            if (squaredError(from, incoming) <= toleranceSquared) return true;
            int prefix = 1;
            while (iterator.hasNext()) {
                float[] to = iterator.next();
                double lengthSquared = 0, projection = 0;
                for (int axis = 0; axis < incoming.length; axis++) {
                    double delta = (double) to[axis] - from[axis];
                    lengthSquared += delta * delta;
                    projection += ((double) incoming[axis] - from[axis]) * delta;
                }
                double fraction = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, projection / lengthSquared));
                double error = 0;
                for (int axis = 0; axis < incoming.length; axis++) {
                    double delta = incoming[axis] - (from[axis] + fraction * ((double) to[axis] - from[axis]));
                    error += delta * delta;
                }
                if (error <= toleranceSquared) {
                    // Choose the EARLIEST matching segment on loops/backtracking: the same pose
                    // occurring later is not evidence that intervening movement was acknowledged.
                    for (int count = 0; count < prefix; count++) points.removeFirst();
                    if (Arrays.equals(points.getFirst(), incoming)) points.removeFirst();
                    points.addFirst(incoming.clone());
                    return true;
                }
                from = to;
                prefix++;
            }
            return false;
        }

        private static double squaredError(float[] a, float[] b) {
            double error = 0;
            for (int axis = 0; axis < a.length; axis++) {
                double delta = (double) a[axis] - b[axis];
                error += delta * delta;
            }
            return error;
        }
    }

    /** Navigation's basis error metric: summed per-axis square distances. */
    private static double basisError(Basis384f a, Basis384f b) {
        return (double) a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }
}
