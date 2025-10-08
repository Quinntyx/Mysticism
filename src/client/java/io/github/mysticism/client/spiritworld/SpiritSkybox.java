package io.github.mysticism.client.spiritworld;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

@Environment(EnvType.CLIENT)
public final class SpiritSkybox {
    private SpiritSkybox() {}

    private static final RegistryKey<World> SPIRIT_WORLD_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("mysticism", "spirit"));

    public enum Mode { FLAT, TEXTURE }

    private static volatile Mode mode = Mode.FLAT;
    private static volatile int flatColorArgb = 0xFF7F7F7F;
    private static volatile Identifier skyTexture = null;

    private static final float SKY_RADIUS = 2048f;

    public static void init() {
        // CORRECTED: The callback uses WorldRenderContext, as you correctly pointed out.
        DimensionRenderingRegistry.registerSkyRenderer(SPIRIT_WORLD_KEY, (WorldRenderContext context) -> {
            var mc = MinecraftClient.getInstance();
            if (mc.world == null || !mc.world.getRegistryKey().equals(SPIRIT_WORLD_KEY)) return;

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();
            if (matrices == null || consumers == null) return;

            matrices.push();

            switch (mode) {
                case FLAT -> drawFlatCubeInward(matrices, consumers, flatColorArgb);
                case TEXTURE -> {
                    if (skyTexture != null) {
                        drawTexturedCubeInward(matrices, consumers, skyTexture);
                    } else {
                        drawFlatCubeInward(matrices, consumers, flatColorArgb);
                    }
                }
            }
            matrices.pop();
        });
    }

    // ---------- Public runtime controls (no changes needed) ----------
    public static void setMode(Mode newMode) {
        mode = newMode == null ? Mode.FLAT : newMode;
    }

    public static void setFlatColor(int argb) {
        flatColorArgb = argb;
    }

    public static void setTexture(Identifier id) {
        skyTexture = id;
        mode = Mode.TEXTURE;
    }

    // ---------- Draw helpers ----------
    private static void drawFlatCubeInward(MatrixStack matrices, VertexConsumerProvider consumers, int argb) {
        // Manual bitwise color extraction for older versions (this was correct)
        float a = (float)(argb >> 24 & 255) / 255.0F;
        float r = (float)(argb >> 16 & 255) / 255.0F;
        float g = (float)(argb >> 8 & 255) / 255.0F;
        float b = (float)(argb & 255) / 255.0F;

        // CORRECTED: Use a suitable RenderLayer like getLightning for a POSITION_COLOR format.
        RenderLayer layer = RenderLayer.getLightning();
        VertexConsumer vc = consumers.getBuffer(layer);
        MatrixStack.Entry e = matrices.peek();

        float R = SKY_RADIUS;
        quadColorInward(vc, e,  R, -R, -R,  R,  R,  R, r,g,b,a);
        quadColorInward(vc, e, -R, -R,  R, -R,  R, -R, r,g,b,a);
        quadColorInward(vc, e, -R,  R, -R,  R,  R,  R, r,g,b,a);
        quadColorInward(vc, e,  R, -R, -R, -R, -R,  R, r,g,b,a);
        quadColorInward(vc, e, -R, -R,  R,  R,  R,  R, r,g,b,a);
        quadColorInward(vc, e,  R, -R, -R, -R,  R, -R, r,g,b,a);
    }

    private static void drawTexturedCubeInward(MatrixStack matrices, VertexConsumerProvider consumers, Identifier tex) {
        // CORRECTED: Use a RenderLayer suitable for sky textures, like getEyes(tex).
        RenderLayer layer = RenderLayer.getEyes(tex);
        VertexConsumer vc = consumers.getBuffer(layer);
        MatrixStack.Entry e = matrices.peek();

        float R = SKY_RADIUS;
        int overlay = OverlayTexture.DEFAULT_UV;
        int light   = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        quadTexInward(vc, e,  R, -R, -R,  R,  R,  R, 0f,1f, 1f,0f, overlay, light);
        quadTexInward(vc, e, -R, -R,  R, -R,  R, -R, 0f,1f, 1f,0f, overlay, light);
        quadTexInward(vc, e, -R,  R, -R,  R,  R,  R, 0f,1f, 1f,0f, overlay, light);
        quadTexInward(vc, e,  R, -R, -R, -R, -R,  R, 0f,1f, 1f,0f, overlay, light);
        quadTexInward(vc, e, -R, -R,  R,  R,  R,  R, 0f,1f, 1f,0f, overlay, light);
        quadTexInward(vc, e,  R, -R, -R, -R,  R, -R, 0f,1f, 1f,0f, overlay, light);
    }

    // ------- Inward-winding emitters -------
    private static void quadColorInward(VertexConsumer vc, MatrixStack.Entry e,
                                        float x0,float y0,float z0, float x1,float y1,float z1,
                                        float r,float g,float b,float a) {
        // CORRECTED: Removed the .next() calls.
        vc.vertex(e.getPositionMatrix(), x0, y0, z0).color(r,g,b,a).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y0, z1).color(r,g,b,a).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y1, z1).color(r,g,b,a).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x0, y1, z0).color(r,g,b,a).normal(0,0,1);
    }

    private static void quadTexInward(VertexConsumer vc, MatrixStack.Entry e,
                                      float x0,float y0,float z0, float x1,float y1,float z1,
                                      float u0,float v0,float u1,float v1,
                                      int overlay, int light) {
        // CORRECTED: Removed the .next() calls.
        vc.vertex(e.getPositionMatrix(), x0, y0, z0).color(1,1,1,1).texture(u0, v1).overlay(overlay).light(light).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y0, z1).color(1,1,1,1).texture(u1, v1).overlay(overlay).light(light).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y1, z1).color(1,1,1,1).texture(u1, v0).overlay(overlay).light(light).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x0, y1, z0).color(1,1,1,1).texture(u0, v0).overlay(overlay).light(light).normal(0,0,1);
    }
}