package io.github.mysticism.client.mixin;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderLayer.MultiPhaseParameters.class)
public interface SpiritLayerPhasesAccess {
    @Accessor("texture") RenderPhase.TextureBase mysticism$texture();
}
