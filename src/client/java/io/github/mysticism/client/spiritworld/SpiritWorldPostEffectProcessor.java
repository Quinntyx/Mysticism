package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectPipeline;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.util.Pool;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class SpiritWorldPostEffectProcessor {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("MysticismClient-SpiritWorldPostEffectProcessor");

    private static final RegistryKey<World> SPIRIT_DIM =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("mysticism", "spirit"));

    // Chain id and resources
    private static final Identifier POST_ID   = Identifier.of("mysticism", "kuwahara");
    private static final Identifier POST_JSON = Identifier.of("mysticism", "shaders/post/kuwahara.json");
    private static final Identifier VSH_ID    = Identifier.of("mysticism", "shaders/program/kuwahara.vsh");
    private static final Identifier FSH_ID    = Identifier.of("mysticism", "shaders/program/kuwahara.fsh");

    private static final int RETRY_COOLDOWN_TICKS = 5;
    private static final int ENTER_WARMUP_TICKS    = 1;
    private static final int POSTLOAD_SKIP_TICKS   = 1;   // <— NEW: skip first render tick after load

    private PostEffectProcessor chain;
    private final Pool pool = new Pool(3);

    private int lastW = -1, lastH = -1;

    private int retryCooldown = 0;
    private int ticksInSpirit = 0;
    private int justLoadedSkipTicks = 0;                  // <— NEW
    private boolean resourcesVerified = false;

    /** Call once per frame at WorldRenderEvents.END */
    public void onWorldRenderEnd() {
        final MinecraftClient mc = MinecraftClient.getInstance();
        final ClientWorld world = mc.world;
        if (world == null) {
            resetOutsideSpirit();
            return;
        }

        final boolean inSpirit = world.getRegistryKey().equals(SPIRIT_DIM);
        if (!inSpirit) {
            resetOutsideSpirit();
            return;
        }

        ticksInSpirit++;

        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }

        // Verify req’d resources once per lifecycle
        if (!resourcesVerified) {
            final ResourceManager rm = mc.getResourceManager();
            final boolean hasPost = rm.getResource(POST_JSON).isPresent();
            final boolean hasVsh  = rm.getResource(VSH_ID).isPresent();
            final boolean hasFsh  = rm.getResource(FSH_ID).isPresent();
            if (!hasPost || !hasVsh || !hasFsh) {
                LOGGER.error("[Mysticism] Missing required post assets; skip (post={}, vsh={}, fsh={})",
                        hasPost, hasVsh, hasFsh);
                retryCooldown = RETRY_COOLDOWN_TICKS;
                return;
            }
            resourcesVerified = true;
        }

        if (ticksInSpirit <= ENTER_WARMUP_TICKS) {
            return;
        }

        // Build/rebuild on size change
        final int w = mc.getWindow().getFramebufferWidth();
        final int h = mc.getWindow().getFramebufferHeight();
        final boolean sizeChanged = (w != lastW) || (h != lastH);

        if (chain == null || sizeChanged) {
            close();
            try {
                // IMPORTANT: use ALL targets so chains that rely on translucent/particles/etc. work
                chain = mc.getShaderLoader().loadPostEffect(POST_ID, DefaultFramebufferSet.MAIN_ONLY);
                lastW = w;
                lastH = h;
                justLoadedSkipTicks = POSTLOAD_SKIP_TICKS;     // <— NEW
                LOGGER.info("Loaded post chain {} ({}x{}) on {}", POST_ID, w, h, Thread.currentThread().getName());
            } catch (Throwable t) {
                retryCooldown = RETRY_COOLDOWN_TICKS;
                LOGGER.error("Failed to load post chain {} on {}. Will retry in {} ticks.",
                        POST_ID, Thread.currentThread().getName(), RETRY_COOLDOWN_TICKS, t);
                return;
            }
        }

        // Give internal targets/samplers one tick to settle after load
        if (justLoadedSkipTicks > 0) {
            justLoadedSkipTicks--;
            return;
        }

        // Run the chain
        try {
            if (chain != null) {
                chain.render(mc.getFramebuffer(), pool);
            }
        } catch (Throwable t) {
            LOGGER.error("Render pass threw; cleaning up", t);
            close();
            retryCooldown = RETRY_COOLDOWN_TICKS;
        }
    }

    /** Call from ClientLifecycleEvents.CLIENT_STOPPING and on dimension leave */
    public void close() {
        if (chain != null) {
            try {
                chain.close();
            } catch (Throwable t) {
                LOGGER.error("close() failed", t);
            }
            chain = null;
        }
    }

    /** Call on resource reload (F3+T) */
    public void onResourcesReloaded() {
        LOGGER.info("resource reload -> dropping post chain");
        close();
        resourcesVerified = false;

        // Validate & summarize post JSON OFF the render loop (for visibility)
        final MinecraftClient mc = MinecraftClient.getInstance();
        final ResourceManager rm = mc.getResourceManager();
        rm.getResource(POST_JSON).ifPresent(res -> {
            try (var reader = res.getReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                var parsed = PostEffectPipeline.CODEC.parse(JsonOps.INSTANCE, root);
                parsed.resultOrPartial(msg -> LOGGER.error("[Mysticism] POST JSON decode error: {}", msg))
                        .ifPresent(p -> LOGGER.info("[Mysticism] POST JSON decoded OK"));

                // lightweight human-readable summary of targets & passes
                if (root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    List<String> targets = new ArrayList<>();
                    if (obj.has("targets") && obj.get("targets").isJsonArray()) {
                        for (JsonElement e : obj.getAsJsonArray("targets")) {
                            targets.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
                        }
                    }
                    List<String> passes = new ArrayList<>();
                    if (obj.has("passes") && obj.get("passes").isJsonArray()) {
                        JsonArray arr = obj.getAsJsonArray("passes");
                        for (JsonElement e : arr) {
                            if (e.isJsonObject()) {
                                JsonObject po = e.getAsJsonObject();
                                String name = po.has("name") ? po.get("name").getAsString() : "<unnamed>";
                                String in   = po.has("intarget") ? po.get("intarget").getAsString() : "<main>";
                                String out  = po.has("outtarget") ? po.get("outtarget").getAsString() : "<main>";
                                passes.add(name + " (" + in + " -> " + out + ")");
                            }
                        }
                    }
                    LOGGER.info("[Mysticism] Post JSON summary: targets={}, passes={}", targets, passes);
                }
            } catch (Exception e) {
                LOGGER.error("[Mysticism] Exception while decoding/summarizing post JSON", e);
            }
        });
    }

    private void resetOutsideSpirit() {
        if (ticksInSpirit != 0) {
            close();
        }
        ticksInSpirit = 0;
        retryCooldown = 0;
        justLoadedSkipTicks = 0;
        lastW = -1;
        lastH = -1;
    }
}
