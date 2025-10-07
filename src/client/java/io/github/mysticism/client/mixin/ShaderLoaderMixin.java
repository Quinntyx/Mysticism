//package io.github.mysticism.client.mixin;
//
//
//import net.minecraft.client.gl.ShaderLoader;
//import net.minecraft.resource.ResourceManager;
//import net.minecraft.util.profiler.Profiler;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//
//// The target class is ShaderLoader.Definitions, which is passed to apply()
//import net.minecraft.client.gl.ShaderLoader.Definitions;
//
//@Mixin(ShaderLoader.class)
//public class ShaderLoaderMixin {
//
//    // Target the 'apply' method, which is the second phase of the reload.
//    // We inject at the very end ("TAIL") to ensure all vanilla shaders are ready.
//    @Inject(
//            method = "apply(Lnet/minecraft/client/gl/ShaderLoader$Definitions;Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/profiler/Profiler;)V",
//            at = @At("TAIL")
//    )
//    private void onShadersApplied(Definitions definitions, ResourceManager manager, Profiler profiler, CallbackInfo ci) {
//        // This code now runs at the perfect moment.
//        SpiritShaderManager.load(manager);
//    }
//}