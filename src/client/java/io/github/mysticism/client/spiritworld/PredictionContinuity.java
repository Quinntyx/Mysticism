package io.github.mysticism.client.spiritworld;

import net.minecraft.util.math.Vec3d;

/** Client prediction continuity state for latent semantic travel.
 * The predictor integrates movement only across an unbroken (player, world, pose, epoch, mode,
 * readiness) chain. Two server-decided events break it, in either processing order:
 * <ul>
 *   <li>the synced prediction-epoch advance published by TeleportReconciliation before the arrival
 *       position packet (closes the same-tick case);</li>
 *   <li>the ACTUAL arrival position being applied client-side by PlayerPositionLook
 *       (closes the split-tick case: the epoch update can legitimately be processed one client tick
 *       before the position packet, which would otherwise record the new epoch against the
 *       pre-teleport pose and let a sub-band arrival integrate as chosen movement).</li>
 * </ul>
 * Both are re-baselines: the arrival tick never integrates, and ordinary movement resumes on the
 * next tick. Pure state, no Minecraft entities, directly regression-drivable. */
public final class PredictionContinuity {
    private Object player, world;
    private Vec3d lastPos;
    private long lastEpoch = -1;
    private boolean lastDeep;

    public void cleared() { player = null; world = null; lastPos = null; lastEpoch = -1; lastDeep = false; }

    /** A server-decided arrival position was applied client-side: re-seed the last pose so the
     * arrival delta is never integrated, regardless of when the epoch update was processed. */
    public void onArrivalApplied() { lastPos = null; }

    /** Returns the integrable per-tick delta, or null when the chain is broken or the delta is
     * teleport-scale (re-baselined either way; the pose is recorded for the next tick). */
    public Vec3d integrableDelta(Object player, Object world, Vec3d now, long epoch, boolean deep, boolean semanticReady) {
        Vec3d delta = null;
        if (ClientLatentPredictor.integratesMovement(this.player == player, this.world == world, lastPos != null,
                lastEpoch, epoch, lastDeep, deep, semanticReady)) {
            Vec3d candidate = now.subtract(lastPos);
            if (candidate.lengthSquared() <= 16) delta = candidate; // teleport-scale movement is never chosen
        }
        this.player = player; this.world = world; lastPos = now; lastEpoch = epoch; lastDeep = deep;
        return delta;
    }
}
