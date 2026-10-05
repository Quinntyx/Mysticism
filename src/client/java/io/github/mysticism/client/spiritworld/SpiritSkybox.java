package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** Immediate sky geometry: the Fabric sky callback does NOT yet have a MatrixStack. */
public final class SpiritSkybox {
    public enum Mode { FLAT, TEXTURE }
    private static Mode mode = Mode.FLAT;
    private static int flatColorArgb = 0xff7f7f7f;
    private static Identifier skyTexture;
    private static boolean initialized;
    private static final float RADIUS = 96;
    private static final float[][] FACES = {
            {1,-1,-1, 1,-1,1, 1,1,1, 1,1,-1},
            {-1,-1,1, -1,-1,-1, -1,1,-1, -1,1,1},
            {-1,1,-1, 1,1,-1, 1,1,1, -1,1,1},
            {-1,-1,1, 1,-1,1, 1,-1,-1, -1,-1,-1},
            {1,-1,1, -1,-1,1, -1,1,1, 1,1,1},
            {-1,-1,-1, 1,-1,-1, 1,1,-1, -1,1,-1}
    };

    private SpiritSkybox() {}
    public static void init() {
        if (initialized) return;
        initialized = true;
        DimensionRenderingRegistry.registerSkyRenderer(
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("mysticism", "spirit")), SpiritSkybox::render);
    }
    public static void setMode(Mode value) { mode = value == null ? Mode.FLAT : value; }
    public static void setFlatColor(int argb) { flatColorArgb = argb; }
    public static void setTexture(Identifier texture) { skyTexture = texture; mode = Mode.TEXTURE; }

    private static void render(WorldRenderContext context) {
        boolean textured = mode == Mode.TEXTURE && skyTexture != null;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false); // leave depth=1 for sky rays, never fake terrain depth
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        try {
            if (textured) {
                RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
                RenderSystem.setShaderTexture(0, skyTexture);
            } else RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            var buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
                    textured ? VertexFormats.POSITION_TEXTURE_COLOR : VertexFormats.POSITION_COLOR);
            for (float[] face : FACES) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    var consumer = buffer.vertex(context.positionMatrix(), face[vertex * 3] * RADIUS,
                            face[vertex * 3 + 1] * RADIUS, face[vertex * 3 + 2] * RADIUS);
                    if (textured) consumer.texture(vertex == 1 || vertex == 2 ? 1 : 0, vertex >= 2 ? 0 : 1);
                    consumer.color(textured ? 0xffffffff : flatColorArgb);
                }
            }
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
        }
    }
}
