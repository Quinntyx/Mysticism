package io.github.mysticism.component;

/**
 * Ordering decision core for authoritative semantic-pose synchronization.
 *
 * <p>Authoritative q/basis syncs are produced from movement the server has already integrated, so a
 * delayed or reordered sync legitimately differs from the client prediction by at most the
 * unacknowledged in-flight movement. Such syncs must NOT roll the predicted spirit pose back; the
 * client keeps its (fresher) prediction until the server demonstrably disagrees. Any divergence
 * beyond that explainable lag is a real authoritative correction (anchor, capture, blend, skipped
 * movement) and must be applied immediately.
 *
 * <p>Both quantities are measured in semantic units: position distance is the Euclidean distance of
 * the 768-d pose vectors (one unit projects to {@code TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT}
 * blocks); basis divergence is the sum of per-axis square distances, matching the navigation
 * controller's own basis error metric.
 */
public final class PoseSyncReconciliation {
    /**
     * Divergence below this is indistinguishable from float noise across the sync round trip
     * (~0.1 projected block). Pending movement up to this slack never justifies holding a sync.
     */
    public static final double POSITION_LAG_TOLERANCE = 1e-3;
    /** Same slack for the summed per-axis square-distance basis metric. */
    public static final double BASIS_LAG_TOLERANCE = 1e-4;

    private PoseSyncReconciliation() {}

    /**
     * @param divergence distance between the authoritative pose and the locally predicted pose
     * @param pending    semantic movement the client has integrated but no accepted sync has
     *                   acknowledged yet (the only legitimate source of divergence)
     * @return true when the authoritative sync must be applied; false when holding the prediction
     *         is safe because the sync is merely an in-flight lagging snapshot of it
     */
    public static boolean acceptPosition(double divergence, double pending) {
        if (!Double.isFinite(divergence) || !Double.isFinite(pending)) return true;
        return divergence > pending + POSITION_LAG_TOLERANCE;
    }

    /** Basis analogue of {@link #acceptPosition(double, double)}. */
    public static boolean acceptBasis(double divergence, double pending) {
        if (!Double.isFinite(divergence) || !Double.isFinite(pending)) return true;
        return divergence > pending + BASIS_LAG_TOLERANCE;
    }
}
