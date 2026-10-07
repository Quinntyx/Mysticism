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
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

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
    private static long enteredAt, transitionAt;
    private static boolean targetActive, meshDepthSeen;
    private static float transitionFrom;
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
            meshDepthSeen=false;
            if (sessionWorld == null || !targetActive && effectStrength()<=.001f || failed) return;
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
                int medium=SpiritMediumTexture.prepare();
                KUWAHARA_SHADER.setUniformValue("HasOccupancy",medium==0?0:1);
                if (medium!=0) KUWAHARA_SHADER.setSamplerUniform("OccupancySampler",medium);
                var offset=SpiritMediumTexture.offset(camera);
                KUWAHARA_SHADER.setUniformValue("OccupancyCameraOffset",(float)offset.x,(float)offset.y,(float)offset.z);
                KUWAHARA_SHADER.setUniformValue("NearBubble",(float)SpiritRenderSettings.NEAR_BUBBLE);
                KUWAHARA_SHADER.setUniformValue("EffectStrength",effectStrength());
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
            if(!failed && !targetActive && effectStrength()<=.001f && sessionWorld!=null
                    && sessionWorld.getRegistryKey().getValue().equals(SPIRIT)) {
                status="waiting for navigation/source mesh";
                try {SpiritEntryScene.hold(MinecraftClient.getInstance());checkGl("holding entry scene");}
                catch(RuntimeException error){SpiritEntryScene.clear();fail("holding entry scene",error);}
            }
            if (!failed && !ClientSpiritCache.active() && effectStrength()<=.001f) {
                try {SpiritEntryScene.capture(MinecraftClient.getInstance());checkGl("capturing source scene");}
                catch(RuntimeException error) {SpiritEntryScene.clear();fail("source-scene snapshot",error);}
            }
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
                // Keep the unprocessed FINAL world color separate from postpass target clears.
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,main.fbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,sceneDepth.fbo);
                GL30.glBlitFramebuffer(0,0,main.textureWidth,main.textureHeight,0,0,sceneDepth.textureWidth,sceneDepth.textureHeight,
                        GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
                main.beginWrite(false);
                KUWAHARA_SHADER.setSamplerUniform("OriginalSampler",SpiritEntryScene.original(client,sceneDepth.getColorAttachment()));
                KUWAHARA_SHADER.setSamplerUniform("DepthSampler", sceneDepth.getDepthAttachment());
                KUWAHARA_SHADER.render(context.tickCounter().getTickDelta(false));
                checkGl("executing postprocess");
                status = "volumetric " + quality.name().toLowerCase(java.util.Locale.ROOT)
                        + (meshDepthSeen ? " + mesh depth" : " (awaiting custom mesh)");
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
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {release();SpiritMediumTexture.clear();SpiritEntryScene.clear();});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{release();SpiritMediumTexture.clear();SpiritEntryScene.clear();});
        SpiritRenderReload.register(Identifier.of("mysticism","spirit_postprocess"),()->{release();SpiritEntryScene.clear();failed=false;});
        HudRenderCallback.EVENT.register((draw, counter) -> {
            if (sessionWorld != null && (effectStrength()>.001f || sessionWorld.getRegistryKey().getValue().equals(SPIRIT))
                    && (!status.startsWith("volumetric") || failed))
                draw.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        Text.literal("Spirit rendering: " + status), 6, 6, 0xffffcc66);
        });
    }

    public static boolean inSpiritWorld() {
        return ClientSpiritCache.active() || effectStrength()>.001f;
    }
    /** Terrain emits MAIN depth before glyphs and the AFTER_TRANSLUCENT snapshot. */
    public static void afterMeshDepth() { meshDepthSeen=true; }
    public static void setMediumSamples(float[] occupancy) { SpiritMediumTexture.publish(occupancy); }
    public static void setMediumSamples(float[] occupancy,Vec3d center) { SpiritMediumTexture.publish(occupancy,center); }
    public static float effectStrength() {
        if (transitionAt==0) return 0;
        double t=Math.max(0,Math.min(1,(System.nanoTime()-transitionAt)*1e-9/1.5));
        float eased=(float)(t*t*(3-2*t));
        return transitionFrom+((targetActive?1:0)-transitionFrom)*eased;
    }

    /** Called only from the requested client mixin. Fallback retains vanilla terrain depth/occlusion. */
    public static void applySpiritFog() {
        if (!inSpiritWorld()) return;
        fogHookSeen = true;
        if(effectStrength()<=.001f)return;
        // During source-scene fade retain source fog; once fully in spirit there is ONLY
        // participating-medium fog. The transition layer is zero at entry, not an abrupt filter.
        if (frameReady && !failed && effectStrength()>=.999f) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE / 4);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE / 2);
        } else if (!frameReady || failed) {
            float s=effectStrength();
            RenderSystem.setShaderFogStart(RenderSystem.getShaderFogStart()*(1-s)+(float)SpiritRenderSettings.HORIZONS.visible()*s);
            RenderSystem.setShaderFogEnd(RenderSystem.getShaderFogEnd()*(1-s)+(float)SpiritRenderSettings.OPAQUE_RADIUS*s);
        }
    }

    public static void applySpiritFogColor() {
        if (inSpiritWorld() && effectStrength()>.001f && (!frameReady || failed)) {
            float s=effectStrength(); float[] c=RenderSystem.getShaderFogColor();
            RenderSystem.setShaderFogColor(c[0]+(.75f-c[0])*s,c[1]+(.68f-c[1])*s,c[2]+(.95f-c[2])*s);
        }
    }

    public static void setQuality(SpiritRenderSettings.Quality value) {
        quality = java.util.Objects.requireNonNull(value);
    }

    public static String status() { return status; }

    private static void updateSession(MinecraftClient client) {
        ClientSpiritCache.syncObserver(client);
        ClientWorld next=client.player==null?null:client.world;
        if (next!=sessionWorld) {
            SpiritMediumTexture.retainFor(next);
            float outgoing=next!=null && targetActive?effectStrength():0;
            long previousEntry=enteredAt;
            release(); sessionWorld=next; transitionAt=System.nanoTime();
            enteredAt=outgoing>0?previousEntry:transitionAt;
            targetActive=false; transitionFrom=outgoing; failed=false; status="starting";
            SpiritItemProjectionRenderer.resetSession(); SpiritSemanticEntityRenderer.clear();
        }
        boolean active=next!=null && ClientSpiritCache.active()
                && io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient.frame().isPresent();
        if (active!=targetActive) {
            transitionFrom=effectStrength(); transitionAt=System.nanoTime(); targetActive=active;
            if (active) enteredAt=transitionAt;
        }
        if (!ClientSpiritCache.active() && effectStrength()<=.001f && sceneDepth!=null) release();
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
        SpiritMediumTexture.resetGpu();
        SpiritRenderLayers.clear();
        if (sceneDepth != null) {
            sceneDepth.delete();
            sceneDepth = null;
        }
        KUWAHARA_SHADER.release(); // remains managed: Satin recreates on re-entry/resource reload
    }
}
