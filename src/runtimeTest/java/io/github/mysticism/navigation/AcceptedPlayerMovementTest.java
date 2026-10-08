package io.github.mysticism.navigation;

import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Offline integration regression: the real accepted-movement accumulator/mode policy and vanilla
 * self-velocity packet, with deliberately different controlling-client/server velocities. Also checks
 * the mapped handler bytecode supporting the mixin's server-thread/accepted-tail injection contract.
 * Does not bootstrap a game or pretend to exercise an installed mixin in a live connection. */
public final class AcceptedPlayerMovementTest {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    private static void near(Vec3d actual, Vec3d expected, double epsilon, String why) {
        check(actual.subtract(expected).length() <= epsilon, why + ": " + actual + " != " + expected);
    }
    private static Vec3d delivered(Vec3d velocity) {
        var packet = new EntityVelocityUpdateS2CPacket(17, velocity);
        check(packet.getEntityId() == 17, "Handoff targets the controlling player");
        return new Vec3d(packet.getVelocityX(), packet.getVelocityY(), packet.getVelocityZ());
    }

    private static void movingClientStationaryServer() {
        for (Vec3d clientMomentum : new Vec3d[]{new Vec3d(.215, 0, 0), new Vec3d(.3, 0, .28),
                new Vec3d(.2, .42, 0), new Vec3d(.18, -.0784, -.12)}) {
            var movement = new AcceptedPlayerMovement();
            Vec3d acceptedPose = new Vec3d(10, 64, -20);
            Vec3d serverVelocity = Vec3d.ZERO; // ordinary walking packets do NOT synchronize this
            for (int tick = 100; tick <= 104; tick++) {
                Vec3d before = acceptedPose;
                acceptedPose = acceptedPose.add(clientMomentum);
                movement.record(tick, before, acceptedPose);
            }
            // Double-jump abilities may already be preset on the server; the move this tick is not
            // received yet. Hand off last tick's accepted displacement, not zero server momentum.
            check(WalkFlightHandoff.handoffDue(false, true), "Preset flying still triggers navigation handoff");
            Vec3d handoff = movement.handoff(105, serverVelocity, true, false);
            near(handoff, clientMomentum, 1e-12, "Real accepted walking/jump momentum wins over server zero");
            check(handoff.lengthSquared() > 0, "Flight entry cannot cancel a moving client");
            Vec3d controllingClientVelocity = delivered(handoff);
            near(controllingClientVelocity, clientMomentum, Math.sqrt(3) / 8000,
                    "Owner starts flight with accepted momentum, within vanilla wire quantization");
            near(acceptedPose, new Vec3d(10, 64, -20).add(clientMomentum.multiply(5)), 1e-12,
                    "Velocity handoff leaves the accepted carrier pose untouched");
        }
        var movement = new AcceptedPlayerMovement();
        movement.record(12, Vec3d.ZERO, new Vec3d(.25, 0, -.1));
        near(movement.handoff(12, new Vec3d(-2, .9, 1), true, false), new Vec3d(.25, 0, -.1), 1e-12,
                "A nonzero but divergent server velocity is not the walking client's momentum either");
    }

