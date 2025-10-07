//package io.github.mysticism.client.mixin;
//
//import com.mojang.blaze3d.opengl.GlStateManager;
//import com.mojang.blaze3d.platform.DestFactor;
//import com.mojang.blaze3d.platform.SourceFactor;
//import com.mojang.blaze3d.systems.RenderSystem;
//import com.mojang.blaze3d.vertex.VertexFormat;
//import io.github.mysticism.client.SpiritShaderManager;
//import net.minecraft.client.MinecraftClient;
//import net.minecraft.client.gl.Framebuffer;
//import net.minecraft.client.gl.ShaderProgram;
//import net.minecraft.client.render.BufferBuilder;
//import net.minecraft.client.render.GameRenderer;
//import net.minecraft.client.render.RenderTickCounter;
//import net.minecraft.client.render.Tessellator;
//import net.minecraft.client.render.VertexFormats;
//import org.joml.Matrix4f;
//import org.joml.Matrix4fStack;
//import org.spongepowered.asm.mixin.Final;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.Shadow;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//
//@Mixin(GameRenderer.class)
//public abstract class GameRendererMixin {
//
//    @Shadow @Final private MinecraftClient client;
//    @Shadow abstract ShaderProgram getPositionColorProgram(); // Assume this accessor will find it
//
//    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("RETURN"))
//    private void onRenderEnd(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
//        if (SpiritShaderManager.isEnabled) {
//            Framebuffer framebuffer = this.client.getFramebuffer();
//            int screenWidth = framebuffer.textureWidth;
//            int screenHeight = framebuffer.textureHeight;
//
//            // --- LOW-LEVEL DRAWING BASED ON YOUR API ---
//
//            // 1. Set GL State
//            GlStateManager._disableDepthTest();
//            GlStateManager._depthMask(false);
//            GlStateManager._enableBlend();
//            GlStateManager._blendFuncSeparate(SourceFactor.SRC_ALPHA.ordinal(), DestFactor.ONE_MINUS_SRC_ALPHA.ordinal(), SourceFactor.ONE.ordinal(), DestFactor.ZERO.ordinal());
//            GlStateManager._disableCull();
//
//            // 2. Activate the shader program
//            ShaderProgram positionColorShader = getPositionColorProgram();
//            if (positionColorShader == null) return; // Safety check
//            positionColorShader.bind();
//
//            // 3. Set up matrices
//            Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
//            modelViewStack.pushMatrix();
//            modelViewStack.identity();
//
//            Matrix4f projectionMatrix = new Matrix4f().setOrtho(0.0f, screenWidth, screenHeight, 0.0f, 1000.0f, 3000.0f);
//
//            // Upload matrices to the active shader
//            positionColorShader.getProjectionMatrix().set(projectionMatrix);
//            positionColorShader.getModelViewMatrix().set(modelViewStack);
//
//            // 4. Use the classic Tessellator pattern
//            Tessellator tessellator = Tessellator.getInstance();
//            BufferBuilder bufferBuilder = tessellator.getBuffer(); // Use the singleton buffer
//
//            bufferBuilder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
//
//            // 5. Build the quad, using endVertex()
//            bufferBuilder.vertex(0.0f, (float)screenHeight, 0.0f).color(1.0f, 0.0f, 1.0f, 1.0f);
//            bufferBuilder.vertex((float)screenWidth, (float)screenHeight, 0.0f).color(1.0f, 0.0f, 1.0f, 1.0f);
//            bufferBuilder.vertex((float)screenWidth, 0.0f, 0.0f).color(1.0f, 0.0f, 1.0f, 1.0f);
//            bufferBuilder.vertex(0.0f, 0.0f, 0.0f).color(1.0f, 0.0f, 1.0f, 1.0f);
//
//            // 6. Execute the draw call
//            tessellator.draw();
//
//            // 7. Clean up
//            positionColorShader.unbind();
//            modelViewStack.popMatrix();
//
//            GlStateManager._depthMask(true);
//            GlStateManager._enableDepthTest();
//            GlStateManager._enableCull();
//            GlStateManager._disableBlend();
//        }
//    }
//}