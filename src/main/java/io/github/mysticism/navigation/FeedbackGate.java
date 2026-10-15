package io.github.mysticism.navigation;

import java.util.Objects;

/** Suppresses identical navigation feedback inside a tick window so repeated walk failures or
 * requests cannot spam the player, while genuinely new or changed information always shows.
 * Pure Java: no Minecraft types, so regressions can exercise every branch without a server. */
public final class FeedbackGate {
    private final long windowTicks;
    private String last;
    private long until = Long.MIN_VALUE;

    public FeedbackGate(long windowTicks) {
        if (windowTicks < 0) throw new IllegalArgumentException("Negative feedback window");
        this.windowTicks = windowTicks;
    }

    /** True when this exact message may be shown now; an identical message inside the window is suppressed. */
    public boolean allow(long tick, String message) {
        Objects.requireNonNull(message, "message");
        if (message.equals(last) && tick < until) return false;
        last = message;
        until = tick + windowTicks;
        return true;
    }
}
