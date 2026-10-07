package io.github.mysticism.client.spiritworld.mixin;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.client.render.RenderLayer$MultiPhase")
public interface SpiritLayerAccess {
    @Accessor("phases") RenderLayer.MultiPhaseParameters mysticism$phases();
}
