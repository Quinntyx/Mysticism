package io.github.mysticism.navigation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One-shot, connection-local permission to omit only a spirit dimension-change screen.
 * No terrain, semantic state, teleport authority or persisted state lives here. */
public final class SpiritTransitionGate {
    public static final String SPIRIT = "mysticism:spirit";
    public static final long MAX_AGE_NANOS = 5_000_000_000L;

    public record Handoff(UUID player, String from, String to,
                          double x, double y, double z, float yaw, float pitch) {
        public Handoff {
            Objects.requireNonNull(player);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (from.isEmpty() || to.isEmpty() || from.equals(to)
                    || !(SPIRIT.equals(from) ^ SPIRIT.equals(to))
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch))
                throw new IllegalArgumentException("Invalid spirit handoff");
        }
        public boolean entering() { return SPIRIT.equals(to); }
    }

    private Object connection;
    private Handoff pending;
    private long received;

    public void offer(Object connection, Handoff handoff, long now) {
        this.connection = Objects.requireNonNull(connection);
        pending = Objects.requireNonNull(handoff);
        received = now;
    }

    /** The very next respawn consumes the hint, even if it is a death or a mismatched teleport. */
    public Optional<Handoff> consume(Object connection, UUID player, String from, String to,
                                     boolean alive, long now) {
        Handoff next = pending;
        boolean valid = next != null && this.connection == connection && alive
                && next.player().equals(player) && next.from().equals(from) && next.to().equals(to)
                && now - received >= 0 && now - received <= MAX_AGE_NANOS;
        clear();
        return valid ? Optional.of(next) : Optional.empty();
    }

    public void clear() { connection = null; pending = null; received = 0; }
}
