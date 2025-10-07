package io.github.mysticism.client.spiritworld;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

@Environment(EnvType.CLIENT)
public final class SpiritSkybox {
    private SpiritSkybox() {}

    // Your spirit world key (RegistryKey<World>, not Identifier)
    private static final RegistryKey<World> SPIRIT_WORLD_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("mysticism", "spirit"));

    public enum Mode { FLAT, TEXTURE }

    // Runtime-configurable
    private static volatile Mode mode = Mode.FLAT;
    private static volatile int flatColorArgb = 0xFF7F7F7F; // default flat gray
    private static volatile Identifier skyTexture = null;   // set via setTexture()

    // big cube radius (keep very large so it never intersects fog/terrain)
    private static final float SKY_RADIUS = 2048f;

    public static void init() {
        // Single-arg callback in 1.21.x
        DimensionRenderingRegistry.registerSkyRenderer(SPIRIT_WORLD_KEY, ctx -> {
            var mc = MinecraftClient.getInstance();
            if (mc.world == null || !mc.world.getRegistryKey().equals(SPIRIT_WORLD_KEY)) return;

            MatrixStack matrices = ctx.matrixStack();
            VertexConsumerProvider consumers = ctx.consumers();
            if (matrices == null || consumers == null) return;

            // NOTE: In modern hooks, 0,0,0 is already camera-centered; do NOT translate by -camera
            matrices.push();

            switch (mode) {
                case FLAT -> drawFlatCubeInward(matrices, consumers, flatColorArgb);
                case TEXTURE -> {
                    if (skyTexture != null) {
                        // Nudge texture manager to make sure it’s present (if it’s from your resources)
                        mc.getTextureManager().getTexture(skyTexture);
                        drawTexturedCubeInward(matrices, consumers, skyTexture);
                    } else {
                        drawFlatCubeInward(matrices, consumers, flatColorArgb);
                    }
                }
            }

            matrices.pop();
        });
    }

    // ---------- Public runtime controls ----------
    public static void setMode(Mode newMode) {
        mode = newMode == null ? Mode.FLAT : newMode;
    }

    public static void setFlatColor(int argb) {
        flatColorArgb = argb;
    }

    /** Texture should be a valid resource id (e.g., mysticism:textures/sky/fog.png passed as Identifier.of("mysticism","textures/sky/fog.png")). */
    public static void setTexture(Identifier id) {
        skyTexture = id;
        mode = Mode.TEXTURE;
    }

    // ---------- Draw helpers ----------
    private static void drawFlatCubeInward(MatrixStack matrices, VertexConsumerProvider consumers, int argb) {
        float r = ColorHelper.getRed(argb)   / 255f;
        float g = ColorHelper.getGreen(argb) / 255f;
        float b = ColorHelper.getBlue(argb)  / 255f;
        float a = ColorHelper.getAlpha(argb) / 255f;

        RenderLayer layer = RenderLayer.getSunriseSunset(); // POSITION_COLOR pipeline
        VertexConsumer vc = consumers.getBuffer(layer);
        MatrixStack.Entry e = matrices.peek();

        // Inward-facing 6 quads of a big cube centered at camera
        float R = SKY_RADIUS;
        // +X
        quadColorInward(vc, e,  R, -R, -R,  R,  R,  R, r,g,b,a);
        // -X
        quadColorInward(vc, e, -R, -R,  R, -R,  R, -R, r,g,b,a);
        // +Y
        quadColorInward(vc, e, -R,  R, -R,  R,  R,  R, r,g,b,a);
        // -Y
        quadColorInward(vc, e,  R, -R, -R, -R, -R,  R, r,g,b,a);
        // +Z
        quadColorInward(vc, e, -R, -R,  R,  R,  R,  R, r,g,b,a);
        // -Z
        quadColorInward(vc, e,  R, -R, -R, -R,  R, -R, r,g,b,a);
    }

    private static void drawTexturedCubeInward(MatrixStack matrices, VertexConsumerProvider consumers, Identifier tex) {
        RenderLayer layer = RenderLayer.getCelestial(tex); // POSITION_TEX_COLOR pipeline
        VertexConsumer vc = consumers.getBuffer(layer);
        MatrixStack.Entry e = matrices.peek();

        float R = SKY_RADIUS;
        int overlay = OverlayTexture.DEFAULT_UV;
        int light   = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // For each face, use reversed winding and suitable UVs (simple full-quad mapping)
        // +X
        quadTexInward(vc, e,  R, -R, -R,  R,  R,  R, 0f,1f, 1f,0f, overlay, light);
        // -X
        quadTexInward(vc, e, -R, -R,  R, -R,  R, -R, 0f,1f, 1f,0f, overlay, light);
        // +Y
        quadTexInward(vc, e, -R,  R, -R,  R,  R,  R, 0f,1f, 1f,0f, overlay, light);
        // -Y
        quadTexInward(vc, e,  R, -R, -R, -R, -R,  R, 0f,1f, 1f,0f, overlay, light);
        // +Z
        quadTexInward(vc, e, -R, -R,  R,  R,  R,  R, 0f,1f, 1f,0f, overlay, light);
        // -Z
        quadTexInward(vc, e,  R, -R, -R, -R,  R, -R, 0f,1f, 1f,0f, overlay, light);
    }

    // ------- Inward-winding emitters (so faces are front-facing from inside) -------
    private static void quadColorInward(VertexConsumer vc, MatrixStack.Entry e,
                                        float x0,float y0,float z0, float x1,float y1,float z1,
                                        float r,float g,float b,float a) {
        // Corner order chosen so front face is the "inside" of the cube
        vc.vertex(e.getPositionMatrix(), x0, y0, z0).color(r,g,b,a).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y0, z1).color(r,g,b,a).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y1, z1).color(r,g,b,a).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x0, y1, z0).color(r,g,b,a).normal(0,0,1);
    }

    private static void quadTexInward(VertexConsumer vc, MatrixStack.Entry e,
                                      float x0,float y0,float z0, float x1,float y1,float z1,
                                      float u0,float v0,float u1,float v1,
                                      int overlay, int light) {
        vc.vertex(e.getPositionMatrix(), x0, y0, z0).color(1,1,1,1).texture(u0, v1).overlay(overlay).light(light).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y0, z1).color(1,1,1,1).texture(u1, v1).overlay(overlay).light(light).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x1, y1, z1).color(1,1,1,1).texture(u1, v0).overlay(overlay).light(light).normal(0,0,1);
        vc.vertex(e.getPositionMatrix(), x0, y1, z0).color(1,1,1,1).texture(u0, v0).overlay(overlay).light(light).normal(0,0,1);
    }
}
