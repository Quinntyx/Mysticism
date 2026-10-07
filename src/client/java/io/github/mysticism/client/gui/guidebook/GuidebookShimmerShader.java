package io.github.mysticism.client.gui.guidebook;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/** Dedicated GUI core shader. No world chain, framebuffer, texture or persistent buffer. */
final class GuidebookShimmerShader {
    static final Identifier ID = Identifier.of("mysticism", "guidebook_shimmer");
    static final long PERIOD_MS = 17_280L;
    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism/GuideShimmer");
    private static Binding binding;
    private static boolean registered, stopped;

    private record Binding(ShaderProgram program, GlUniform size, GlUniform phase) {}
    private GuidebookShimmerShader() {}

    static void init() {
        if (registered) return;
        registered = true;
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            // Called for every reload. Never retain the program vanilla is about to close.
            binding = null;
            if (stopped) return;
            try {
                context.register(ID, VertexFormats.POSITION, program -> {
                    try {
                        if (GL20.glGetProgrami(program.getGlRef(), GL20.GL_LINK_STATUS) != GL11.GL_TRUE)
                            throw new IllegalStateException("Guide shader did not link");
                        var size = Objects.requireNonNull(program.getUniform("GuiSize"), "GuiSize uniform");
                        var phase = Objects.requireNonNull(program.getUniform("Phase"), "Phase uniform");
                        Objects.requireNonNull(program.getUniform("ModelViewMat"), "ModelViewMat uniform");
                        Objects.requireNonNull(program.getUniform("ProjMat"), "ProjMat uniform");
                        binding = new Binding(program, size, phase);
                        LOGGER.info("Guide pixel shimmer GPU shader ready: {}", ID);
                    } catch (RuntimeException failure) {
                        binding = null;
                        LOGGER.warn("Guide shader validation failed; using static purple background until reload", failure);
                    }
                });
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Guide shader load failed; using static purple background until reload", failure);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopped = true;
            binding = null;
            // GameRenderer owns registered programs and closes them on reload/shutdown.
            // Closing here as well would double-delete its managed program/stages.
        });
    }

    /** Bounded periodic phase; reduced motion is completely time-independent. */
    static float phase(long timeMs, boolean reducedMotion) {
        return reducedMotion ? 0f : Math.floorMod(timeMs, PERIOD_MS) / 180f;
    }

    /** Called inside DrawContext.draw's boundary flushes, before guide content/items. */
    static boolean render(DrawContext context, int width, int height, boolean reducedMotion) {
        Binding active = binding;
        if (active == null || width <= 0 || height <= 0) return false;
        try {
            return draw(context, width, height, reducedMotion, active);
        } catch (RuntimeException failure) {
            binding = null; // No retry/log flood every frame; resource reload retries.
            LOGGER.warn("Guide shader draw failed; using static purple background until reload", failure);
            return false;
        }
    }

    private static boolean draw(DrawContext context, int width, int height, boolean reducedMotion, Binding active) {
        RenderSystem.assertOnRenderThread();
        // Preserve state even when a resource pack/driver causes the draw to fail.
        ShaderProgram previousShader = RenderSystem.getShader();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            active.size.set((float) width, (float) height);
            active.phase.set(phase(Util.getMeasuringTimeMs(), reducedMotion));
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend(); // Opaque output; no blend-function changes.
            RenderSystem.disableCull();
            RenderSystem.setShader(() -> active.program);
            var matrix = context.getMatrices().peek().getPositionMatrix();
            var quad = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
            quad.vertex(matrix, 0, 0, 0);
            quad.vertex(matrix, 0, height, 0);
            quad.vertex(matrix, width, height, 0);
            quad.vertex(matrix, width, 0, 0);
            BufferRenderer.drawWithGlobalProgram(quad.end()); // Vanilla upload owns BuiltBuffer close.
            int error=GL11.glGetError();
            if (error!=GL11.GL_NO_ERROR) throw new IllegalStateException("Guide draw GL error 0x"+Integer.toHexString(error));
            return true;
        } finally {
            RenderSystem.setShader(() -> previousShader);
            GlStateManager._glUseProgram(previousProgram);
            RenderSystem.depthMask(depthMask);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        }
    }
}
