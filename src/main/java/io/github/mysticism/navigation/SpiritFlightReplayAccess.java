package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.MeshMovementValidation;
import net.minecraft.util.math.Vec3d;

/** Implemented on the network handler by flight validation; no global/player-map handoff state.
 * Consumes only this packet's matching published-frame replay and its correction accounting. */
public interface SpiritFlightReplayAccess {
    MeshMovementValidation.Decision mysticism$takeFlightMeshDecision(Vec3d requested);
}
