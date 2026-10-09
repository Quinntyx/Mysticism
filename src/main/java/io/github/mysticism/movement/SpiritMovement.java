package io.github.mysticism.movement;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.mixin.EntityFlagAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Consistent spirit deep-flight movement, shared by client prediction and any logical simulation.
 *
 * <p>Vanilla creative flight (which deep mode previously inherited via {@code abilities.flying}) is
 * directionally inconsistent: vertical key input accelerates at {@code 3 * flySpeed} with {@code 0.6}
 * damping while horizontal input accelerates at {@code flySpeed} (2x sprinting) with {@code 0.91}
 * damping, and the vertical input bypasses the input normalization that keeps horizontal diagonals
 * at single-key speed. In a per-player semantic world that anisotropy makes semantic travel rates
 * depend on the direction the player holds, and sprint toggles during flight produce abrupt
 * acceleration changes that read as jitter.
 *
 * <p>The model here keeps vanilla's stable exponential structure (accelerate, {@link Entity#move},
 * then damp the post-collision velocity) but applies ONE acceleration rate and ONE drag factor to
 * every axis and normalizes the combined three-dimensional input, so every held direction converges
 * to the same terminal speed. Velocity is never clamped, zeroed or "corrected"; mode transitions and
 * sprint toggles only change the acceleration rate, so speed stays continuous and prediction never
 * fights a correction. Shallow walking is deliberately untouched (approved design: walk normally).
 */
public final class SpiritMovement {
    /** Identical drag on every axis: vanilla's airborne value, now applied to vertical motion too. */
    public static final float DRAG = 0.91f;
    /** Vanilla sprint-flight factor, applied to the whole direction so diagonal speed stays consistent. */
    public static final float SPRINT_FACTOR = 2.0f;
    /** Vanilla ClientPlayerEntity.tickMovement pre-adds direction * flySpeed * 3 before travel; strip it exactly. */
    public static final double VANILLA_VERTICAL_INPUT = 3.0;
    /** Vanilla movementInputToVelocity dead zone. */
    private static final double INPUT_DEAD_ZONE = 1.0E-7;

    /** Client installs its real key state; the default keeps every path deterministic without a client. */
    private static volatile ToDoubleFunction<PlayerEntity> verticalInput = player -> 0;
    private SpiritMovement() {}

    public static void installVerticalInput(ToDoubleFunction<PlayerEntity> provider) {
        verticalInput = Objects.requireNonNull(provider);
    }

    public static boolean inSpiritWorld(PlayerEntity player) {
        return player.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD);
    }

    /** Exactly the vanilla flight vertical direction (-1 sneak, +1 jump, 0 otherwise) under the same isCamera condition. */
    public static double verticalDirection(PlayerEntity player) {
        return verticalInput.applyAsDouble(player);
    }

    /** Travel-head decision, factored out so regressions can exercise it without entity instances. */
    public static boolean handlesFlight(boolean spiritWorld, boolean flying, boolean hasVehicle,
                                        boolean swimming, boolean logicalSide) {
        return spiritWorld && flying && !hasVehicle && !swimming && logicalSide;
    }

    /**
     * Vanilla {@code movementInputToVelocity} rule (normalize only past length 1) extended to the
     * vertical axis, then yaw-rotated exactly like vanilla. Digital key input therefore always yields
     * a unit direction: diagonal (including jump/sneak mixes) is never faster than straight.
     */
    public static Vec3d direction(double sideways, double vertical, double forward, float yaw) {
        Vec3d input = new Vec3d(sideways, vertical, forward);
        double lengthSquared = input.lengthSquared();
        if (lengthSquared < INPUT_DEAD_ZONE) return Vec3d.ZERO;
        if (lengthSquared > 1) input = input.normalize();
        float radians = yaw * 0.017453292f;
        float sin = MathHelper.sin(radians), cos = MathHelper.cos(radians);
        return new Vec3d(input.x * cos - input.z * sin, input.y, input.z * cos + input.x * sin);
    }

    public static Vec3d accelerate(Vec3d velocity, Vec3d direction, double acceleration) {
        return velocity.add(direction.multiply(acceleration));
    }

    /** Isotropic exponential damping; the post-collision velocity damps identically on every axis. */
    public static Vec3d damp(Vec3d velocity) {
        return new Vec3d(velocity.x * DRAG, velocity.y * DRAG, velocity.z * DRAG);
    }

    /** Collision-free flight tick: accelerate then damp. Stable for any input, never overshoots the terminal speed. */
    public static Vec3d flyStep(Vec3d velocity, Vec3d direction, double acceleration) {
        return damp(accelerate(velocity, direction, acceleration));
    }

    /** Analytic steady state of {@link #flyStep}: identical for every held direction. */
    public static double terminalSpeed(double acceleration) {
        return acceleration * DRAG / (1 - DRAG);
    }

    public static double acceleration(PlayerAbilities abilities, boolean sprinting) {
        return abilities.getFlySpeed() * (sprinting ? SPRINT_FACTOR : 1);
    }

    /** Removes exactly the vertical impulse vanilla ClientPlayerEntity.tickMovement added this tick. */
    public static Vec3d stripVanillaFlightVertical(Vec3d velocity, double direction, double flySpeed) {
        if (direction == 0) return velocity;
        return velocity.subtract(0, direction * VANILLA_VERTICAL_INPUT * flySpeed, 0);
    }

    /**
     * Spirit deep-flight travel. Returns true when the caller must cancel vanilla
     * {@code PlayerEntity.travel}. Vanilla's tick structure is preserved (strip the pre-added vanilla
     * vertical impulse, accelerate, {@link Entity#move} so mesh collision/sneaking/collision zeroing
     * behave exactly as in walking, then damp the post-move velocity), only the anisotropy is removed.
     */
    public static boolean travel(PlayerEntity player, Vec3d input) {
        if (!inSpiritWorld(player)) return false;
        PlayerAbilities abilities = player.getAbilities();
        if (!handlesFlight(true, abilities.flying, player.hasVehicle(), player.isSwimming(),
                player.isLogicalSideForUpdatingMovement())) return false;
        double direction = verticalDirection(player);
        Vec3d velocity = stripVanillaFlightVertical(player.getVelocity(), direction, abilities.getFlySpeed());
        Vec3d unit = direction(input.x, direction, input.z, player.getYaw());
        player.setVelocity(accelerate(velocity, unit, acceleration(abilities, player.isSprinting())));
        player.move(MovementType.SELF, player.getVelocity());
        player.setVelocity(damp(player.getVelocity()));
        player.onLanding();
        ((EntityFlagAccess) player).mysticism$setFlag(7, false);
        player.updateLimbs(false); // LivingEntity.travel tail parity for PlayerEntity (not a Flutterer)
        return true;
    }
}
