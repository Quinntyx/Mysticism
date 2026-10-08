package io.github.mysticism.component;

/**
 * Ordering decision core for authoritative semantic-pose synchronization.
 *
 * <p>Reordered or duplicated snapshots are rejected before this core runs: every sync packet now
 * carries a per-component monotonic wire sequence written by the server
 * ({@link LatentPos#writeSyncPacket}), and {@code ClientPoseSync} never adopts a snapshot whose
 * sequence is not newer than everything already received — an older snapshot can never carry better
 * authority than a newer one already seen, regardless of its content.
 *
 * <p>For a fresh (ordering-verified) snapshot the only legitimate reason to hold it is in-flight
 * lag: the snapshot was integrated from movement the client predicted before the server received
 * it. The lag budget is the <em>composable direct distance</em> from the current prediction to the
 * freshest previously received snapshot — recomputed on demand, never accumulated from per-step
 * distances. Per-step squared sums do not bound cumulative divergence (N same-direction rotations
 * of θ each accumulate ~Nθ² step-wise but ~ (Nθ)² directly), so an accumulated budget under-counts
 * multi-tick steering and lets stale snapshots through as "corrections".
 *
 * <p>Both quantities are measured in semantic units: position distance is the Euclidean distance of
 * the pose vectors (one unit projects to {@code TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT} blocks);
 * basis divergence is the sum of per-axis square distances, matching the navigation controller's own
 * basis error metric.
 */
public final class PoseSyncReconciliation {
    /**
     * Divergence below this is indistinguishable from float noise across the sync round trip
     * (~0.1 projected block). Lag up to this slack never justifies rejecting a fresh snapshot.
     */
    public static final double POSITION_LAG_TOLERANCE = 1e-3;
    /** Same slack for the summed per-axis square-distance basis metric. */
    public static final double BASIS_LAG_TOLERANCE = 1e-4;

    private PoseSyncReconciliation() {}

    /**
     * @param divergence distance between the fresh authoritative snapshot and the locally predicted pose
     * @param lagBound   direct distance from the prediction to the freshest previously received
     *                   snapshot (the unacknowledged in-flight movement it may legitimately lag by)
     * @return true when the fresh snapshot moved away from the prediction beyond explainable lag
     *         (a genuine authoritative correction) and must be applied; false when it is still
     *         inside the acknowledged frontier's lag, where holding the prediction is safe and the
     *         snapshot would only roll it back
     */
    public static boolean acceptPosition(double divergence, double lagBound) {
        if (!Double.isFinite(divergence) || !Double.isFinite(lagBound)) return true;
        return divergence > lagBound + POSITION_LAG_TOLERANCE;
    }

    /** Basis analogue of {@link #acceptPosition(double, double)}. */
    public static boolean acceptBasis(double divergence, double lagBound) {
        if (!Double.isFinite(divergence) || !Double.isFinite(lagBound)) return true;
        return divergence > lagBound + BASIS_LAG_TOLERANCE;
    }
}
