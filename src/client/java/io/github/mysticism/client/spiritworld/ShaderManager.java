package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;

/** One scene-depth snapshot, one volumetric integration, then painterly + saturation. */
public final class ShaderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism/SpiritRendering");
    private static final Identifier SPIRIT = Identifier.of("mysticism", "spirit");
    public static final ManagedShaderEffect KUWAHARA_SHADER = ShaderEffectManager.getInstance().manage(
            Identifier.of("mysticism", "shaders/post/kuwahara.json"), effect -> {
                failed = false;
                LOGGER.info("Spirit postprocess initialized/reloaded/resized; depth will be rebound this frame");
            });
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static SimpleFramebuffer sceneDepth;
    private static ClientWorld sessionWorld;
    private static long enteredAt;
    private static boolean initialized, failed, frameReady, depthReady, fogHookSeen;
    private static boolean missingHookReported;
    private static SpiritRenderSettings.Quality quality = initialQuality();
    private static String status = "starting";

    private ShaderManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        WorldRenderEvents.START.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            updateSession(client);
            frameReady = false;
            depthReady = false;
            if (sessionWorld == null || failed) return;
            try {
                Framebuffer main = client.getFramebuffer();
                if (main.textureWidth <= 0 || main.textureHeight <= 0) return;
                if (!main.useDepthAttachment || main.getDepthAttachment() < 0)
                    throw new IllegalStateException("Main framebuffer has no readable depth attachment");
                if (KUWAHARA_SHADER.getShaderEffect() == null)
                    throw new IllegalStateException("Satin could not initialize spirit shader (see preceding Satin diagnostics)");
                ensureDepth(main);
                // These are the actual world matrices, including projection effects; not postpass ProjMat.
                inverseViewProjection.set(context.projectionMatrix()).mul(context.positionMatrix());
                if (!Float.isFinite(inverseViewProjection.determinant())
                        || Math.abs(inverseViewProjection.determinant()) < 1.0e-12f)
                    throw new IllegalStateException("Singular/nonfinite world view-projection matrix");
                inverseViewProjection.invert();
                var camera = context.camera().getPos();
                KUWAHARA_SHADER.setUniformValue("InverseViewProjection", inverseViewProjection);
                KUWAHARA_SHADER.setUniformValue("CameraModulo", SpiritRenderSettings.wrap(camera.x),
                        SpiritRenderSettings.wrap(camera.y), SpiritRenderSettings.wrap(camera.z));
                KUWAHARA_SHADER.setUniformValue("OpaqueRadius", (float) SpiritRenderSettings.OPAQUE_RADIUS);
                KUWAHARA_SHADER.setUniformValue("MinExtinction", (float) SpiritRenderSettings.MIN_EXTINCTION);
                KUWAHARA_SHADER.setUniformValue("FogSteps", quality.fogSteps);
                KUWAHARA_SHADER.setUniformValue("KernelRadius", quality.kernelRadius);
                KUWAHARA_SHADER.setUniformValue("Saturation", SpiritRenderSettings.saturation(
                        (System.nanoTime() - enteredAt) * 1.0e-9));
                frameReady = true;
            } catch (RuntimeException exception) {
                fail("preparing matrices/framebuffer", exception);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (!frameReady || sessionWorld == null) return;
            MinecraftClient client = MinecraftClient.getInstance();
            try {
                // Before Fabulous's transparency chain clears main depth. Includes opaque terrain and
                // our already-flushed glyphs; main depth is NOT the later hand-only depth texture.
                sceneDepth.copyDepthFrom(client.getFramebuffer());
                checkGl("copying scene depth");
                depthReady = true;
            } catch (RuntimeException exception) {
                fail("copying scene depth", exception);
            } finally {
                client.getFramebuffer().beginWrite(true);
            }
        });
        WorldRenderEvents.END.register(context -> {
            if (!frameReady || !depthReady || sessionWorld == null) return;
            frameReady = false;
            depthReady = false; // consume the world frame once; never process menus/stale frames
            if (!fogHookSeen) {
                status = "fallback: parent must register SpiritBackgroundRendererMixin";
                if (!missingHookReported) {
                    missingHookReported = true;
                    LOGGER.error("{}; refusing double-fog postprocess", status);
                }
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            Framebuffer main = client.getFramebuffer();
            try {
                // Fabric END is WorldRenderer.RETURN, BEFORE GameRenderer clears depth for the hand.
                // Satin's own effect event runs AFTER renderWorld/hand; unsuitable for scene depth.
                KUWAHARA_SHADER.setSamplerUniform("DepthSampler", sceneDepth.getDepthAttachment());
                KUWAHARA_SHADER.render(context.tickCounter().getTickDelta(false));
                checkGl("executing postprocess");
                status = "volumetric " + quality.name().toLowerCase(java.util.Locale.ROOT);
            } catch (RuntimeException exception) {
                fail("rendering Satin chain", exception);
            } finally {
                try {
                    // Vanilla PostEffectPass clears its outtarget, including main depth. Preserve scene
                    // depth for other world callbacks; GameRenderer subsequently clears it for hands.
                    main.copyDepthFrom(sceneDepth);
                    checkGl("restoring scene depth");
                } catch (RuntimeException exception) {
                    fail("restoring scene depth", exception);
                }
                main.beginWrite(true);
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(ShaderManager::updateSession);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> release());
        HudRenderCallback.EVENT.register((draw, counter) -> {
            if (sessionWorld != null && (!status.startsWith("volumetric") || failed))
                draw.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        Text.literal("Spirit rendering: " + status), 6, 6, 0xffffcc66);
        });
    }

    public static boolean inSpiritWorld() {
        var world = MinecraftClient.getInstance().world;
        return world != null && world.getRegistryKey().getValue().equals(SPIRIT);
    }

    /** Called only from the requested client mixin. Fallback retains vanilla terrain depth/occlusion. */
    public static void applySpiritFog() {
        if (!inSpiritWorld()) return;
        fogHookSeen = true;
        if (frameReady && !failed) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE / 4);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE / 2);
        } else {
            RenderSystem.setShaderFogStart((float) SpiritRenderSettings.HORIZONS.visible());
            RenderSystem.setShaderFogEnd((float) SpiritRenderSettings.OPAQUE_RADIUS);
        }
    }

    public static void applySpiritFogColor() {
        if (inSpiritWorld()) RenderSystem.setShaderFogColor(0.75f, 0.68f, 0.95f);
    }

    public static void setQuality(SpiritRenderSettings.Quality value) {
        quality = java.util.Objects.requireNonNull(value);
    }

    public static String status() { return status; }

    private static void updateSession(MinecraftClient client) {
        ClientWorld next = inSpiritWorld() ? client.world : null;
        if (next == sessionWorld) return;
        release();
        sessionWorld = next;
        enteredAt = System.nanoTime();
        failed = false;
        status = "starting";
        SpiritItemProjectionRenderer.resetSession();
    }

    private static void ensureDepth(Framebuffer main) {
        if (sceneDepth == null) {
            // One full-size RGBA8+depth FBO; the three Satin targets also allocate color+depth.
            // No per-frame allocation. Depth snapshot is rebound after every reload/resize.
            sceneDepth = new SimpleFramebuffer(main.textureWidth, main.textureHeight, true, MinecraftClient.IS_SYSTEM_MAC);
        } else if (sceneDepth.textureWidth != main.textureWidth || sceneDepth.textureHeight != main.textureHeight) {
            sceneDepth.resize(main.textureWidth, main.textureHeight, MinecraftClient.IS_SYSTEM_MAC);
        }
        sceneDepth.beginWrite(false);
        try {
            sceneDepth.checkFramebufferStatus();
            checkGl("allocating depth snapshot");
        } finally {
            main.beginWrite(true);
        }
    }

    private static SpiritRenderSettings.Quality initialQuality() {
        String configured = System.getProperty("mysticism.spirit.quality", "medium");
        try {
            return SpiritRenderSettings.Quality.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Unknown mysticism.spirit.quality '{}'; using medium (low/medium/high supported)", configured);
            return SpiritRenderSettings.Quality.MEDIUM;
        }
    }

    private static void checkGl(String phase) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR)
            throw new IllegalStateException(phase + ": OpenGL error 0x" + Integer.toHexString(error));
    }

    private static void fail(String phase, RuntimeException exception) {
        if (!failed) LOGGER.error("Spirit rendering failed while {}; using vanilla linear fog until re-entry/resource reload", phase, exception);
        failed = true;
        frameReady = false;
        status = "fallback: " + phase + " (see log; F3+T to retry)";
    }

    private static void release() {
        frameReady = false;
        depthReady = false;
        if (sceneDepth != null) {
            sceneDepth.delete();
            sceneDepth = null;
        }
        KUWAHARA_SHADER.release(); // remains managed: Satin recreates on re-entry/resource reload
    }
}
