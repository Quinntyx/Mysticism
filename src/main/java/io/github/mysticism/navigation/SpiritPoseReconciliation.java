package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;

/**
 * Client prediction/reconciliation contract for the per-player semantic pose (latent q and basis).
 *
 * <p>Both sides integrate the SAME accepted physical movement stream: the server advances q/basis
 * per server tick from accepted positions, the client predicts per client tick from predicted
 * movement. The four-tick authoritative CCA sync therefore carries a pose that is almost always
 * behind the client's prediction by the client's in-flight (not yet accepted) movement. Blindly
 * overwriting the client pose with every sync regresses the rendered scene by that in-flight
 * amount several times per second — the movement jitter/rubber banding this class removes.
 *
 * <p>Policy on every received sync, per pose channel:
 * <ul>
 *   <li><b>Adopt</b> fully when there is no healthy prediction yet (start, mode/epoch change,
 *       embedding-profile mismatch) or when divergence exceeds the snap threshold: the server
 *       stays authoritative exactly as before.</li>
 *   <li><b>Keep</b> the predicted pose (no overwrite) when divergence is inside the healthy
 *       prediction band: the in-flight movement the server has not accepted yet must not be lost.</li>
 *   <li><b>Pull</b> the predicted pose toward the server pose by a cubic fraction of the
 *       divergence in between: persistent prediction error converges smoothly instead of
 *       snapping, and healthy one-tick phase error stays imperceptible.</li>
 * </ul>
 *
 * <p>This only decides what the CLIENT component holds after a sync. Server-side components,
 * persistence and the sync payload itself are untouched, so server authority and saved data are
 * unchanged. Pure math/state: no Minecraft classes, fully covered by runtime regressions.
 */
public final class SpiritPoseReconciliation {
    /** Semantic units moved per accepted block of physical movement (shared steering scale). */
    public static final float UNITS_PER_BLOCK = (float) (1.0 / TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT);

    /**
     * Divergence (semantic units) at/above which prediction is considered wrong and the synced
     * server pose is adopted, i.e. the fraction reaches 1 when the square distance reaches
     * {@code SNAP_POSITION}². Above the largest healthy in-flight phase error (~2 ticks of
     * movement) but far below gameplay-significant semantic offsets.
     */
    public static final float SNAP_POSITION = 0.06f;

    /**
     * Basis divergence scale: the synced server basis is adopted when the summed axis square
     * distance reaches {@code SNAP_BASIS}² (≈ 5.06, a full-frame rotation of roughly 1.5 rad).
     * Generous: one server-driven alignment blend or a hard flight turn must never snap; only
     * genuinely broken prediction (or a model change, which throws and adopts) does.
     */
    public static final float SNAP_BASIS = 2.25f;

    private SpiritPoseReconciliation() {}

    /** Summed axis square distance between two bases (shared convention with navigation). */
    public static float basisError(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
    }

    /**
     * Reconciliation fraction for a semantic position divergence: zero inside the healthy band,
     * cubic ramp so small phase error self-corrects imperceptibly, exactly 1 at the snap point.
     */
    public static float positionFraction(float squareDistance) {
        return ramp(squareDistance, SNAP_POSITION);
    }

    /** Reconciliation fraction for a basis divergence (summed axis square distance). */
    public static float basisFraction(float basisError) {
        return ramp(basisError, SNAP_BASIS);
    }

    private static float ramp(float squareDistance, float snap) {
        if (!Float.isFinite(squareDistance) || squareDistance < 0) return 1f;
        if (squareDistance >= snap * snap) return 1f;
        float distance = (float) Math.sqrt(squareDistance);
        float fraction = distance / snap;
        return Math.max(0f, Math.min(1f, fraction * fraction * fraction));
    }

    /**
     * Stateful per-player reconciliation session owned by the client predictor. All methods run
     * on the client main thread (sync application and prediction share it), so plain fields are
     * sufficient. Session state is deliberately NOT persisted or synced: it is prediction bookkeeping.
     */
    public static final class Session {
        // Independent per channel: a mode transition must adopt BOTH channels even when the
        // position and basis syncs arrive in separate packets.
        private boolean awaitingPositionResync = true;
        private boolean awaitingBasisResync = true;

        /** Mode/epoch changed or prediction was invalidated: the next sync of each channel is adopted fully. */
        public void reset() { awaitingPositionResync = true; awaitingBasisResync = true; }

        public boolean awaitingPositionResync() { return awaitingPositionResync; }
        public boolean awaitingBasisResync() { return awaitingBasisResync; }

        /**
         * Decide what the client LATENT_POS component holds after receiving {@code serverValue}.
         *
         * @param serverValue the pose decoded from the authoritative sync (never null)
         * @param predicted   the client's current predicted pose, or null before the first tick
         * @return the object the component must store (may be either argument, mutated in place)
         */
        public Vec384f reconcilePosition(Vec384f serverValue, Vec384f predicted) {
            if (serverValue == null) throw new IllegalArgumentException("Synced semantic position required");
            if (awaitingPositionResync || predicted == null) { awaitingPositionResync = false; return serverValue; }
            float squareDistance;
            try { squareDistance = serverValue.squareDistance(predicted); }
            catch (RuntimeException profileMismatch) { awaitingPositionResync = false; return serverValue; }
            float fraction = positionFraction(squareDistance);
            if (fraction >= 1f) { awaitingPositionResync = false; return serverValue; }
            if (fraction <= 0f) return predicted; // healthy in-flight phase: keep the prediction
            return predicted.converge(serverValue, fraction);
        }

        /**
         * Decide what the client LATENT_BASIS component holds after receiving {@code serverValue}.
         *
         * @param serverDriven true while the server owns the basis trajectory (support/landing
         *                     alignment): every sync is adopted because the client cannot
         *                     reproduce the server's bounded alignment blend.
         */
        public Basis384f reconcileBasis(Basis384f serverValue, Basis384f predicted, boolean serverDriven) {
            if (serverValue == null) throw new IllegalArgumentException("Synced semantic basis required");
            if (serverDriven || awaitingBasisResync || predicted == null) { awaitingBasisResync = false; return serverValue; }
            float error;
            try { error = basisError(serverValue, predicted); }
            catch (RuntimeException profileMismatch) { awaitingBasisResync = false; return serverValue; }
            float fraction = basisFraction(error);
            if (fraction >= 1f) { awaitingBasisResync = false; return serverValue; }
            if (fraction <= 0f) return predicted;
            // Partial convergence toward the server frame, re-orthonormalized like server blends.
            return TraversalSteering.blend(predicted, serverValue, fraction);
        }
    }
}
