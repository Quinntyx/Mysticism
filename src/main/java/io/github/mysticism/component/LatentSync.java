package io.github.mysticism.component;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import java.util.Objects;

/**
 * Client-installed ordering guard for authoritative {@link LatentPos}/{@link LatentBasis} syncs.
 *
 * <p>Every sync packet carries a per-component monotonic wire sequence written by the server in
 * {@link LatentPos#writeSyncPacket}. The client filter uses it to reject reordered/duplicated
 * snapshots outright (an older snapshot can never carry better authority than a newer one already
 * received), and then decides between the locally predicted pose and the fresh snapshot with
 * {@link PoseSyncReconciliation}.
 *
 * <p>Common-source only; with no client filter installed, sync application is unchanged.
 */
public final class LatentSync {
    /** @return the pose the client component should adopt for this authoritative position sync. */
    public interface PositionFilter {
        Vec384f apply(LatentPos target, int sequence, Vec384f current, Vec384f incoming);
    }

    /** @return the pose the client component should adopt for this authoritative basis sync. */
    public interface BasisFilter {
        Basis384f apply(LatentBasis target, int sequence, Basis384f current, Basis384f incoming);
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

    static Vec384f reconcile(LatentPos target, int sequence, Vec384f current, Vec384f incoming) {
        PositionFilter filter = position;
        return filter == null ? incoming : filter.apply(target, sequence, current, incoming);
    }

    static Basis384f reconcile(LatentBasis target, int sequence, Basis384f current, Basis384f incoming) {
        BasisFilter filter = basis;
        return filter == null ? incoming : filter.apply(target, sequence, current, incoming);
    }
}
