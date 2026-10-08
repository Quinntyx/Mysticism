package io.github.mysticism.navigation;

import java.util.List;
import java.util.Objects;

/** Real walk-target acquisition policy for a pending walk request in deep flight.
 * A request must never spin to expiry while available terrain under the player is treated as
 * unknown: it actively acquires a discovered source target through the real bounded extraction
 * pipeline. Discovery is throttled, never concurrent, and a discovered landmark is attached to
 * the walk window only when that does not overwrite an already established source binding. */
public final class WalkTargetAcquisition {
    /** Minimum server ticks between two discovery kicks for the same pending walk request. */
    public static final int RETRY_TICKS = 40;
    public enum Discovery { START, WAIT }
    public enum Attach { BIND_NEW, REFRESH_REVISION, KEEP_BINDING }
    private WalkTargetAcquisition() {}

    /** One pending-walk decision. A running discovery is never duplicated or restarted early. */
    public static Discovery discovery(boolean inFlight, long ticksSinceKick) {
        if (inFlight) return Discovery.WAIT;
        return ticksSinceKick >= RETRY_TICKS ? Discovery.START : Discovery.WAIT;
    }

    /** Attachment decision when a real source landmark was discovered at the current contact.
     * An unbound window binds the discovery; the same landmark with changed geometry refreshes
     * its revision; a different landmark never replaces an established binding. */
    public static Attach attach(String boundId, String foundId, List<String> boundGeometryKeys, List<String> foundGeometryKeys) {
        if (foundId == null || foundId.isEmpty()) throw new IllegalArgumentException("discovery produced no landmark id");
        if (boundId == null || boundId.isEmpty()) return Attach.BIND_NEW;
        if (boundId.equals(foundId) && !keys(boundGeometryKeys).equals(keys(foundGeometryKeys))) return Attach.REFRESH_REVISION;
        return Attach.KEEP_BINDING;
    }

    private static List<String> keys(List<String> keys) { return keys == null ? List.of() : List.copyOf(keys); }

    /** Binding may only be replaced by an identical id revision, never a different landmark. */
    public static boolean mayRebind(String boundId, String foundId) {
        return Objects.equals(boundId == null ? "" : boundId, foundId == null ? "" : foundId);
    }
}
