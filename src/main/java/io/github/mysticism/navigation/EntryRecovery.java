package io.github.mysticism.navigation;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Deterministic policy for recovering partial spirit-entry failures without stranding the player. */
public final class EntryRecovery {
    /** Ticks between attempts to rebuild a lost carrier session from the per-player binding. */
    public static final int RETRY_INTERVAL_TICKS = 20;
    /** Consecutive rebuild failures before falling back to the remembered source pose. */
    public static final int REBUILD_FAILURE_LIMIT = 10;
    /** Escalation backoff after a refused source return; the next attempt waits this many failures. */
    public static final int REBUILD_BACKOFF_FAILURES = REBUILD_FAILURE_LIMIT / 2;

    private EntryRecovery() {}
    public enum Action { ABORT, RETURN_TO_SOURCE, RETAIN_CARRIER }

    /** Candidate decision for a failed /spirit enter, classified from the player's ACTUAL current world.
     * Fabric world-change callbacks execute inside teleport, so a callback throwing after the transfer
     * leaves the player in the carrier before teleport returns; a stale post-return flag must never
     * downgrade an in-carrier player to ABORT. RETURN_TO_SOURCE is a validated attempt — a refused or
     * failed attempt falls back to RETAIN_CARRIER, never to an exit-less abort. */
    public static Action failedEnter(boolean currentlyInCarrier, boolean sourceRecoverable) {
        if (!currentlyInCarrier) return Action.ABORT;
        return sourceRecoverable ? Action.RETURN_TO_SOURCE : Action.RETAIN_CARRIER;
    }

    /** The remembered source pose is only a recovery target when it names a real dimension with a finite position. */
    public static boolean recoverableSource(String dimension, Vec3d pose) {
        if (dimension == null || dimension.isEmpty() || Identifier.tryParse(dimension) == null) return false;
        return pose != null && Double.isFinite(pose.x) && Double.isFinite(pose.y) && Double.isFinite(pose.z);
    }
}
