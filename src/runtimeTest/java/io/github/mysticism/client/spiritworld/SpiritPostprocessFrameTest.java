package io.github.mysticism.client.spiritworld;

/** Exercises the production frame gate without bootstrapping Minecraft or a GL context. */
public final class SpiritPostprocessFrameTest {
    private static int checks;
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    private static SpiritPostprocessFrame.Surface surface(Object owner, int width, int height, int fbo, int color, int depth) {
        return new SpiritPostprocessFrame.Surface(owner, width, height, fbo, color, depth);
    }
    public static void main(String[] args) {
        var gate = new SpiritPostprocessFrame();
        Object main = new Object();
        var initial = surface(main, 1280, 720, 1, 2, 3);
        check(!gate.ready() && !gate.consume(initial), "Startup cannot consume uninitialized depth");
        gate.prepare(initial);
        check(gate.ready() && gate.matches(initial), "Normal frame is prepared");
        check(!gate.consume(initial), "Missing AFTER_TRANSLUCENT must not process stale depth");
        gate.prepare(initial);
        check(gate.captureDepth(initial) && gate.consume(initial), "Normal depth capture/render");
        check(!gate.consume(initial), "END cannot render a frame twice");

        var replacements = new SpiritPostprocessFrame.Surface[]{
                surface(main, 1920, 1080, 4, 5, 6), // resize changes both dimensions and GL names
                surface(main, 1280, 720, 1, 2, 7), // depth-only recreation
                surface(main, 1280, 720, 1, 8, 3), // color-only recreation
                surface(main, 1280, 720, 9, 2, 3), // FBO recreation
                surface(new Object(), 1280, 720, 1, 2, 3), // main object replaced, GL names reused
                surface(main, 0, 0, 1, 2, 3), // minimized/unallocated
                surface(main, 1280, 720, -1, 2, 3),
                surface(main, 1280, 720, 1, -1, 3),
                surface(main, 1280, 720, 1, 2, -1),
                surface(main, 1280, 720, 1, 0, 3),
                surface(main, 1280, 720, 1, 2, 0)
        };
        for (var changed : replacements) {
            gate.prepare(initial);
            check(!gate.captureDepth(changed) && !gate.ready(), "Change before capture invalidates frame: " + changed);
            gate.prepare(initial);
            gate.captureDepth(initial);
            check(!gate.consume(changed) && !gate.ready(), "Change after capture rejects restore/render: " + changed);
        }
        gate.prepare(initial);
        gate.captureDepth(initial);
        gate.invalidate(); // Satin setup/reload or disconnect
        check(!gate.consume(initial), "Reload must invalidate even if GL driver reuses every name");
        gate.prepare(initial);
        gate.captureDepth(initial);
        gate.prepare(initial); // new world frame arrived without END
        check(!gate.consume(initial), "New frame must not inherit previous frame depth");
        for (int i = 0; i < 64; i++) {
            var resized = surface(main, 640 + i, 360 + i, 10 + i, 80 + i, 160 + i);
            gate.prepare(resized);
            check(gate.captureDepth(resized) && gate.consume(resized), "Resize/reload recovery " + i);
        }
        check(!gate.ready(), "No stale readiness after render");
        // Exercise the retained units together: option changes must widen presentation
        // independently of surface lifecycle, while reload/resize still reject old depth.
        for (int chunks : new int[]{8, 12, 16, 32, 8}) {
            var range = SpiritRenderSettings.fogRange(chunks);
            gate.prepare(initial);
            gate.captureDepth(initial);
            gate.invalidate(); // resource reload with identical GL names
            check(!gate.consume(initial), "Option " + chunks + ": reload cannot reuse old depth");
            var resized = surface(main, 800 + chunks, 600 + chunks, 20, 21, 22);
            gate.prepare(resized);
            check(range.containsDistanceSquared(Math.pow(chunks * 12.0, 2)), "Option " + chunks + ": useful distant terrain remains drawable during resize");
            check(gate.captureDepth(resized) && gate.consume(resized), "Option " + chunks + ": fresh resized depth recovers");
            check(!gate.consume(resized), "Option " + chunks + ": fresh depth remains one-shot");
        }
        try {
            gate.prepare(surface(null, 1280, 720, 1, 2, 3));
            throw new AssertionError("Invalid surface accepted");
        } catch (IllegalArgumentException expected) {
            check(!gate.ready(), "Invalid prepare clears previous state");
        }
        System.out.println("SpiritPostprocessFrameTest passed: " + checks + " checks");
    }
}
