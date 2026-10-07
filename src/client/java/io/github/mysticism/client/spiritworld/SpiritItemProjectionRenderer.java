package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.mysticism.client.net.SpiritNetworkingClient;
import io.github.mysticism.dimension.spiritworld.SpiritGlyphSelection;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import java.util.*;

/** Current observer head-centered semantic rings; MAIN mesh depth, then ONE volumetric pass. */
public final class SpiritItemProjectionRenderer {
    private static final Map<String,Visual> visuals=new LinkedHashMap<>();
    private static final Set<String> failedItems=new HashSet<>();
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("Mysticism/SpiritItems");
    private static BufferAllocator allocator;
    private static VertexConsumerProvider.Immediate immediate;
    private static boolean initialized;
    private static long lastFrame;
    private static final class Visual {
        SpiritGlyphSelection.Glyph glyph; ItemStack stack; Vec3d position; float alpha; boolean present;
        Visual(SpiritGlyphSelection.Glyph glyph,ItemStack stack,Vec3d pos) { this.glyph=glyph; this.stack=stack; position=pos; }
    }
    private SpiritItemProjectionRenderer() {}
    public static void init() {
        if (initialized) return; initialized=true;
        WorldRenderEvents.AFTER_ENTITIES.register(SpiritItemProjectionRenderer::render);
        SpiritRenderReload.register(Identifier.of("mysticism","spirit_items"),SpiritItemProjectionRenderer::resetSession);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->resetSession());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->resetSession());
    }
    public static void resetSession() {
        if (allocator!=null) allocator.close(); allocator=null; immediate=null; visuals.clear(); failedItems.clear(); lastFrame=0;
    }
    private static void render(WorldRenderContext context) {
        var client=MinecraftClient.getInstance();
        if (!ClientSpiritCache.active() || client.player==null) { resetSession(); return; }
        var view=SpiritGlyphFrame.current(context.tickCounter().getTickDelta(false));
        if (view==null || context.matrixStack()==null || context.frustum()==null) return;
        var selected=SpiritNetworkingClient.glyphs();
        if (selected.size()>128) return;
        long now=System.nanoTime(); float dt=lastFrame==0 ? 0 : (float)Math.min(.1,(now-lastFrame)*1e-9); lastFrame=now;
        visuals.values().forEach(v->v.present=false);
        var q=view.position(); var basis=view.basis();
        for (var glyph:selected) {
            Vec3d target=SpiritGlyphSelection.position(glyph,q,basis,view.head(),8);
            Visual visual=visuals.get(glyph.id());
            if (visual==null) {
                if (visuals.size()>=256) continue;
                visual=new Visual(glyph,icon(glyph.id()),target); visuals.put(glyph.id(),visual);
            }
            visual.glyph=glyph; visual.present=true;
            visual.position=visual.position.lerp(target,1-Math.exp(-dt*12));
        }
        visuals.values().removeIf(v->!v.present && v.alpha<=0);
        for (Visual visual:visuals.values()) visual.alpha=Math.max(0,Math.min(1,visual.alpha+(visual.present?1:-1)*dt*3));
        Vec3d camera=context.camera().getPos();
        try {
            for (var entry:visuals.entrySet()) {
                Visual visual=entry.getValue();
                if (visual.alpha<=0 || visual.position.squaredDistanceTo(camera)>=64*64
                        || !context.frustum().isVisible(Box.of(visual.position,2,2,2))) continue;
                drawStack(context,visual.stack,visual.position,.55f,visual.alpha*ShaderManager.effectStrength(),entry.getKey().hashCode());
            }
        } finally { RenderSystem.setShaderColor(1,1,1,1); }
    }
    /** Shared with vanilla-drop semantic descriptions; model is in WORLD, not a GUI overlay. */
    static void drawStack(WorldRenderContext context,ItemStack stack,Vec3d position,float scale,float alpha,int seed) {
        String item=Registries.ITEM.getId(stack.getItem()).toString();
        if (alpha<=.001f || failedItems.contains(item) || failedItems.size()>=128) return;
        if (immediate==null) { allocator=new BufferAllocator(262144); immediate=VertexConsumerProvider.immediate(allocator); }
        var client=MinecraftClient.getInstance(); var matrices=context.matrixStack(); var camera=context.camera().getPos();
        client.getFramebuffer().beginWrite(false); RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
        RenderSystem.setShaderColor(1,1,1,alpha);
        matrices.push();
        try {
            matrices.translate(position.x-camera.x,position.y-camera.y,position.z-camera.z); matrices.scale(scale,scale,scale);
            boolean block=stack.getItem() instanceof BlockItem;
            if (!block) { matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-context.camera().getYaw())); matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(context.camera().getPitch())); }
            // Preserve actual texture AND vertex interpretation for atlas, built-in and glint
            // models; route compatible model geometry to explicit MAIN+depth, not Fabulous AUX.
            VertexConsumerProvider provider=SpiritRenderLayers.main(immediate);
            client.getItemRenderer().renderItem(stack,block?ModelTransformationMode.GROUND:ModelTransformationMode.NONE,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE,OverlayTexture.DEFAULT_UV,matrices,provider,client.world,seed);
            immediate.draw();
        } catch(RuntimeException failure) {
            if(failedItems.add(item)) {
                LOGGER.warn("Projected item {} model failed; skip until session/reload (native stack preserved)",item,failure);
                if(client.player!=null) client.player.sendMessage(net.minecraft.text.Text.literal("Spirit item model unavailable: "+item),true);
            }
            if(allocator!=null)allocator.close();allocator=null;immediate=null;
        } finally { matrices.pop(); RenderSystem.setShaderColor(1,1,1,1); client.getFramebuffer().beginWrite(false); }
    }
    private static ItemStack icon(String id) {
        Identifier identifier=Identifier.tryParse(id); Item item=identifier==null?Items.AMETHYST_SHARD:Registries.ITEM.get(identifier);
        ItemStack stack=new ItemStack(item==Items.AIR?Items.AMETHYST_SHARD:item);
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,false);
        return stack;
    }
}
