package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.FogShape;
import net.minecraft.client.gl.PostEffectProcessor;
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

/** One scene-depth snapshot: painterly terrain, one volumetric integration, saturation, transition. */
public final class ShaderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism/SpiritRendering");
    private static final Identifier SPIRIT = Identifier.of("mysticism", "spirit");
    private static final java.util.List<String> TARGETS = java.util.List.of("fogged", "painted", "saturated");
    private static final SpiritPostprocessFrame frame = new SpiritPostprocessFrame();
    public static final ManagedShaderEffect KUWAHARA_SHADER = ShaderEffectManager.getInstance().manage(
            Identifier.of("mysticism", "shaders/post/kuwahara.json"), effect -> {
                frame.invalidate();
                failed = false;
                LOGGER.info("Spirit postprocess initialized/reloaded/resized; depth will be rebound this frame");
            });
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static SimpleFramebuffer sceneDepth;
    private static PostEffectProcessor processor;
    private static Framebuffer processorMain;
    private static ClientWorld sessionWorld;
    private static long enteredAt, transitionAt;
    private static boolean targetActive, meshDepthSeen;
    private static float transitionFrom;
    private static boolean initialized, failed, fogHookSeen;
    private static boolean missingHookReported;
    private static String failureReason = "shader failed";
    private static final SpiritVisualControls controls = initialControls();
    private static SpiritVisualControls.Passes framePasses = controls.settings().passes(0);
    private static SpiritSourceFog sourceFog;
    private static SpiritRenderSettings.FogRange fogRange = SpiritRenderSettings.DEFAULT_FOG_RANGE;

    private ShaderManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        SpiritVisualCommands.init();
        WorldRenderEvents.START.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            updateSession(client);
            fogRange = SpiritRenderSettings.fogRange(client.options.getViewDistance().getValue());
            frame.invalidate();
            fogHookSeen = false; // each prepared world frame must actually bypass analytic terrain fog
            sourceFog = null;
            meshDepthSeen=false;
            boolean sceneActive = sessionWorld != null && (targetActive || effectStrength() > .001f);
            boolean waiting = sessionWorld != null && !sceneActive
                    && sessionWorld.getRegistryKey().getValue().equals(SPIRIT);
            framePasses = controls.beginFrame(sceneActive, waiting, (System.nanoTime() - enteredAt) * 1.0e-9);
            if (!framePasses.anyEnabled() || !sceneActive) return;
            if (failed) {
                controls.fallback(failureReason + "; " + (framePasses.fog() && fogHookSeen ? "linear fog fallback selected" : "vanilla fog unchanged")
                        + " (see log; /spiritvisual retry or F3+T)");
                return;
            }
            Framebuffer main = client.getFramebuffer();
            if (main.textureWidth <= 0 || main.textureHeight <= 0) return;
            try {
                if (!main.useDepthAttachment || main.getDepthAttachment() < 0)
                    throw new IllegalStateException("Main framebuffer has no readable depth attachment");
                ensureChain(main);
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
                KUWAHARA_SHADER.setUniformValue("VisibleRadius", (float) fogRange.visibleRadius());
                KUWAHARA_SHADER.setUniformValue("EffectStrength",effectStrength());
                KUWAHARA_SHADER.setUniformValue("OpaqueRadius", (float) fogRange.opaqueRadius());
                KUWAHARA_SHADER.setUniformValue("HorizonOpticalDepth", (float) SpiritRenderSettings.HORIZON_OPTICAL_DEPTH);
                KUWAHARA_SHADER.setUniformValue("SampleStride", SpiritRenderSettings.painterlyStride(main.textureHeight));
                KUWAHARA_SHADER.setUniformValue("FogEnabled", framePasses.fog() ? 1 : 0);
                KUWAHARA_SHADER.setUniformValue("KuwaharaEnabled", framePasses.kuwahara() ? 1 : 0);
                KUWAHARA_SHADER.setUniformValue("FogSteps", framePasses.fogSteps());
                KUWAHARA_SHADER.setUniformValue("KernelRadius", framePasses.kernelRadius());
                KUWAHARA_SHADER.setUniformValue("Saturation", framePasses.saturationValue());
                frame.prepare(surface(main));
            } catch (RuntimeException exception) {
                fail("preparing matrices/framebuffer", exception);
            } finally {
                // Satin target initialization/resize can bind a secondary FBO before failing.
                main.beginWrite(true);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (!frame.ready() || sessionWorld == null) return;
            MinecraftClient client = MinecraftClient.getInstance();
            try {
                if (!frame.matches(surface(client.getFramebuffer()))) { frame.invalidate(); return; }
                // Before Fabulous's transparency chain clears main depth. Includes opaque terrain and
                // our already-flushed glyphs; main depth is NOT the later hand-only depth texture.
                sceneDepth.copyDepthFrom(client.getFramebuffer());
                checkGl("copying scene depth");
                frame.captureDepth(surface(client.getFramebuffer()));
            } catch (RuntimeException exception) {
                fail("copying scene depth", exception);
            } finally {
                client.getFramebuffer().beginWrite(true);
            }
        });
        WorldRenderEvents.END.register(context -> {
            if (!framePasses.anyEnabled()) return;
            if(!failed && !targetActive && effectStrength()<=.001f && sessionWorld!=null
                    && sessionWorld.getRegistryKey().getValue().equals(SPIRIT)) {
                try {
                    boolean held = SpiritEntryScene.hold(MinecraftClient.getInstance());
                    controls.waiting(held);
                    checkGl("holding entry scene");
                }
                catch(RuntimeException error){SpiritEntryScene.clear();fail("holding entry scene",error);}
            }
            if (!failed && !ClientSpiritCache.active() && effectStrength()<=.001f) {
                try {SpiritEntryScene.capture(MinecraftClient.getInstance());checkGl("capturing source scene");}
                catch(RuntimeException error) {SpiritEntryScene.clear();fail("source-scene snapshot",error);}
            }
            if (!frame.ready() || sessionWorld == null) return;
            if (!frame.depthReady()) {
                // A prepared shader is not evidence of a rendered frame. Stop suppressing native
                // fog on subsequent frames if Fabric never supplied the required world-depth event.
                fail("scene depth snapshot not observed", new IllegalStateException("AFTER_TRANSLUCENT did not supply scene depth"));
                return;
            }
            if (framePasses.fog() && (!fogHookSeen || sourceFog == null)) {
                frame.invalidate();
                controls.fallback("fog hook not observed; postprocess skipped, vanilla fog unchanged");
                if (!missingHookReported) {
                    missingHookReported = true;
                    LOGGER.error("{}; check SpiritBackgroundRendererMixin registration", controls.summary());
                }
                return;
            }
            if (!framePasses.canRender(frame.ready(), frame.depthReady(), failed, fogHookSeen)) return;
            MinecraftClient client = MinecraftClient.getInstance();
            Framebuffer main = client.getFramebuffer();
            if (!frame.consume(surface(main))) return; // resize/reload/replacement: never use stale depth
            try {
                // Fabric END is WorldRenderer.RETURN, BEFORE GameRenderer clears depth for the hand.
                // Satin's own effect event runs AFTER renderWorld/hand; unsuitable for scene depth.
                // Keep the unprocessed FINAL world color separate from postpass target clears.
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,main.fbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,sceneDepth.fbo);
                GL30.glBlitFramebuffer(0,0,main.textureWidth,main.textureHeight,0,0,sceneDepth.textureWidth,sceneDepth.textureHeight,
                        GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
                main.beginWrite(false);
                var original = SpiritEntryScene.original(client,sceneDepth.getColorAttachment());
                KUWAHARA_SHADER.setSamplerUniform("OriginalSampler",original.texture());
                // With fog disabled native fog was not bypassed: do not
                // require a saved fog state or apply it a second time to the original scene.
                var fog = sourceFog == null ? new SpiritSourceFog(0, 1, 0, 0, 0, 0, 0) : sourceFog;
                KUWAHARA_SHADER.setUniformValue("RestoreSourceFog", original.restoreSourceFog() && sourceFog != null ? 1 : 0);
                KUWAHARA_SHADER.setUniformValue("SourceFogStart",fog.start());
                KUWAHARA_SHADER.setUniformValue("SourceFogEnd",fog.end());
                KUWAHARA_SHADER.setUniformValue("SourceFogShape",fog.shape());
                KUWAHARA_SHADER.setUniformValue("SourceFogColor",fog.red(),fog.green(),fog.blue(),fog.alpha());
                KUWAHARA_SHADER.setSamplerUniform("DepthSampler", sceneDepth.getDepthAttachment());
                // BEFORE_ENTITIES has now drawn and published the current terrain. Binding at START
                // paired this frame's depth with last frame's density (and lost first-frame occupancy).
                bindCurrentMedium(context.camera().getPos());
                KUWAHARA_SHADER.render(context.tickCounter().getTickDelta(false));
                checkGl("executing postprocess");
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
            if (!failed) controls.markApplied(framePasses, meshDepthSeen);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ShaderManager::updateSession);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {release();SpiritMediumTexture.clear();SpiritEntryScene.clear();controls.inactive();});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{release();SpiritMediumTexture.clear();SpiritEntryScene.clear();controls.inactive();});
        SpiritRenderReload.register(Identifier.of("mysticism","spirit_postprocess"),ShaderManager::retry);
        HudRenderCallback.EVENT.register((draw, counter) -> {
            if (sessionWorld != null && (effectStrength()>.001f || sessionWorld.getRegistryKey().getValue().equals(SPIRIT))
                    && controls.state() != SpiritVisualControls.State.APPLIED)
                draw.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        Text.literal(controls.hudStatus()), 6, 6, 0xffffcc66);
        });
    }

    public static boolean inSpiritWorld() {
        return ClientSpiritCache.active() || effectStrength()>.001f;
    }
    /** Terrain emits MAIN depth before glyphs and the AFTER_TRANSLUCENT snapshot. */
    public static void afterMeshDepth() { meshDepthSeen=true; }
    public static void setMediumSamples(float[] occupancy) { SpiritMediumTexture.publish(occupancy); }
    public static void setMediumSamples(float[] occupancy,Vec3d center) { SpiritMediumTexture.publish(occupancy,center); }
    public static void clearMediumSamples() { SpiritMediumTexture.clear(); }

    private static void bindCurrentMedium(Vec3d camera) {
        int medium=SpiritMediumTexture.prepare();
        KUWAHARA_SHADER.setUniformValue("HasOccupancy",medium==0?0:1);
        KUWAHARA_SHADER.setSamplerUniform("OccupancySampler", medium == 0 ? sceneDepth.getColorAttachment() : medium); // bind a valid texture even when occupancy is disabled
        var offset=SpiritMediumTexture.offset(camera);
        KUWAHARA_SHADER.setUniformValue("OccupancyCameraOffset",(float)offset.x,(float)offset.y,(float)offset.z);
    }
    public static float effectStrength() {
        if (transitionAt==0) return 0;
        double t=Math.max(0,Math.min(1,(System.nanoTime()-transitionAt)*1e-9/1.5));
        float eased=(float)(t*t*(3-2*t));
        return transitionFrom+((targetActive?1:0)-transitionFrom)*eased;
    }

    /** Called only from the requested client mixin. Fallback retains vanilla terrain depth/occlusion. */
    public static void applySpiritFog(boolean terrainFog) {
        if (!inSpiritWorld()) return;
        if (!terrainFog) return; // Preserve the native sky backdrop.
        fogHookSeen = true;
        switch (framePasses.fogMode(inSpiritWorld(), effectStrength(), frame.ready(), failed)) {
            case VANILLA -> { /* Fog disabled: leave native fog unchanged. */ }
            case VOLUMETRIC -> {
                float[] color = RenderSystem.getShaderFogColor();
                sourceFog = new SpiritSourceFog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                        RenderSystem.getShaderFogShape() == FogShape.SPHERE ? 0 : 1,
                        color[0], color[1], color[2], color[3]);
                RenderSystem.setShaderFogStart(Float.MAX_VALUE / 4);
                RenderSystem.setShaderFogEnd(Float.MAX_VALUE / 2);
            }
            case LINEAR_FALLBACK -> {
                float s = effectStrength();
                RenderSystem.setShaderFogStart(RenderSystem.getShaderFogStart() * (1-s)
                        + (float) fogRange.visibleRadius() * s);
                RenderSystem.setShaderFogEnd(RenderSystem.getShaderFogEnd() * (1-s)
                        + (float) fogRange.opaqueRadius() * s);
            }
        }
    }

    public static void applySpiritFogColor() {
        if (framePasses.fogMode(inSpiritWorld(), effectStrength(), frame.ready(), failed)
                == SpiritVisualControls.FogMode.LINEAR_FALLBACK) {
            float s=effectStrength(); float[] c=RenderSystem.getShaderFogColor();
            RenderSystem.setShaderFogColor(c[0]+(.75f-c[0])*s,c[1]+(.68f-c[1])*s,c[2]+(.95f-c[2])*s);
        }
    }

    public static SpiritVisualControls.Settings settings() { return controls.settings(); }
    public static void setQuality(SpiritRenderSettings.Quality value) {
        configure(settings().withQuality(value));
    }
    public static void setEnabled(boolean value) { configure(settings().withEnabled(value)); }
    public static void setEffect(SpiritVisualControls.Effect effect, boolean value) {
        configure(settings().withEffect(effect, value));
    }
    private static void configure(SpiritVisualControls.Settings value) {
        RenderSystem.assertOnRenderThread();
        boolean wasEnabled = controls.settings().anyEnabled();
        controls.configure(value);
        framePasses = value.passes((System.nanoTime() - enteredAt) * 1.0e-9);
        frame.invalidate();
        if (!value.anyEnabled()) {
            release();
            SpiritEntryScene.clear();
        } else if (!wasEnabled) {
            failed = false; // Explicit off/on permits recovery, not a retry/log loop every frame.
        }
    }
    public static void retry() {
        RenderSystem.assertOnRenderThread();
        release();
        SpiritEntryScene.clear();
        failed = false;
        controls.configure(settings());
    }
    public static String status() { return controls.status(); }

    /** Current world-frame presentation range, shared by mesh culling and fallback fog. */
    public static SpiritRenderSettings.FogRange fogRange() { return fogRange; }

    private static void updateSession(MinecraftClient client) {
        ClientSpiritCache.syncObserver(client);
        ClientWorld next=client.player==null?null:client.world;
        if (next!=sessionWorld) {
            SpiritMediumTexture.retainFor(next);
            float outgoing=next!=null && targetActive?effectStrength():0;
            long previousEntry=enteredAt;
            release(); sessionWorld=next; transitionAt=System.nanoTime();
            enteredAt=outgoing>0?previousEntry:transitionAt;
            targetActive=false; transitionFrom=outgoing; failed=false; controls.inactive();
            SpiritItemProjectionRenderer.resetSession(); SpiritSemanticEntityRenderer.clear();
        }
        boolean active=next!=null && ClientSpiritCache.active()
                && io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient.frame().isPresent();
        if (active!=targetActive) {
            transitionFrom=effectStrength(); transitionAt=System.nanoTime(); targetActive=active;
            if (active) enteredAt=transitionAt;
        }
        if (next == null) controls.inactive();
        if (!ClientSpiritCache.active() && effectStrength()<=.001f && sceneDepth!=null) release();
    }

    private static SpiritPostprocessFrame.Surface surface(Framebuffer main) {
        return new SpiritPostprocessFrame.Surface(main, main.textureWidth, main.textureHeight,
                main.fbo, main.getColorAttachment(), main.getDepthAttachment());
    }

    private static void ensureChain(Framebuffer main) {
        var current = KUWAHARA_SHADER.getShaderEffect();
        // PostEffectProcessor captures its main target in its constructor. A replacement of
        // Minecraft's framebuffer cannot be repaired merely by resizing the secondary targets.
        if (current == processor && processorMain != main) {
            KUWAHARA_SHADER.release();
            current = KUWAHARA_SHADER.getShaderEffect();
        }
        if (current == null)
            throw new IllegalStateException("Satin could not initialize spirit shader (see preceding Satin diagnostics)");
        processor = current;
        processorMain = main;
        // Satin normally receives window-resize callbacks, but the actual render surface is the
        // authority (including startup/re-entry and off-screen render-size changes).
        for (String name : TARGETS) {
            Framebuffer target = current.getSecondaryTarget(name);
            if (target == null) throw new IllegalStateException("Missing postprocess target: " + name);
            if (target.textureWidth != main.textureWidth || target.textureHeight != main.textureHeight) {
                current.setupDimensions(main.textureWidth, main.textureHeight);
                frame.invalidate();
                break;
            }
        }
    }

    private static void ensureDepth(Framebuffer main) {
        try {
            if (sceneDepth == null) {
                sceneDepth = new SimpleFramebuffer(main.textureWidth, main.textureHeight, true, MinecraftClient.IS_SYSTEM_MAC);
            } else if (sceneDepth.textureWidth != main.textureWidth || sceneDepth.textureHeight != main.textureHeight) {
                sceneDepth.resize(main.textureWidth, main.textureHeight, MinecraftClient.IS_SYSTEM_MAC);
            }
            sceneDepth.beginWrite(false);
            sceneDepth.checkFramebufferStatus();
            checkGl("allocating depth snapshot");
        } finally {
            // Constructors/resize bind their own FBO too. Restore main even on allocation failure.
            main.beginWrite(true);
        }
    }

    private static SpiritVisualControls initialControls() {
        var result = new SpiritVisualControls(initialQuality());
        result.configure(result.settings().withEffect(SpiritVisualControls.Effect.KUWAHARA,
                Boolean.parseBoolean(System.getProperty("mysticism.spirit.kuwahara", "true"))));
        return result;
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
        if (!failed) LOGGER.error("Spirit rendering failed while {}; postprocess stopped until explicit retry/re-entry/resource reload", phase, exception);
        failed = true;
        failureReason = phase;
        frame.invalidate();
        controls.fallback(phase + "; " + (framePasses.fog() && fogHookSeen ? "linear fog fallback selected (next fog callback)" : "vanilla fog unchanged")
                + " (see log; /spiritvisual retry or F3+T)");
    }

    private static void release() {
        frame.invalidate();
        fogHookSeen = false;
        sourceFog = null;
        SpiritMediumTexture.resetGpu();
        SpiritRenderLayers.clear();
        if (sceneDepth != null) {
            sceneDepth.delete();
            sceneDepth = null;
        }
        processor = null;
        processorMain = null;
        KUWAHARA_SHADER.release(); // remains managed: Satin recreates on re-entry/resource reload
    }
}
