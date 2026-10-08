package io.github.mysticism.navigation;

import net.minecraft.util.math.Vec3d;

/** Recent carrier displacement accepted by vanilla's player-move handler, not the unsynchronized
 * ServerPlayerEntity velocity or an unvalidated C2S destination. Owned by one navigation session. */
public final class AcceptedPlayerMovement {
    private int tick;
    private boolean sampled;
    private Vec3d displacement = Vec3d.ZERO;

    /** Called only after a successful onPlayerMove transaction. Several packets in one server tick
     * contribute once each; rotation-only packets do not erase movement already accepted that tick.
     * A zero-displacement transaction on a later tick does record a real stop. */
    public void record(int now, Vec3d before, Vec3d after) {
        Vec3d delta = after.subtract(before);
        if (!finite(delta) || delta.lengthSquared() > 16) {
            clear(); // discontinuity, never momentum
            return;
        }
        if (!sampled || tick != now) displacement = Vec3d.ZERO;
        tick = now;
        sampled = true;
        displacement = displacement.add(delta);
    }

    /** A toggle can arrive before this tick's move packet, so the previous tick is still useful.
     * Older samples must not replay a sprint/jump after an idle interval or explicit stop. */
    public Vec3d momentum(int now, Vec3d serverVelocity) {
        return sampled && now >= tick && now - tick <= 1 ? displacement : serverVelocity;
    }

    /** The real service and regressions use the same accepted-motion selection and mode policy. */
    public Vec3d handoff(int now, Vec3d serverVelocity, boolean deep, boolean grounded) {
        Vec3d momentum = momentum(now, serverVelocity);
        return deep ? WalkFlightHandoff.toFlight(momentum) : WalkFlightHandoff.toWalk(momentum, grounded);
    }

    public void clear() {
        sampled = false;
        displacement = Vec3d.ZERO;
    }

    private static boolean finite(Vec3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
