package io.github.mysticism.navigation;

/**
 * Attachment policy for a pending DEEP walk request. A source floor can be steep in the current
 * projection: alignment must START before the projected normal can reach the acquisition gate.
 * Unsupported side/edge grazes wait; alignable floors attach, and moving requests follow the
 * actual contacted locality. Only a locality change under a settled player cancels the request.
 * Terrain still guards every basis step and revalidates the projected floor at final acquisition.
 */
public final class DeepWalkAttachment {
    /** Final projected-normal threshold, matching terrain acquisition's double-precision .99. */
    public static final double MIN_WALK_NORMAL_Y = .99;
    /** Movement threshold matching the existing per-tick movement epsilon used for touch blending. */
    public static final double MOVING_EPSILON = 1e-5;

    public enum Decision {
        /** No alignable source floor: leave the pending request untouched. */
        WAIT,
        /** First alignable source floor: attach the request to that locality. */
        ATTACH,
        /** Contact is the already-attached locality: continue the request unchanged. */
        KEEP,
        /** A different locality was contacted while the player still moves: re-attach to it. */
        FOLLOW,
        /** The attached locality changed under a settled player: cancel and request again. */
        CANCEL
    }

    private DeepWalkAttachment() {}

    /** Final acquisition only, NOT permission to begin guarded source-grid alignment. */
    public static boolean walkable(double projectedNormalY) {
        return Double.isFinite(projectedNormalY) && projectedNormalY >= MIN_WALK_NORMAL_Y;
    }

    /** A real ground contact on a source floor, even if that floor is currently tilted in projection. */
    public static boolean alignable(double projectedNormalY, double sourceNormalY) {
        return Double.isFinite(projectedNormalY) && projectedNormalY > .3
                && Double.isFinite(sourceNormalY) && sourceNormalY >= MIN_WALK_NORMAL_Y;
    }

    /** Real per-tick physical movement. Non-finite deltas (never produced by the evolver) count as settled. */
    public static boolean moving(double deltaX, double deltaY, double deltaZ) {
        return Double.isFinite(deltaX) && Double.isFinite(deltaY) && Double.isFinite(deltaZ)
                && deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > MOVING_EPSILON;
    }

    /**
     * @param attached true when the request is currently attached to a locality
     * @param sameLocality current window identity, landmark, source dimension and grid are unchanged
     * @param moving true when the player physically moved this tick
     * @param projectedNormalY current contact normal Y in the visible/collidable projection
     * @param sourceNormalY the same contact normal pulled back through its producing cell into source space
     */
    public static Decision evaluate(boolean attached, boolean sameLocality, boolean moving,
                                    double projectedNormalY, double sourceNormalY) {
        if (!alignable(projectedNormalY, sourceNormalY)) return Decision.WAIT;
        if (!attached) return Decision.ATTACH;
        if (sameLocality) return Decision.KEEP;
        return moving ? Decision.FOLLOW : Decision.CANCEL;
    }
}
