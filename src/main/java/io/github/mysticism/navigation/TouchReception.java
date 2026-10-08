package io.github.mysticism.navigation;

import java.util.Objects;

/**
 * Pure per-target touch reception policy for the shared navigation lifecycle.
 *
 * <p>An accepted touch interpolates the RECIPIENT's basis toward the initiator's basis over
 * about one second (approved deep-player interaction). That effect belongs to the recipient's
 * own lifecycle: it must never silently destroy work the recipient is already doing on its own
 * mode transition or readiness. While the recipient carries any of its own pending lifecycle
 * intents — a deep walk request/support approach, a captured-target landing approach, or an
 * in-progress touch blend — a new incoming touch is REJECTED instead of cancelling them.
 * The initiator keeps its per-actor touch throttle and may retry after the recipient's intent
 * resolves; the recipient's intent, readiness and mode transition survive untouched.</p>
 *
 * <p>This policy is deliberately free of Minecraft/server types so the per-player independence
 * contract stays directly regression-testable without a booted game.</p>
 */
public final class TouchReception {
    private TouchReception() {}

    /**
     * Snapshot of one target player's own pending lifecycle intents. Each flag ORs the
     * session-local view and the synced component view of the same intent, so a partial
     * update on either side still fails closed.
     *
     * @param supportPending deep walk request/support approach is pending
     * @param landingPending captured-target landing approach is pending
     * @param blending       a touch blend is currently interpolating this target's basis
     */
    public record Intent(boolean supportPending, boolean landingPending, boolean blending) {
        public boolean any() {
            return supportPending || landingPending || blending;
        }
    }

    /** True only when the target carries none of its own pending lifecycle intents. */
    public static boolean mayDisturb(Intent target) {
        Objects.requireNonNull(target, "target intent");
        return !target.any();
    }
}