    private static void packetTimingAndStops() {
        var movement = new AcceptedPlayerMovement();
        Vec3d pose = new Vec3d(0, 64, 0);
        movement.record(40, pose, pose.add(.1, .2, 0));
        movement.record(40, pose.add(.1, .2, 0), pose.add(.25, .42, .1));
        movement.record(40, pose.add(.25, .42, .1), pose.add(.25, .42, .1)); // rotation only
        Vec3d sum = new Vec3d(.25, .42, .1);
        near(movement.handoff(40, Vec3d.ZERO, true, false), sum, 1e-12,
                "Multiple accepted packets aggregate once; same-tick rotation does not erase momentum");
        near(movement.handoff(41, Vec3d.ZERO, true, false), sum, 1e-12,
                "Toggle before the next movement packet retains the previous tick");
        near(movement.handoff(42, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Old motion cannot restart a stationary player");
        movement.record(41, pose, pose);
        near(movement.handoff(41, new Vec3d(1, 0, 0), true, false), Vec3d.ZERO, 0,
                "Fresh accepted stop overrides a stale server burst");
        movement.record(42, pose, pose.add(.3, 0, 0));
        near(movement.handoff(42, Vec3d.ZERO, false, true), Vec3d.ZERO, 0,
                "Validated ground acquisition still delivers a stable standing velocity");
        movement.clear(); // the production handoff/expiry path clears its old sample
        near(movement.handoff(42, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Expiry/grounded handoff cannot resurrect pre-stop momentum on a quick toggle");
        Vec3d impulse = new Vec3d(.6, .2, 0);
        near(movement.handoff(42, impulse, true, false), impulse, 0,
                "With no accepted sample, a real server impulse remains usable");
    }

    private static void invalidationAndBounds() {
        var movement = new AcceptedPlayerMovement();
        movement.record(1, Vec3d.ZERO, new Vec3d(.3, 0, 0));
        movement.clear(); // requestTeleport invalidates even a short relocation/correction
        near(movement.handoff(1, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Teleport/correction discards motion from the old carrier pose");
        movement.record(2, Vec3d.ZERO, new Vec3d(50, 0, 0));
        near(movement.handoff(2, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Teleport-scale displacement is never movement momentum");
        movement.record(3, Vec3d.ZERO, new Vec3d(Double.NaN, 0, 0));
        near(movement.handoff(3, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Non-finite movement cannot enter the packet");
        movement.record(4, Vec3d.ZERO, new Vec3d(4, 0, 0));
        Vec3d bounded = movement.handoff(4, Vec3d.ZERO, true, false);
        near(bounded, new Vec3d(WalkFlightHandoff.MAX_HANDOFF_SPEED, 0, 0), 1e-12,
                "Accepted fast movement is bounded, not cancelled");
        near(delivered(bounded), bounded, 0, "Bound matches vanilla self-packet clamp");
        movement.record(4, new Vec3d(4, 0, 0), new Vec3d(8, 0, 0));
        near(movement.handoff(4, Vec3d.ZERO, true, false), bounded, 1e-12,
                "Same-tick packet bursts cannot bypass the handoff speed bound");
        near(movement.handoff(3, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Samples from a future tick are not reused");
        var otherPlayer = new AcceptedPlayerMovement();
        near(otherPlayer.handoff(4, Vec3d.ZERO, true, false), Vec3d.ZERO, 0,
                "Accepted movement is independent per navigation session");
    }

    private static void mappedAcceptanceHook() throws Exception {
        var vanilla = new ClassNode();
        try (var stream = AcceptedPlayerMovementTest.class.getClassLoader().getResourceAsStream(
                "net/minecraft/server/network/ServerPlayNetworkHandler.class")) {
            check(stream != null, "Mapped handler bytecode is available");
            new ClassReader(stream).accept(vanilla, 0);
        }
        MethodNode move = vanilla.methods.stream().filter(m -> m.name.equals("onPlayerMove")).findFirst().orElseThrow();
        int returns = 0;
        AbstractInsnNode last = null;
        boolean mainThreadGuard = false;
        boolean acceptedPoseCommit = false;
        for (AbstractInsnNode instruction : move.instructions) {
            if (instruction.getOpcode() < 0) continue;
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/network/NetworkThreadUtils") && call.name.equals("forceMainThread")) {
                    check(call.desc.equals("(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V"),
                            "Snapshot hook descriptor matches the server-thread guard");
                    mainThreadGuard = true;
                }
                if (call.name.equals("updatePositionAndAngles")) acceptedPoseCommit = true;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) {
                returns++;
                last = instruction;
            }
        }
        check(mainThreadGuard && acceptedPoseCommit, "Handler guards its thread and commits accepted pose");
        check(returns > 1, "Rejected/vehicle/pending-teleport paths have early returns (not TAIL)");
        AbstractInsnNode beforeTail = last.getPrevious();
        while (beforeTail.getOpcode() < 0) beforeTail = beforeTail.getPrevious();
        check(beforeTail instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals("updatedZ"),
                "TAIL follows vanilla's accepted-position bookkeeping, not a rejection/temporary move");
        check(vanilla.methods.stream().anyMatch(m -> m.name.equals("requestTeleport")
                        && m.desc.equals("(DDDFFLjava/util/Set;)V")),
                "Teleport invalidation hook descriptor exists");
    }

    public static void main(String[] args) throws Exception {
        movingClientStationaryServer();
        packetTimingAndStops();
        invalidationAndBounds();
        mappedAcceptanceHook();
        System.out.println("AcceptedPlayerMovementTest: " + checks + " checks passed");
    }
}
