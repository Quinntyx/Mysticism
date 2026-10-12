package io.github.mysticism.navigation;

import io.github.mysticism.net.SpiritFlightCorrectionPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;

/** Exercises the real barrier codec together with production walk arbitration, without a server. */
public final class FlightCorrectionPayloadTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static SpiritFlightCorrectionPayload roundtrip(SpiritFlightCorrectionPayload payload) {
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            SpiritFlightCorrectionPayload.CODEC.encode(buffer, payload);
            check(buffer.readableBytes() == 16, "Barrier is one bounded opaque token, not player-controlled flight/epoch data");
            var decoded = SpiritFlightCorrectionPayload.CODEC.decode(buffer);
            check(decoded.equals(payload), "Barrier token survives both S2C and C2S transport unchanged");
            check(!buffer.isReadable(), "Codec consumes the whole token");
            return decoded;
        } finally {
            buffer.release();
        }
    }
    public static void main(String[] args) {
        WalkIntentTracker tracker = new WalkIntentTracker();
        check(tracker.flightRequest(false) == WalkIntentTracker.FlightRequest.WALK && tracker.startWalk(),
                "Original flight-off starts the walk");
        var server = new SpiritFlightCorrectionPayload(tracker.serverCorrected(true));
        var client = roundtrip(server); // arrives AFTER vanilla abilities on the client game thread
        tracker.bumpDestination();
        tracker.endWalk();
        // The earlier client flight-off arrives before the barrier echo on the ordered connection.
        check(tracker.flightRequest(false) == WalkIntentTracker.FlightRequest.IGNORE,
                "Pre-receipt stale flight-off cannot override the retarget");
        var acknowledgment = roundtrip(client);
        check(tracker.acknowledgeCorrection(acknowledgment.token()), "Server accepts the echoed current barrier");
        check(!tracker.pending(), "Receipt never revives the cancelled request");
        check(tracker.flightRequest(false) == WalkIntentTracker.FlightRequest.WALK && tracker.startWalk(),
                "Post-receipt genuine retry works without a flight-on packet");
        check(!tracker.superseded(), "Retry belongs to the latest destination epoch");
        check(!tracker.acknowledgeCorrection(acknowledgment.token()), "Replayed wire acknowledgment cannot rearm another retry");
        tracker.endWalk();
        check(tracker.flightRequest(false) == WalkIntentTracker.FlightRequest.IGNORE,
                "A duplicate off after cancellation remains suppressed");
        var truncated = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            truncated.writeLong(1);
            boolean rejected = false;
            try { SpiritFlightCorrectionPayload.CODEC.decode(truncated); }
            catch (IndexOutOfBoundsException expected) { rejected = true; }
            check(rejected, "Truncated acknowledgment is rejected by the real codec");
        } finally {
            truncated.release();
        }
        System.out.println("FlightCorrectionPayloadTest passed: " + assertions + " assertions");
    }
}
