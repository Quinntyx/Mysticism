package io.github.mysticism.client.spiritworld;

/** No GL calls: a depth snapshot is usable only for the exact surface that produced it. */
final class SpiritPostprocessFrame {
    record Surface(Object owner, int width, int height, int framebuffer, int color, int depth) {
        boolean valid() {
            return owner != null && width > 0 && height > 0 && framebuffer >= 0 && color > 0 && depth > 0;
        }
    }

    private Surface prepared;
    private boolean depthCaptured;

    void prepare(Surface surface) {
        invalidate();
        if (!surface.valid()) throw new IllegalArgumentException("Invalid postprocess surface");
        prepared = surface;
    }

    boolean ready() { return prepared != null; }

    boolean matches(Surface current) {
        return prepared != null && current.valid() && prepared.owner() == current.owner()
                && prepared.equals(current);
    }

    boolean captureDepth(Surface current) {
        if (!matches(current)) { invalidate(); return false; }
        depthCaptured = true;
        return true;
    }

    /** Always consume, even if the frame was resized/reloaded or no depth callback arrived. */
    boolean consume(Surface current) {
        boolean usable = depthCaptured && matches(current);
        invalidate();
        return usable;
    }

    void invalidate() { prepared = null; depthCaptured = false; }
}
