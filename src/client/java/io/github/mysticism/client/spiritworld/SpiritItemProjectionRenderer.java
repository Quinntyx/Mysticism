package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Projection384f;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, frozen-session glyph placement; opaque/cutout depth goes into the main scene. */
public final class SpiritItemProjectionRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism/SpiritGlyphs");
    private record Glyph(Vec3d position, ItemStack icon, float scale) {}
    private static final Map<String, Glyph> glyphs = new LinkedHashMap<>();
    private static final RenderLayer GLYPH_LAYER = RenderLayer.getEntityCutoutNoCull(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    private static ClientWorld world;
    private static Basis384f frozenBasis;
    private static Vec384f frozenOrigin;
    private static Vec3d realmAnchor;
    private static BufferAllocator allocator;
    private static VertexConsumerProvider.Immediate immediate;
    private static boolean initialized, warned;

    private SpiritItemProjectionRenderer() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        WorldRenderEvents.AFTER_ENTITIES.register(SpiritItemProjectionRenderer::render);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (allocator != null) allocator.close();
            allocator = null;
            immediate = null;
            resetSession();
        });
    }

    public static void resetSession() {
        glyphs.clear();
        world = null;
        frozenBasis = null;
        frozenOrigin = null;
        realmAnchor = null;
        warned = false;
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!ShaderManager.inSpiritWorld() || client.player == null) {
            resetSession();
            return;
        }
        var matrices = context.matrixStack();
        if (matrices == null || context.frustum() == null) return;
        if (world != client.world) {
            resetSession();
            world = client.world;
            // The current payload carries vectors, not authoritative realm placements. Freeze a local
            // session frame rather than re-anchoring to the camera each render (the old orbit/ring bug).
            frozenBasis = ClientSpiritCache.playerLatentBasis.clone();
            frozenOrigin = ClientSpiritCache.playerLatentPos.clone();
            realmAnchor = client.player.getPos();
        }
        if (ClientSpiritCache.VISIBLE.size() > SpiritRenderSettings.MAX_GLYPHS) {
            warn("Server glyph set exceeds 128; refusing unbounded render enumeration", null);
            return;
        }
        if (immediate == null) {
            allocator = new BufferAllocator(262144);
            immediate = VertexConsumerProvider.immediate(allocator);
        }
        var ids = new ArrayList<>(ClientSpiritCache.VISIBLE);
        ids.sort(String::compareTo);
        glyphs.keySet().removeIf(id -> !ClientSpiritCache.VISIBLE.contains(id));
        Vec3d camera = context.camera().getPos();
        // Deliberately route atlas icons to a LEQUAL, depth-writing main-target layer. Vanilla's
        // item-translucent layer targets a separate Fabulous FBO whose depth isn't in scene depth.
        VertexConsumerProvider direct = ignoredLayer -> immediate.getBuffer(GLYPH_LAYER);
        client.getFramebuffer().beginWrite(false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        for (String id : ids) {
            Vec384f vector = ClientSpiritCache.VEC.get(id);
            if (vector == null) continue;
            Glyph glyph;
            try {
                EmbeddingSpace.requireCurrent(vector);
                glyph = glyphs.get(id);
                if (glyph == null) {
                    Vec3d position = Projection384f.projectToWorld(vector, frozenOrigin, frozenBasis, realmAnchor, 30.0f);
                    if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
                        throw new IllegalArgumentException("Nonfinite projected position");
                    float scale = (float) Math.max(0.25, Math.min(1.5,
                            1.0 / Math.sqrt(Math.max(0.01, vector.squareDistance(frozenOrigin)))));
                    glyph = new Glyph(position, resolveIcon(id), scale);
                    glyphs.put(id, glyph);
                }
            } catch (IllegalArgumentException exception) {
                warn("Rejected incompatible/nonfinite glyph " + id, exception);
                continue;
            }
            if (glyph.position.squaredDistanceTo(camera) >= SpiritRenderSettings.OPAQUE_RADIUS * SpiritRenderSettings.OPAQUE_RADIUS
                    || !context.frustum().isVisible(Box.of(glyph.position, 3, 3, 3))) continue;
            matrices.push();
            try {
                matrices.translate(glyph.position.x - camera.x, glyph.position.y - camera.y, glyph.position.z - camera.z);
                matrices.scale(glyph.scale, glyph.scale, glyph.scale);
                boolean block = glyph.icon.getItem() instanceof BlockItem;
                if (!block) {
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-context.camera().getYaw()));
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(context.camera().getPitch()));
                }
                client.getItemRenderer().renderItem(glyph.icon,
                        block ? ModelTransformationMode.GROUND : ModelTransformationMode.NONE,
                        LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV,
                        matrices, direct, client.world, id.hashCode());
                immediate.draw(); // flush NOW, before block entities/translucency and Satin scene-depth snapshot
            } finally {
                matrices.pop();
            }
        }
    }

    private static ItemStack resolveIcon(String id) {
        Identifier identifier = Identifier.tryParse(id);
        Item item = identifier == null ? Items.AMETHYST_SHARD : Registries.ITEM.get(identifier);
        ItemStack stack = new ItemStack(item == Items.AIR ? Items.AMETHYST_SHARD : item);
        // Built-in renderers may use non-atlas textures/special targets. Keep a predictable depth glyph.
        if (MinecraftClient.getInstance().getItemRenderer().getModel(stack, world, null, 0).isBuiltin())
            return new ItemStack(Items.AMETHYST_SHARD);
        return stack;
    }

    private static void warn(String message, Throwable exception) {
        if (warned) return;
        warned = true;
        if (exception == null) LOGGER.warn(message); else LOGGER.warn(message, exception);
    }
}
