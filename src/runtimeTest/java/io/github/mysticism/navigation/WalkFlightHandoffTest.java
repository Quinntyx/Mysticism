package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.util.math.Vec3d;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic regressions for walk/flight transition stability: every mode switch must hand off a
 * bounded, mode-appropriate velocity, and a settled shallow pose must never spontaneously start flight.
 * Pure policy math; no server boot, no test framework. */
public final class WalkFlightHandoffTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-9,why+" (got "+actual+")");}
    /** Vanilla encodes velocity packets at 1/8000 blocks/tick resolution; arbitrary values lose up to
    * one step. The delivery contract for arbitrary momentum is one wire step, not bit equality. */
    private static void wireNear(double actual,double expected,String why){check(Math.abs(actual-expected)<=1.0/8000,why+" (got "+actual+")");}

    private static void toFlightContinuity() {
        // Ordinary gameplay velocities at a walk→flight handoff must pass through EXACTLY: clamping or
        // zeroing them would itself be the rubber band. Jump ascent, walk, sprint, gentle descent.
        for (Vec3d v : new Vec3d[]{new Vec3d(0,.42,0), new Vec3d(.215,0,0), new Vec3d(.3,.1,.28),
                new Vec3d(0,-.0784,0), Vec3d.ZERO}) {
            Vec3d handed = WalkFlightHandoff.toFlight(v);
            check(handed.x==v.x && handed.y==v.y && handed.z==v.z,"Sub-cap momentum must survive the handoff unchanged");
        }
        // Over-cap residues are bounded, never dropped: direction is preserved and speed hits the cap.
        Vec3d burst = WalkFlightHandoff.toFlight(new Vec3d(30,0,0));
        near(burst.length(),WalkFlightHandoff.MAX_HANDOFF_SPEED,"Burst clamped to the integrable band");
        check(burst.x>0,"Clamp preserves direction");
        Vec3d plunge = WalkFlightHandoff.toFlight(new Vec3d(0,-40,0));
        near(plunge.length(),WalkFlightHandoff.MAX_HANDOFF_SPEED,"Falling residue clamped");
        check(plunge.y<0,"Falling residue keeps falling");
        check(WalkFlightHandoff.toFlight(new Vec3d(Double.NaN,0,0)).equals(Vec3d.ZERO),"NaN velocity cannot enter flight");
        check(WalkFlightHandoff.toFlight(new Vec3d(0,Double.POSITIVE_INFINITY,0)).equals(Vec3d.ZERO),"Infinite velocity cannot enter flight");
    }

    private static void toWalkGrounding() {
        // Validated ground contact at walk acquisition means a stable standing pose: zero residual
        // descent so the first walking tick cannot clip the freshly published mesh.
        check(WalkFlightHandoff.toWalk(new Vec3d(1.2,-3.4,.5),true).equals(Vec3d.ZERO),"Grounded walk handoff zeroes velocity");
        check(WalkFlightHandoff.toWalk(Vec3d.ZERO,true).equals(Vec3d.ZERO),"Grounded idle stays idle");
        // Airborne handoffs keep bounded momentum: no fabricated floor, no freeze mid-air.
        Vec3d airborne = WalkFlightHandoff.toWalk(new Vec3d(8,0,0),false);
        near(airborne.length(),WalkFlightHandoff.MAX_HANDOFF_SPEED,"Airborne walk handoff keeps bounded momentum");
        check(WalkFlightHandoff.toWalk(new Vec3d(Double.NaN,-1,0),true).equals(Vec3d.ZERO),"Non-finite velocity cannot enter walk");
        check(WalkFlightHandoff.toWalk(new Vec3d(Double.NaN,-1,0),false).equals(Vec3d.ZERO),"Non-finite airborne velocity sanitized");
    }

    private static void handoffSpeedIsSemanticallyIntegrable() {
        // The steering integrator discards per-tick movement above 4 blocks (teleport-scale). The handoff
        // cap must stay inside that band so post-transition movement keeps advancing semantic travel,
        // and inside vanilla's ±3.9 velocity-packet clamp so the delivered value matches the server value.
        check(WalkFlightHandoff.MAX_HANDOFF_SPEED > 0 && WalkFlightHandoff.MAX_HANDOFF_SPEED <= 4.0,
                "Handoff cap must be inside the steering integrable band");
        Basis384f basis=new Basis384f();Vec384f target=axis(0).mul(1000);
        Vec384f skipped=q();TraversalSteering.deepStep(skipped,basis,target,30,0,0);
        check(skipped.squareDistance(q())==0,"Raw burst is discarded as teleport-scale (the defect)");
        Vec3d clamped=WalkFlightHandoff.toFlight(new Vec3d(30,0,0));
        Vec384f advanced=q();TraversalSteering.deepStep(advanced,basis,target,clamped.x,clamped.y,clamped.z);
        check(advanced.squareDistance(q())>0,"Clamped handoff velocity remains semantically integrable");
    }
    private static Vec384f q(){Vec384f v=Vec384f.ZERO();return v;}
    private static Vec384f axis(int n){float[] d=new float[EmbeddingSpace.DIMENSIONS];d[n]=1;return new Vec384f(d);}

    private static void takeoffDecision() {
        check(WalkFlightHandoff.takeoffIntent(0.42),"Vanilla jump ascent is takeoff intent");
        check(!WalkFlightHandoff.takeoffIntent(0.009),"Sub-takeoff Y delta is not takeoff intent");
        check(!WalkFlightHandoff.takeoffIntent(0),"A settled standing pose is NOT takeoff intent");
        check(!WalkFlightHandoff.takeoffIntent(-0.0784),"Falling is not takeoff intent");
        check(!WalkFlightHandoff.takeoffIntent(Double.NaN),"Non-finite delta is not takeoff intent");
    }

    private static void leaveGroundDecision() {
        // Ascending takeoff keeps the ordinary jump grace, then converts.
        check(!WalkFlightHandoff.leavesGround(true,1,0.42),"Takeoff grace holds early");
        check(!WalkFlightHandoff.leavesGround(true,WalkFlightHandoff.TAKEOFF_GRACE_TICKS,-0.0784),"Takeoff grace holds at the boundary");
        check(WalkFlightHandoff.leavesGround(true,WalkFlightHandoff.TAKEOFF_GRACE_TICKS+1,0),"Expired takeoff grace converts to flight");
        // Walking off an edge (falling, no takeoff intent) still converts immediately.
        check(WalkFlightHandoff.leavesGround(false,1,-0.0784),"Edge walk converts to freeflight immediately");
        check(WalkFlightHandoff.leavesGround(false,1,Double.NEGATIVE_INFINITY),"Non-finite delta converts safely");
        // A settled/stationary shallow pose NEVER auto-converts, however long ownership publication takes:
        // entering on supported ground must not start flight, and floor churn under a standing player
        // must not either (a genuinely missing floor produces a falling delta on the next tick instead).
        for (int tick=1;tick<=500;tick++)
            check(!WalkFlightHandoff.leavesGround(false,tick,0),"Stationary unsupported pose must stay shallow at tick "+tick);
        check(WalkFlightHandoff.leavesGround(false,1,-1e-4-1e-9),"Past the falling epsilon the pose is genuinely airborne");
        check(!WalkFlightHandoff.leavesGround(false,1,-1e-4+1e-9),"At the falling epsilon the pose still counts as settled");
    }

    /** Tick-sequence simulations of the full unsupported loop, as wired in SpiritNavigationService.update. */
    private static void transitionSequences() {
        // Entering on supported ground whose ownership is still publishing: the carrier stands still on
        // the real mesh floor (delta exactly zero every tick) and must remain shallow indefinitely.
        boolean jumping=false;
        for (int tick=1;tick<=200;tick++) {
            if (tick==1) jumping=WalkFlightHandoff.takeoffIntent(0);
            check(!WalkFlightHandoff.leavesGround(jumping,tick,0),"Supported-ground entry must not start flight at tick "+tick);
        }
        // Ordinary jump takeoff that loses support mid-jump: grace, then freeflight near the apex.
        double[] jump={0.42,0.39,0.36,0.33,0.30,0.26,0.22,0.18,0.14,0.10,0.06,0.02,-0.02,-0.06,-0.10,-0.14};
        jumping=false;boolean flown=false;
        for (int tick=1;tick<=jump.length;tick++) {
            if (tick==1) jumping=WalkFlightHandoff.takeoffIntent(jump[0]);
            if (WalkFlightHandoff.leavesGround(jumping,tick,jump[tick-1])){flown=tick>=(WalkFlightHandoff.TAKEOFF_GRACE_TICKS);break;}
        }
        check(flown,"Mid-jump support loss converts after the jump grace, not before");
        // Walking off an edge: immediate freeflight on the first genuinely falling tick.
        jumping=false;
        check(WalkFlightHandoff.leavesGround(jumping,1,-0.0784),"Edge walk freefalls into freeflight on tick one");
        // Floor genuinely vanished beneath a standing player: the next tick falls and converts.
        check(WalkFlightHandoff.leavesGround(false,2,-0.0784),"Vanished floor converts on the first falling tick");
    }

    private static void handoffTrigger() {
        // Handoff is driven by the NAVIGATION-mode transition, not an abilities diff: normal double-jump
        // entry reaches navigation only after vanilla's abilities handler has already preset flying=true
        // server-side, so an abilities-only check would silently skip the handoff.
        check(WalkFlightHandoff.handoffDue(null, true), "Fresh session hands off on first flight assertion");
        check(WalkFlightHandoff.handoffDue(null, false), "Fresh session hands off on first walk assertion");
        check(WalkFlightHandoff.handoffDue(false, true), "Double-jump entry: vanilla preset flying server-side, navigation still hands off");
        check(WalkFlightHandoff.handoffDue(true, false), "Walk acquisition hands off deep->shallow");
        check(!WalkFlightHandoff.handoffDue(false, false), "Repeated shallow assertion must not re-handoff");
        check(!WalkFlightHandoff.handoffDue(true, true), "Repeated deep assertion must not re-handoff");
        // Simulated post-vanilla packet state across a full entry: abilities preset by vanilla each time,
        // session previously shallow — the deep assertion must still hand off exactly once.
        Boolean handed = false;
        int handoffs = 0;
        for (boolean requested : new boolean[]{true, true, true}) {
            if (WalkFlightHandoff.handoffDue(handed, requested)) { handed = requested; handoffs++; }
        }
        check(handoffs == 1, "Double-jump entry hands off exactly once despite preset abilities");
    }

    private static void deliveryReachesControllingClient() {
        // setVelocity alone mutates only server state; the controlling client never sees it. The mapped
        // delivery channel is a self EntityVelocityUpdate packet, applied client-side via setVelocityClient.
        try {
            check(EntityVelocityUpdateS2CPacket.class.getConstructor(int.class, Vec3d.class) != null,
                    "Self velocity packet constructor (id, velocity) exists");
            check(ServerPlayNetworkHandler.class.getMethod("sendPacket", Packet.class) != null,
                    "Player network handler exposes sendPacket delivery");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Mapped delivery targets changed", failure);
        }
        // The handed-off value must survive the packet's /8000 wire quantization EXACTLY, so the client
        // starts the new mode from the same bounded pose the server computed.
        var handed = new EntityVelocityUpdateS2CPacket(1, WalkFlightHandoff.toFlight(new Vec3d(30, 0, 0)));
        near(handed.getVelocityX(), WalkFlightHandoff.MAX_HANDOFF_SPEED, "Clamped handoff velocity survives packet quantization");
        near(handed.getVelocityY(), 0, "Handoff quantization leaves other axes untouched");
        // The delivered value must EQUAL the server value at the cap itself — the cap is chosen so vanilla's
        // packet clamp never rewrites it (the round trip is the delivery contract, not a tolerance).
        var capped = new EntityVelocityUpdateS2CPacket(1, new Vec3d(WalkFlightHandoff.MAX_HANDOFF_SPEED, -WalkFlightHandoff.MAX_HANDOFF_SPEED, 0));
        near(capped.getVelocityX(), WalkFlightHandoff.MAX_HANDOFF_SPEED, "Cap axis round-trips exactly (+)");
        near(capped.getVelocityY(), -WalkFlightHandoff.MAX_HANDOFF_SPEED, "Cap axis round-trips exactly (-)");
        check(handed.getVelocityX() == capped.getVelocityX(), "Delivered cap equals server cap");
        var takeoff = new EntityVelocityUpdateS2CPacket(1, WalkFlightHandoff.toFlight(new Vec3d(0, 0.42, 0)));
        wireNear(takeoff.getVelocityY(), 0.42, "Ordinary jump momentum delivers within one wire step");
        var stop = new EntityVelocityUpdateS2CPacket(1, Vec3d.ZERO);
        check(stop.getVelocityX() == 0 && stop.getVelocityY() == 0 && stop.getVelocityZ() == 0,
                "Delivered expiry stop is exactly zero");
        var plunge = new EntityVelocityUpdateS2CPacket(1, WalkFlightHandoff.toFlight(new Vec3d(0, -40, 0)));
        near(plunge.getVelocityY(), -WalkFlightHandoff.MAX_HANDOFF_SPEED, "Clamped falling residue delivers intact");
        // Ordinary walk handoff: the delivered stop must also round-trip exactly.
        var walkStop = new EntityVelocityUpdateS2CPacket(1, WalkFlightHandoff.toWalk(new Vec3d(1.2, -3.4, .5), true));
        check(walkStop.getVelocityX() == 0 && walkStop.getVelocityY() == 0 && walkStop.getVelocityZ() == 0,
                "Grounded walk handoff delivers an exact zero to the controlling client");
    }

    /** Offline wiring contract: the service must actually DELIVER at both handoff and expiry, and gate on
    * the navigation-mode transition. Source-level by design; the live path needs a running server. */
    private static void serviceWiringContract() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path source = null;
        for (Path dir = root; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java");
            if (Files.exists(candidate)) { source = candidate; break; }
        }
        check(source != null, "SpiritNavigationService source discoverable from " + root);
        String text = Files.readString(source);
        check(text.contains("deliverVelocity(p, p.getVelocity())"), "Mode handoff delivers the new velocity to the controlling client");
        check(text.contains("deliverVelocity(p, Vec3d.ZERO)"), "Expiry stop is delivered, not only set server-side");
        check(text.contains("EntityVelocityUpdateS2CPacket"), "Delivery uses the self EntityVelocityUpdate packet channel");
        check(text.contains("handoffDue"), "Handoff is gated on the navigation-mode transition");
        int deliveries = text.split("deliverVelocity\\(", -1).length - 1;
        check(deliveries >= 3, "Delivery definition plus handoff/expiry call sites all present (found " + deliveries + " references)");
    }

    public static void main(String[] args) throws Exception {
        toFlightContinuity();
        toWalkGrounding();
        handoffSpeedIsSemanticallyIntegrable();
        takeoffDecision();
        leaveGroundDecision();
        transitionSequences();
        handoffTrigger();
        deliveryReachesControllingClient();
        serviceWiringContract();
        System.out.println("WalkFlightHandoffTest: "+checks+" checks passed");
    }
}
