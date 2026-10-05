package io.github.mysticism.client.gui.guidebook;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;

/** Original CPU pixel shimmer: no shader lifecycle, world effects, textures or GL state changes. */
final class GuidebookShimmer {
    private GuidebookShimmer() {}
    static void render(DrawContext context, int width, int height, boolean reducedMotion) {
        context.fillGradient(0, 0, width, height, 0xff21122e, 0xff100d1b);
        if (reducedMotion) return; // Static purple is the deliberate reduced-motion fallback.
        long phase = Util.getMeasuringTimeMs() / 180;
        // Sparse 2px cells. Adapt density to keep CPU work bounded on large/low-scale windows.
        int stride = Math.max(18, (int)Math.ceil(Math.sqrt((double)width * height / 1600)));
        for (int y = 0; y < height; y += stride) for (int x = 0; x < width; x += stride) {
            int hash = (x * 73428767) ^ (y * 912931);
            int band = (int)Math.floorMod(x + y - phase * 2, 192);
            int alpha = band < 28 ? 38 : 10;
            int dx = 2 * Math.floorMod(hash, 7), dy = 2 * Math.floorMod(hash >>> 8, 7);
            context.fill(x + dx, y + dy, x + dx + 2, y + dy + 2, (alpha << 24) | 0xbc8cdf);
        }
    }
}
