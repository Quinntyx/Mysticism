package io.github.mysticism.client.spiritworld;

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
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Objects;

import static io.github.mysticism.client.MysticismClient.LOGGER;

/**
 * Draws each visible embedding as a billboarded item at:
 * playerWorldPos + project( (obj384 - player384) onto {i,j,k} ).
 */
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
        assert matrices != null;
        final VertexConsumerProvider consumers = ctx.consumers();
        final var camera = ctx.camera();

        final var basis   = ClientSpiritCache.playerLatentBasis;
        final Vec384f you = ClientSpiritCache.playerLatentPos;

        final ItemRenderer itemRenderer = mc.getItemRenderer();

        // Iterate the server-selected visible ids
        for (String id : ClientSpiritCache.VISIBLE) {
            final Vec384f obj = ClientSpiritCache.VEC.get(id);
            if (obj == null) continue;

            // 1–3: Δ384 → project onto basis → add player world pos
            final Vec3d worldPos = Projection384f.projectToWorld(
                    obj, you, basis, camera.getPos(), 20.0f);

            if (ctx.frustum() == null) {
                continue;
            }

            if (!Objects.requireNonNull(ctx.frustum()).isVisible(new Box(worldPos.x - 0.5, worldPos.y - 0.5, worldPos.z - 0.5,
                    worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5))) {
                continue;
            }

            // Draw a billboarded item at worldPos
            matrices.push();

            // Move to world position relative to camera
            Vec3d cam = camera.getPos();
            matrices.translate(worldPos.x - cam.x, worldPos.y - cam.y, worldPos.z - cam.z);

            ItemStack stack = resolveIcon(id);

            if (!(stack.getItem() instanceof BlockItem)) {
                // Face the camera (billboard)
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
            }

//            var scale = (float) (Math.sqrt(obj.squareDistance(you)));
            var scale = 1f;
//            LOGGER.info("Square Distance for item {}: {}", id, obj.squareDistance(you));
//            LOGGER.info("Scale: {}", scale);
//             Scale down a bit
//            LOGGER.info("Vec for {}: {}", id, Arrays.copyOfRange(obj.data(), 0, 5));
            matrices.scale(scale, scale, scale);

            // Resolve an icon (try id as an Item identifier; otherwise fallback)


            // Fullbright so void stays readable; change if you want world lighting
            final int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

            itemRenderer.renderItem(
                    stack,
                    ItemDisplayContext.GROUND,
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
            Item item = null;
            try {
                item = Registries.ITEM.get(ident);
            } catch (Exception ignored) {
                item = Items.AMETHYST_SHARD;
            }
            return new ItemStack(item);
        } catch (Exception ignored) {
            return new ItemStack(Items.AMETHYST_SHARD);
        }
    }
}
