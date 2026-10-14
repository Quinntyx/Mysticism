package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.vector.Vec384f;
import java.util.Optional;

/** Carrier rebasing shared by restore and accepted async source delivery, without mutating snapshots. */
public final class SourceAnchorDelivery {
    private SourceAnchorDelivery() {}
    /** Saved permission and the independently decoded native coordinate must both survive.
     * Terrain may restore before NAV's join callback revokes stale permission, so it cannot
     * rely on semanticReady alone (nor infer readiness from a nonzero/default vector).
     */
    public static boolean canRestoreAnchor(boolean semanticReady, boolean coordinateAvailable) {
        return semanticReady && coordinateAvailable;
    }
    public static Vec384f restoreCoordinate(boolean coordinateReady, Vec384f current, Vec384f sourceCoordinate) {
        return (coordinateReady ? sourceCoordinate : current).clone();
    }
    public static Optional<Vec384f> acceptedCoordinate(SpiritNavigation nav, String dimension, String owner,
                                                       Vec384f carrier, Vec384f before, Vec384f after) {
        if (!nav.sourceAnchorMatches(dimension, owner)) return Optional.empty();
        // Keep carrier - q invariant; acceptance changes semantic origin, not visible physical pose.
        return Optional.of(carrier.clone().add(after.clone().sub(before)));
    }
}
