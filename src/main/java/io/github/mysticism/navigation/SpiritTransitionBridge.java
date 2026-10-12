package io.github.mysticism.navigation;

import java.util.UUID;
import io.github.mysticism.navigation.SpiritTransitionGate.Handoff;

/** Lifetime/provenance policy for the client-only outgoing world-color snapshot. No GL types. */
public final class SpiritTransitionBridge {
    private Handoff handoff;
    private Object connection;
    private long started;

    public boolean begin(Object connection, UUID imagePlayer, String imageDimension, long captured,
                         Handoff handoff, long now) {
        cancel();
        if (connection == null || !handoff.player().equals(imagePlayer) || !handoff.from().equals(imageDimension)
                || now - captured < 0 || now - captured > 1_000_000_000L) return false;
        this.connection = connection; this.handoff = handoff; started = now;
        return true;
    }

    public boolean hold(Object connection, UUID player, String dimension, boolean respawning,
                        boolean destinationReady, long now) {
        if (handoff == null) return false;
        boolean current = this.connection == connection && handoff.player().equals(player)
                && now - started >= 0 && now - started <= SpiritTransitionGate.MAX_AGE_NANOS;
        // MinecraftClient.reset forces a render while the outgoing world is STILL installed.
        if (current && respawning && handoff.from().equals(dimension)) return true;
        if (current && handoff.to().equals(dimension) && !destinationReady) return true;
        cancel();
        return false;
    }

    public void cancel() { handoff = null; connection = null; started = 0; }
}
