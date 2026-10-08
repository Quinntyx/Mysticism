package io.github.mysticism.component;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import java.util.Objects;

/**
 * Client-installed ordering guard for authoritative {@link LatentPos}/{@link LatentBasis} syncs.
 *
 * <p>The server re-broadcasts the movement-integrated semantic pose on a fixed cadence (and at
 * correction events). Those packets are built from movement packets the server has already received,
 * so on any real connection they lag the locally predicted pose by the in-flight movement. Applying
 * them verbatim rolls the predicted spirit pose (q/basis) back to a stale snapshot every cadence
 * tick, which the projection then renders as rubber banding. The filter lets the client hold a sync
 * whose divergence from the prediction is fully explained by unacknowledged movement, and accepts
 * every sync that carries a genuine authoritative correction.
 *
 * <p>Common-source only; with no client filter installed, sync application is unchanged.
 */
public final class LatentSync {
    /** @return the pose the client component should adopt for this authoritative position sync. */
    public interface PositionFilter {
        Vec384f apply(LatentPos target, Vec384f current, Vec384f incoming);
    }

    /** @return the pose the client component should adopt for this authoritative basis sync. */
    public interface BasisFilter {
        Basis384f apply(LatentBasis target, Basis384f current, Basis384f incoming);
    }

    private static volatile PositionFilter position;
    private static volatile BasisFilter basis;

    private LatentSync() {}

    public static void install(PositionFilter positionFilter, BasisFilter basisFilter) {
        position = Objects.requireNonNull(positionFilter);
        basis = Objects.requireNonNull(basisFilter);
    }

    public static void clear() {
        position = null;
        basis = null;
    }

    static Vec384f reconcile(LatentPos target, Vec384f current, Vec384f incoming) {
        PositionFilter filter = position;
        return filter == null ? incoming : filter.apply(target, current, incoming);
    }

    static Basis384f reconcile(LatentBasis target, Basis384f current, Basis384f incoming) {
        BasisFilter filter = basis;
        return filter == null ? incoming : filter.apply(target, current, incoming);
    }
}
