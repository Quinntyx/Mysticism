package io.github.mysticism.movement;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.MeshMovementValidation;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Combined input -> acceleration -> custom mesh collision -> packet admission -> damping
 * regression. Complements the model-only and projection-only suites; no game or model boot. */
public final class SpiritFlightIntegrationTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static Vec3d feet(Box body) {
        return new Vec3d(body.getCenter().x, body.minY, body.getCenter().z);
    }
    private static TerrainMeshFrame wall(double x, double yaw, boolean shallow, long revision) {
        // Same source cell/key/material, two independently projected observer meshes.
        return new TerrainMeshFrame(revision, shallow, "minecraft:overworld", new Vec3d(1000, 64, 1000),
                new Vec3d(x, 0, 0), List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())),
                List.of(new TerrainMeshFrame.Cell(91, 0, "shared-source", new Vec3d(1000, 64, 1000),
                        new Vec3d(x, 0, 0), new Vec3d(1, 64, 64),
                        new Vec3d(Math.cos(yaw), 0, Math.sin(yaw)), new Vec3d(0, 64, 0),
                        new Vec3d(-64 * Math.sin(yaw), 0, 64 * Math.cos(yaw)), 0xFFFFFFFF, 0, 1f,
                        List.of(new Box(0, -2, -2, 1, 3, 3)))));
    }

    private static void liveTravelInput() {
        var abilities = new PlayerAbilities();
        for (boolean sprint : List.of(false, true)) {
            double acceleration = SpiritMovement.acceleration(abilities, sprint);
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                if (x == 0 && y == 0 && z == 0) continue;
                // LivingEntity passes 0.98f horizontal key input to PlayerEntity.travel, while
                // ClientPlayerEntity's vertical keys are unscaled. Synthetic unit input misses this.
                Vec3d direction = SpiritMovement.flightDirection(x * .98f, y, z * .98f, 0);
                check(Math.abs(direction.length() - 1) < 1e-6,
                        "actual travel arguments must give equal single-key/diagonal input: " + x + "," + y + "," + z);
                Vec3d velocity = Vec3d.ZERO;
                for (int tick = 0; tick < 400; tick++) velocity = SpiritMovement.flyStep(velocity, direction, acceleration);
                check(Math.abs(velocity.length() - SpiritMovement.terminalSpeed(acceleration)) < 1e-6,
                        "actual input, not synthetic unit vectors, must converge to one flight speed");
            }
        }
        check(SpiritMovement.flightDirection(0, 0, 0, 0).equals(Vec3d.ZERO), "no keys remains no input");
        check(Math.abs(SpiritMovement.flightDirection(.5f * .98f, 0, 0, 0).length() - .5) < 1e-6,
                "partial/analog input remains proportional");
    }

    private static void sustainedFlight() {
        UUID deepPlayer = UUID.randomUUID(), shallowPlayer = UUID.randomUUID();
        var deep = wall(4, .23, false, 1);
        var currentDeep = wall(4.2, .24, false, 2);
        var shallow = wall(-4, -.31, true, 1);
        var clientIndex = new MeshCollision.Index(deep);
        var abilities = new PlayerAbilities();
        Box body = new Box(-.3, 2, -.3, .3, 3.8, .3);
        Vec3d velocity = Vec3d.ZERO;
        int contacts = 0;
        try {
            MeshMovementValidation.record(shallowPlayer, shallow);
            MeshMovementValidation.record(deepPlayer, deep);
            MeshMovementValidation.record(deepPlayer, currentDeep);
            check(!deep.shallow() && shallow.shallow(), "fixtures exercise different deep/shallow projections");
            check(deep.cells().getFirst().key() == shallow.cells().getFirst().key(), "source ownership remains shared, geometry does not");
            for (int tick = 0; tick < 480; tick++) {
                // Held inputs, diagonal/up/down mixes, turns, reversals, sprint changes and idle.
                // First segment deliberately reaches the wall; later segments turn both toward and away.
                int segment = tick / 60;
                double side = segment == 0 ? 1 : segment % 2 == 0 ? 1 : -1;
                double up = segment % 3 == 0 ? .0 : segment % 3 == 1 ? 1 : -1;
                double forward = segment % 2 == 0 ? 0 : 1;
                if (segment == 7) side = up = forward = 0;
                double acceleration = SpiritMovement.acceleration(abilities, segment % 2 == 1);
                // Match vanilla pre-add and the production travel hook's exact removal.
                Vec3d preAdded = velocity.add(0, up * 3 * abilities.getFlySpeed(), 0);
                Vec3d stripped = SpiritMovement.stripVanillaFlightVertical(preAdded, up, abilities.getFlySpeed());
                check(stripped.distanceTo(velocity) < 1e-12, "no vertical impulse accumulates across movement ticks");
                Vec3d unit = SpiritMovement.flightDirection(side * .98f, up, forward * .98f, segment == 0 ? 0 : segment * 17);
                check(unit.length() <= 1.000001, "mixed input never exceeds unit acceleration");
                Vec3d wanted = SpiritMovement.accelerate(stripped, unit, acceleration);
                Vec3d resolved = MeshCollision.predicted(clientIndex, body, wanted, true, false, 0);
                if (resolved.distanceTo(wanted) > 1e-6) contacts++;
                check(MeshMovementValidation.allowsMeshMove(deepPlayer, body, feet(body).add(resolved), true),
                        "no teleport-back for combined movement/collision during held input or direction change: " + tick);
                body = body.offset(resolved);
                // Entity.move clears each clipped velocity component before the travel tail's damping.
                Vec3d postCollision = new Vec3d(Math.abs(wanted.x - resolved.x) > 1e-7 ? 0 : wanted.x,
                        Math.abs(wanted.y - resolved.y) > 1e-7 ? 0 : wanted.y,
                        Math.abs(wanted.z - resolved.z) > 1e-7 ? 0 : wanted.z);
                velocity = SpiritMovement.damp(postCollision);
                check(velocity.length() <= SpiritMovement.terminalSpeed(.1) + 1e-6,
                        "no unstable speed correction/overshoot in collision-integrated flight");
                check(MeshCollision.bodyClear(deep, body), "accepted honest movement does not tunnel through its custom mesh");
            }
            check(contacts > 10, "the sustained sequence actually collides with the distorted wall");
            check(velocity.length() < .004, "idle input damps smoothly to hover after flight/turning");
            Box start = new Box(-.3, 2, -.3, .3, 3.8, .3);
            check(!MeshMovementValidation.allowsMeshMove(deepPlayer, start, feet(start).add(12, 0, 0), true),
                    "deep player's mesh wall crossing is still rejected");
            check(MeshMovementValidation.allowsMeshMove(shallowPlayer, start, feet(start).add(12, 0, 0), false),
                    "deep player's wall cannot obstruct a differently projected shallow player");
            check(!MeshMovementValidation.allowsMeshMove(shallowPlayer, start, feet(start).add(-12, 0, 0), false),
                    "shallow player's own wall still blocks its movement");
            check(MeshMovementValidation.allowsMeshMove(deepPlayer, start, feet(start).add(-12, 0, 0), true),
                    "shallow projection cannot leak into deep packet admission");
        } finally {
            MeshMovementValidation.clear(deepPlayer);
            MeshMovementValidation.clear(shallowPlayer);
        }
    }
    public static void main(String[] args) {
        liveTravelInput();
        sustainedFlight();
        System.out.println("SpiritFlightIntegrationTest: " + checks + " combined flight checks passed");
    }
}
