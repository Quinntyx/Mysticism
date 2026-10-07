package io.github.mysticism.client.gui.guidebook;

import net.minecraft.client.gui.DrawContext;

/** Original GPU pixel shimmer with a bounded static fallback if the shader is unavailable. */
final class GuidebookShimmer {
    private GuidebookShimmer() {}
    static void render(DrawContext context, int width, int height, boolean reducedMotion) {
        if (width <= 0 || height <= 0) return;
        // Flush pending GUI work before the custom quad, then complete its fallback batch
        // before tree/pages/widgets/items. Nothing enters the world postprocess chain.
        context.draw(() -> {
            if (!GuidebookShimmerShader.render(context, width, height, reducedMotion))
                context.fillGradient(0, 0, width, height, 0xff21122e, 0xff100d1b);
        });
    }
}
