package io.github.mysticism.client.spiritworld;
import io.github.mysticism.client.mixin.*;
import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserve model textures, but never route to Fabulous ITEM_ENTITY/see-through font targets. */
public final class SpiritRenderLayers extends RenderLayer {
    private static final Map<Identifier,RenderLayer> layers=new LinkedHashMap<>();
    private SpiritRenderLayers() {
        super("mysticism_layers",VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS,1536,false,true,()->{},()->{});
    }
    /** Terrain can use this for its baked-model VBO: explicit MAIN, alpha and real depth writes. */
    public static RenderLayer texturedMain(Identifier texture) {
        var existing=layers.get(texture); if(existing!=null)return existing;
        // Avoid retaining arbitrary model texture IDs forever. Overflow still targets MAIN,
        // but loses smooth alpha rather than losing terrain occlusion or growing GPU state.
        if(layers.size()>=128)return RenderLayer.getEntityCutout(texture);
        var layer=of("mysticism_projected_main",VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS,1536,true,true,MultiPhaseParameters.builder()
                .program(ENTITY_TRANSLUCENT_PROGRAM).texture(new Texture(texture,false,false))
                .transparency(TRANSLUCENT_TRANSPARENCY).cull(DISABLE_CULLING)
                .lightmap(ENABLE_LIGHTMAP).overlay(ENABLE_OVERLAY_COLOR)
                .target(MAIN_TARGET).writeMaskState(ALL_MASK).build(false));
        layers.put(texture,layer); return layer;
    }
    public static void clear() { layers.clear(); }
    static VertexConsumerProvider main(VertexConsumerProvider provider) {
        return layer -> {
            if(layer instanceof SpiritLayerAccess access
                    && (Object)access.mysticism$phases() instanceof SpiritLayerPhasesAccess phases
                    && phases.mysticism$texture() instanceof SpiritTextureAccess texture) {
                var id=texture.mysticism$id();
                if(id.isPresent()) {
                    if(layer.getVertexFormat()==VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL)
                        return provider.getBuffer(texturedMain(id.get()));
                    // Vanilla player labels normally have a see-through first pass. Ghosts/peers
                    // must not introduce text through walls when their body is depth-occluded.
                    if(layer.getVertexFormat()==VertexFormats.POSITION_COLOR_TEXTURE_LIGHT)
                        return provider.getBuffer(RenderLayer.getText(id.get()));
                }
            }
            if(layer.getVertexFormat()==VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
                    || layer.getVertexFormat()==VertexFormats.POSITION_COLOR_TEXTURE_LIGHT)
                throw new IllegalStateException("Projected texture phases unavailable: register SpiritLayerAccess/SpiritLayerPhasesAccess/SpiritTextureAccess; unsupported model texture skipped");
            // Special untextured/formats retain their real vertex interpretation. No corrupt casts.
            return provider.getBuffer(layer);
        };
    }
}
