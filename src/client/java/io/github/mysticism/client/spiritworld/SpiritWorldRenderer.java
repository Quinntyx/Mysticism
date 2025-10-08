package io.github.mysticism.client.spiritworld;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Projection384f;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public final class SpiritWorldRenderer {
    private SpiritWorldRenderer() {}

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(SpiritWorldRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        var mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        // Only render inside the Spirit world
        if (!mc.world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit"))) return;

        final MatrixStack matrices = ctx.matrixStack();
        final VertexConsumerProvider consumers = ctx.consumers();
        final var camera = ctx.camera();

        final Basis384f basis = ClientSpiritCache.playerLatentBasis;
        final Vec384f you = ClientSpiritCache.playerLatentPos;

        final ItemRenderer itemRenderer = mc.getItemRenderer();

        // Iterate the server-selected visible ids
        for (String id : ClientSpiritCache.VISIBLE) {
            final Vec384f obj = ClientSpiritCache.VEC.get(id);
            if (obj == null) continue;

            // --- THE DEFINITIVE FIX: Use defensive cloning ---
            // This prevents the mutable math in your other classes from corrupting
            // the cached data and causing all items to render at the same spot.
            final Vec3d worldPos = Projection384f.projectToWorld(
                    obj.clone(), you.clone(), basis.clone(),
                    camera.getPos(), 30.0f);

            // Frustum culling
            if (ctx.frustum() == null || !ctx.frustum().isVisible(Box.of(worldPos, 1, 1, 1))) {
                continue;
            }

            matrices.push();

            // Translate matrix to the object's world position, relative to the camera
            Vec3d cam = camera.getPos();
            matrices.translate(worldPos.x - cam.x, worldPos.y - cam.y, worldPos.z - cam.z);

            // Calculate scale based on distance
            double sqDist = obj.squareDistance(you);
            float scale = (float) (1.0 / Math.sqrt(sqDist));
            if (!Float.isFinite(scale)) { // Sanity check for division by zero or negative sqrt
                scale = 1.0f;
            }
            matrices.scale(scale, scale, scale);

            ItemStack stack = resolveIcon(id);
            ModelTransformationMode transformMode;

            // Apply billboarding for non-blocks
            if (!(stack.getItem() instanceof BlockItem)) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                transformMode = ModelTransformationMode.NONE;
            } else {
                transformMode = ModelTransformationMode.GROUND;
            }

            // Render the item
            final int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            itemRenderer.renderItem(
                    stack,
                    transformMode,
                    light,
                    OverlayTexture.DEFAULT_UV,
                    matrices,
                    consumers,
                    mc.world,
                    id.hashCode() // render seed
            );

            matrices.pop();
        }
    }

    /**
     * Best-effort: if the id is a valid registry id for an item, use it; otherwise use a default glyph.
     */
    private static ItemStack resolveIcon(String id) {
        try {
            Identifier ident = Identifier.of(id);
            Item item = Registries.ITEM.get(ident);
            if (item == Items.AIR) {
                return new ItemStack(Items.AMETHYST_SHARD);
            }
            return new ItemStack(item);
        } catch (Exception ignored) {
            return new ItemStack(Items.AMETHYST_SHARD);
        }
    }
}