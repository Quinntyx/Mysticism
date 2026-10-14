package io.github.mysticism.navigation;

import java.util.UUID;
import io.github.mysticism.navigation.SpiritTransitionGate.Handoff;

/** Production snapshot lifetime policy, including vanilla reset's intermediate forced render. */
public final class SpiritTransitionBridgeTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        UUID player = UUID.randomUUID(); Object connection = new Object();
        var bridge = new SpiritTransitionBridge();
        for (boolean entering : new boolean[]{true, false}) {
            String from = entering ? "minecraft:overworld" : SpiritTransitionGate.SPIRIT;
            String to = entering ? SpiritTransitionGate.SPIRIT : "minecraft:overworld";
            var h = new Handoff(player, from, to, -.25, 65.5, 17.125, 123, -40);
            check(bridge.begin(connection, player, from, 90, h, 100), "Fresh correct outgoing WORLD");
            check(bridge.hold(connection, player, from, true, false, 101), "reset forced render must retain outgoing color");
            check(bridge.hold(connection, player, from, true, true, 102), "Old-world readiness cannot terminate new-world bridge");
            check(bridge.hold(connection, player, to, false, false, 103), "Delivery gap keeps actual outgoing color");
            check(bridge.hold(connection, player, to, false, false, 104), "Multiple gap frames never overwrite snapshot");
            check(!bridge.hold(connection, player, to, false, true, 105), "Real destination ready releases bridge");
            check(!bridge.hold(connection, player, to, false, false, 106), "No stale replay after release");
            check(!bridge.begin(connection, player, to, 90, h, 100), "Old entry photo cannot stand in for current exit world");
            check(!bridge.begin(connection, UUID.randomUUID(), from, 90, h, 100), "Other player's snapshot rejected");
            check(!bridge.begin(connection, player, from, 101, h, 100), "Future capture rejected");
            check(!bridge.begin(connection, player, from, 0, h, 1_000_000_001L), "Stale snapshot rejected");
            for (int failure = 0; failure < 6; failure++) {
                bridge.begin(connection, player, from, 90, h, 100);
                check(!bridge.hold(failure == 0 ? new Object() : connection,
                        failure == 1 ? UUID.randomUUID() : player,
                        failure == 2 ? "minecraft:the_end" : failure == 3 ? from : to, false, false,
                        failure == 4 ? 99 : failure == 5 ? 101 + SpiritTransitionGate.MAX_AGE_NANOS : 101),
                        "No color replay after mismatched connection/player/world/time " + failure);
                check(!bridge.hold(connection, player, to, false, false, 102), "Rejected bridge stays cancelled");
            }
            bridge.begin(connection, player, from, 90, h, 100); bridge.cancel();
            check(!bridge.hold(connection, player, to, false, false, 101), "Disconnect/reload cancellation");
        }
        System.out.println("SpiritTransitionBridgeTest: " + checks + " checks passed (production policy, no GL)");
    }
}
