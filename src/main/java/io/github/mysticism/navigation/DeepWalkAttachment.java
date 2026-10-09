package io.github.mysticism.navigation;

/**
 * Attachment policy for a pending DEEP walk request.
 *
 * A deep-flying player who requests a walk is still MOVING through the projection: they descend,
 * graze slopes and fly across several projected source localities before settling. The request
 * therefore must not lock onto the first transient terrain contact, and it must not cancel when
 * the contacted locality changes underneath the moving player. Instead:
 *
 * <ul>
 *   <li>Only an acquisition-compatible contact (near-horizontal normal, matching walk
 *       acquisition's own {@code normal().y >= .99} gate) may attach the request to a locality.
 *       Steep grazing contacts pass the generic ground filter but can never complete walk
 *       acquisition, so locking onto them could only waste the request budget.</li>
 *   <li>While the player keeps moving, a changed contacted locality RE-ATTACHES the request
 *       (follows the player through the projection) instead of cancelling it.</li>
 *   <li>Only a locality change under a settled player cancels the request: terrain genuinely
 *       changed underfoot, and a fresh request must validate the new support.</li>
 * </ul>
 *
 * Pure Java: no Minecraft types, so regressions can exercise every branch without a server.
 */
public final class DeepWalkAttachment {
    /** Matches walk acquisition's own refusal of contacts with {@code current.normal().y < .99}. */
    public static final float MIN_WALK_NORMAL_Y = .99f;
    /** Movement threshold matching the existing per-tick movement epsilon used for touch blending. */
    public static final double MOVING_EPSILON = 1e-5;

    public enum Decision {
        /** No (or no acquisition-compatible) contact: leave the pending request untouched. */
        WAIT,
        /** First acquisition-compatible contact: attach the request to that locality. */
        ATTACH,
        /** Contact is the already-attached locality: continue the request unchanged. */
        KEEP,
        /** A different locality was contacted while the player still moves: re-attach to it. */
        FOLLOW,
        /** The attached locality changed under a settled player: cancel and request again. */
        CANCEL
    }

    private DeepWalkAttachment() {}

    /** A contact can only anchor a walk request if acquisition could actually commit to it. */
    public static boolean walkable(double contactNormalY) { return contactNormalY >= MIN_WALK_NORMAL_Y; }

    /** Real per-tick physical movement. Non-finite deltas (never produced by the evolver) count as settled. */
    public static boolean moving(double deltaX, double deltaY, double deltaZ) {
        return Double.isFinite(deltaX) && Double.isFinite(deltaY) && Double.isFinite(deltaZ)
                && deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > MOVING_EPSILON;
    }

    /**
     * @param attached true when the request is currently attached to a locality
     * @param sameLocality true when the current contact is the attached locality (window identity,
     *     landmark, source dimension and grid basis all unchanged)
     * @param moving true when the player physically moved this tick
     * @param contactNormalY the current contact's ground normal Y component
     */
    public static Decision evaluate(boolean attached, boolean sameLocality, boolean moving, double contactNormalY) {
        if (!walkable(contactNormalY)) return Decision.WAIT;
        if (!attached) return Decision.ATTACH;
        if (sameLocality) return Decision.KEEP;
        return moving ? Decision.FOLLOW : Decision.CANCEL;
    }
}
