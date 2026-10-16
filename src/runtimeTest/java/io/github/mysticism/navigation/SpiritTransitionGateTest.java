package io.github.mysticism.navigation;

import java.util.UUID;
import io.github.mysticism.navigation.SpiritTransitionGate.Handoff;

/** Executes production handoff policy without starting Minecraft or an embedding service. */
public final class SpiritTransitionGateTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final UUID PLAYER = UUID.fromString("14741104-5e7a-4ba5-8a64-292cb65c769e");
    private static Handoff entry() {
        return new Handoff(PLAYER, "minecraft:overworld", SpiritTransitionGate.SPIRIT,
                -12345.03125, 72.5, 9876.875, 37.25f, -14.5f);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { check(true, "rejected invalid pose"); return; }
        throw new AssertionError("Invalid handoff accepted");
    }
    public static void main(String[] args) {
        var gate = new SpiritTransitionGate();
        Object connection = new Object();
        Handoff entry = entry();
        check(gate.consume(connection, PLAYER, entry.from(), entry.to(), true, 0).isEmpty(), "Ordinary teleport unchanged");
        // Many exit/reentry cycles, preserving negative/fractional source feet and both camera angles.
        for (int i = 0; i < 100; i++) {
            long now = i * 20_000_000_000L;
            Handoff exit = new Handoff(PLAYER, entry.to(), entry.from(), entry.x() + i * .125,
                    entry.y(), entry.z() - i * .25, entry.yaw(), entry.pitch());
            for (Handoff next : new Handoff[]{entry, exit}) {
                gate.offer(connection, next, now);
                var accepted = gate.consume(connection, PLAYER, next.from(), next.to(), true, now + 1);
                check(accepted.orElseThrow().equals(next), "Exact source pose roundtrip");
                check(accepted.get().entering() == (next == entry), "Entry/exit distinguished");
                check(gate.consume(connection, PLAYER, next.from(), next.to(), true, now + 2).isEmpty(), "One-shot only");
            }
        }
        for (int failure = 0; failure < 7; failure++) {
            gate.offer(connection, entry, 100);
            var result = gate.consume(failure == 0 ? new Object() : connection,
                    failure == 1 ? UUID.randomUUID() : PLAYER,
                    failure == 2 ? "minecraft:the_nether" : entry.from(),
                    failure == 3 ? "minecraft:the_end" : entry.to(), failure != 4,
                    failure == 5 ? 99 : failure == 6 ? 101 + SpiritTransitionGate.MAX_AGE_NANOS : 101);
            check(result.isEmpty(), "Death/connection/player/dimension/age mismatch must keep vanilla screen " + failure);
            check(gate.consume(connection, PLAYER, entry.from(), entry.to(), true, 102).isEmpty(), "Mismatch consumes hint");
        }
        gate.offer(connection, entry, 10);
        gate.clear();
        check(gate.consume(connection, PLAYER, entry.from(), entry.to(), true, 11).isEmpty(), "Disconnect/reload clears");
        gate.offer(connection, entry, 10);
        check(gate.consume(connection, PLAYER, entry.from(), entry.to(), true, 10 + SpiritTransitionGate.MAX_AGE_NANOS).isPresent(), "Age bound inclusive");
        Handoff nether = new Handoff(PLAYER, "minecraft:the_nether", SpiritTransitionGate.SPIRIT, 0, 42.5, 0, 0, 0);
        gate.offer(connection, entry, 10); gate.offer(connection, nether, 11);
        check(gate.consume(connection, PLAYER, nether.from(), nether.to(), true, 12).orElseThrow().equals(nether), "Newest replaces pending");
        rejects(() -> new Handoff(PLAYER, "minecraft:overworld", "minecraft:the_nether", 0, 0, 0, 0, 0));
        rejects(() -> new Handoff(PLAYER, SpiritTransitionGate.SPIRIT, SpiritTransitionGate.SPIRIT, 0, 0, 0, 0, 0));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            rejects(() -> new Handoff(PLAYER, entry.from(), entry.to(), invalid, 0, 0, 0, 0));
        rejects(() -> new Handoff(PLAYER, entry.from(), entry.to(), 0, 0, 0, Float.NaN, 0));
        rejects(() -> new Handoff(PLAYER, entry.from(), entry.to(), 0, 0, 0, 0, Float.POSITIVE_INFINITY));
        System.out.println("SpiritTransitionGateTest: " + checks + " checks passed (production policy, no game)");
    }
}
