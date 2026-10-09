package io.github.mysticism.movement;

import net.minecraft.util.math.Vec3d;
import java.io.InputStream;

/** Deterministic regressions for consistent spirit-mode flight: no entity or client boot needed. */
public final class SpiritMovementTest {
    private static int checks;
    private SpiritMovementTest() {}

    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static void near(double expected, double actual, double epsilon, String why) {
        check(Math.abs(expected - actual) <= epsilon, why + " expected " + expected + " got " + actual);
    }

    /** Digital key input always accelerates as a unit direction: diagonal is never faster than straight. */
    private static void digitalInputIsUnit() {
        double[][] inputs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
                {0, 1, 0}, {0, -1, 0},
                {1, 1, 0}, {1, -1, 0}, {0, 1, 1}, {0, -1, 1},
                {1, 1, 1}, {1, -1, 1}, {-1, 1, -1}, {1, -1, -1},
        };
        for (double[] raw : inputs) {
            Vec3d unit = SpiritMovement.direction(raw[0], raw[1], raw[2], 37f);
            // float trig keeps sin^2+cos^2 within ~1e-7 of 1; consistency is what matters, not exactness
            near(1, unit.length(), 1e-6, "direction must be unit for " + java.util.Arrays.toString(raw));
        }
        check(SpiritMovement.direction(0, 0, 0, 0).equals(Vec3d.ZERO), "no input must yield no direction");
        near(0.5, SpiritMovement.direction(.5, 0, 0, 0).length(), 1e-9, "analog sub-unit input stays proportional");
    }

    /** Yaw rotation follows the vanilla movementInputToVelocity convention. */
    private static void yawConvention() {
        Vec3d forward = SpiritMovement.direction(0, 0, 1, 0f);
        near(0, forward.x, 1e-9, "yaw 0 forward x");
        near(1, forward.z, 1e-9, "yaw 0 (south) forward is +z like vanilla");
        Vec3d rotated = SpiritMovement.direction(0, 0, 1, 90f);
        near(-1, rotated.x, 1e-9, "yaw 90 forward x");
        near(0, rotated.z, 1e-9, "yaw 90 forward z");
        near(0, rotated.y, 1e-9, "yaw rotation must not introduce vertical motion");
        Vec3d strafe = SpiritMovement.direction(1, 0, 0, 0f);
        near(1, strafe.x, 1e-9, "yaw 0 strafe x");
        near(0, strafe.z, 1e-9, "yaw 0 strafe z");
    }

    /** Every held direction converges to the SAME terminal speed: the diagonal consistency regression. */
    private static void isotropicTerminalSpeed() {
        double[] accelerations = {0.05, 0.1}; // walking-fly and sprint-fly
        Vec3d[] directions = {
                new Vec3d(0, 0, -1), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, -1, 0),
                new Vec3d(1, 0, 1).normalize(), new Vec3d(1, 1, 1).normalize(),
                new Vec3d(-1, 1, 0).normalize(), new Vec3d(1, -1, 1).normalize(),
        };
        for (double acceleration : accelerations) {
            double terminal = SpiritMovement.terminalSpeed(acceleration);
            for (Vec3d direction : directions) {
                Vec3d velocity = Vec3d.ZERO;
                for (int tick = 0; tick < 400; tick++) velocity = SpiritMovement.flyStep(velocity, direction, acceleration);
                near(terminal, velocity.length(), 1e-6, "terminal speed must be direction independent");
            }
            // Sanity: vanilla-style flight would differ per axis; the consistent model must not.
            Vec3d horizontal = SpiritMovement.flyStep(Vec3d.ZERO, new Vec3d(0, 0, -1), acceleration);
            Vec3d vertical = SpiritMovement.flyStep(Vec3d.ZERO, new Vec3d(0, 1, 0), acceleration);
            near(horizontal.length(), vertical.length(), 1e-12, "first-tick acceleration must be isotropic");
        }
    }

    /** The exponential model never overshoots or oscillates around the terminal speed: no correction churn. */
    private static void stableNoOvershoot() {
        double acceleration = 0.05, terminal = SpiritMovement.terminalSpeed(acceleration);
        Vec3d direction = new Vec3d(0, 0, -1);
        Vec3d fast = new Vec3d(0, 0, -3); // above terminal (e.g. entry momentum)
        double previous = fast.length();
        for (int tick = 0; tick < 200; tick++) {
            Vec3d next = SpiritMovement.flyStep(fast, direction, acceleration);
            double speed = next.length();
            check(speed <= previous + 1e-12, "above-terminal speed must decay monotonically");
            check(speed >= terminal - 1e-12, "decay must not undershoot the terminal speed");
            previous = speed; fast = next;
        }
        near(terminal, fast.length(), 1e-6, "decay must converge to the terminal speed");
        Vec3d slow = Vec3d.ZERO;
        previous = 0;
        for (int tick = 0; tick < 200; tick++) {
            Vec3d next = SpiritMovement.flyStep(slow, direction, acceleration);
            double speed = next.length();
            check(speed >= previous - 1e-12, "below-terminal speed must rise monotonically");
            check(speed <= terminal + 1e-12, "rise must not overshoot the terminal speed");
            previous = speed; slow = next;
        }
        near(terminal, slow.length(), 1e-6, "rise must converge to the terminal speed");
        Vec3d idle = new Vec3d(0.3, 0.2, 0.1);
        double idleSpeed = idle.length();
        Vec3d coasted = idle;
        for (int tick = 0; tick < 400; tick++) {
            Vec3d next = SpiritMovement.flyStep(coasted, Vec3d.ZERO, acceleration);
            check(next.length() < coasted.length(), "no input must decay smoothly toward hover");
            coasted = next;
        }
        check(coasted.length() < idleSpeed * 1e-6, "no input must decay to a stable hover");
        check(coasted.length() >= 0, "hover decay must stay non-negative (no sign flip)");
    }

    /** Damping is identical on every axis and mode transitions stay continuous. */
    private static void dampingSymmetricAndContinuous() {
        Vec3d velocity = new Vec3d(0.4, 0.4, 0.4);
        Vec3d damped = SpiritMovement.damp(velocity);
        near(velocity.x * SpiritMovement.DRAG, damped.x, 0, "x damping factor");
        near(velocity.y * SpiritMovement.DRAG, damped.y, 0, "y damping factor equals x");
        near(velocity.z * SpiritMovement.DRAG, damped.z, 0, "z damping factor equals x");
        // Entering flight with existing momentum must not jump: one tick only scales by DRAG when idle.
        Vec3d carried = SpiritMovement.flyStep(new Vec3d(0.2, -0.3, 0.1), Vec3d.ZERO, 0.05);
        near(0.2 * SpiritMovement.DRAG, carried.x, 1e-12, "carried x momentum stays continuous");
        near(-0.3 * SpiritMovement.DRAG, carried.y, 1e-12, "carried y momentum stays continuous");
        near(0.1 * SpiritMovement.DRAG, carried.z, 1e-12, "carried z momentum stays continuous");
    }

    /** Exactly vanilla's pre-added vertical impulse is stripped, so the model's vertical rate is the only one. */
    private static void vanillaVerticalStripped() {
        Vec3d velocity = new Vec3d(0.1, -0.2, 0.3);
        Vec3d stripped = SpiritMovement.stripVanillaFlightVertical(velocity, 1, 0.05);
        near(0.1, stripped.x, 0, "strip keeps x");
        near(-0.35, stripped.y, 1e-12, "strip removes 3 * flySpeed upward impulse");
        near(0.3, stripped.z, 0, "strip keeps z");
        Vec3d down = SpiritMovement.stripVanillaFlightVertical(velocity, -1, 0.05);
        near(-0.05, down.y, 1e-12, "strip removes the downward impulse");
        check(SpiritMovement.stripVanillaFlightVertical(velocity, 0, 0.05).equals(velocity),
                "no vanilla impulse means no strip");
    }

    /** Sprint scales the whole direction uniformly, so sprint toggles change only the acceleration rate. */
    private static void sprintFactorUniform() {
        net.minecraft.entity.player.PlayerAbilities abilities = new net.minecraft.entity.player.PlayerAbilities();
        double walk = SpiritMovement.acceleration(abilities, false);
        double sprint = SpiritMovement.acceleration(abilities, true);
        near(0.05, walk, 1e-9, "walk-fly acceleration is vanilla flySpeed");
        near(2 * walk, sprint, 1e-12, "sprint-fly acceleration doubles the whole direction");
        Vec3d diagonal = new Vec3d(1, 1, 1).normalize();
        Vec3d walkDiagonal = SpiritMovement.flyStep(Vec3d.ZERO, diagonal, walk);
        Vec3d sprintDiagonal = SpiritMovement.flyStep(Vec3d.ZERO, diagonal, sprint);
        near(walkDiagonal.length() * 2, sprintDiagonal.length(), 1e-12, "sprint scales diagonal uniformly");
    }

    /** The travel-head gate matches vanilla's flying branch and never runs on non-owning logical sides. */
    private static void travelGate() {
        check(SpiritMovement.handlesFlight(true, true, false, false, true), "spirit flight must be handled");
        check(!SpiritMovement.handlesFlight(false, true, false, false, true), "source worlds stay vanilla");
        check(!SpiritMovement.handlesFlight(true, false, false, false, true), "shallow walking stays vanilla");
        check(!SpiritMovement.handlesFlight(true, true, true, false, true), "vehicles stay vanilla");
        check(!SpiritMovement.handlesFlight(true, true, false, true, true), "swimming stays vanilla");
        check(!SpiritMovement.handlesFlight(true, true, false, false, false), "non-owning logical side stays vanilla");
    }

    /** The model must actually be wired into travel: mixin config and client input registration contract. */
    private static void wiredIntoTravel() throws Exception {
        check(SpiritMovement.DRAG > 0 && SpiritMovement.DRAG < 1, "drag must be a stable decay factor");
        check(SpiritMovement.VANILLA_VERTICAL_INPUT == 3.0, "strip constant must match vanilla's 3x vertical input");
        try (InputStream mixins = SpiritMovementTest.class.getResourceAsStream("/mysticism.mixins.json")) {
            byte[] bytes = mixins == null ? new byte[0] : mixins.readAllBytes();
            String config = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            check(config.contains("SpiritTravelMixin"), "travel mixin must be registered in mysticism.mixins.json");
            check(config.contains("EntityFlagAccess"), "flag invoker must be registered in mysticism.mixins.json");
        }
        check(mixinsConfigReadable(), "mysticism.mixins.json must be on the runtime classpath");
        // Reinstalling the deterministic default keeps headless/side-less paths honest about absent key state.
        SpiritMovement.installVerticalInput(player -> 0);
    }

    private static boolean mixinsConfigReadable() throws Exception {
        try (InputStream stream = SpiritMovementTest.class.getResourceAsStream("/mysticism.mixins.json")) {
            return stream != null;
        }
    }

    public static void main(String[] args) throws Exception {
        digitalInputIsUnit();
        yawConvention();
        isotropicTerminalSpeed();
        stableNoOvershoot();
        dampingSymmetricAndContinuous();
        vanillaVerticalStripped();
        sprintFactorUniform();
        travelGate();
        wiredIntoTravel();
        System.out.println("SpiritMovementTest: " + checks + " checks passed");
    }
}
