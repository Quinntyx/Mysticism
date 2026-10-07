package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import java.nio.ByteBuffer;

/** 16³ voxel samples packed into a 64² atlas. No reads/scans of world or terrain repositories. */
final class SpiritMediumTexture {
    private static final ByteBuffer pixels=ByteBuffer.allocateDirect(64*64*4);
    private static float[] pending;
    private static Vec3d center=Vec3d.ZERO;
    private static Object publishedWorld;
    private static long revision,uploaded=-1;
    private static int texture;
    private SpiritMediumTexture() {}
    static void publish(float[] data) {
        publish(data,MinecraftClient.getInstance().gameRenderer.getCamera().getPos());
    }
    static void publish(float[] data,Vec3d origin) {
        RenderSystem.assertOnRenderThread();
        if(origin==null || !Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z))
            throw new IllegalArgumentException("Invalid occupancy center");
        if (data.length!=4096) throw new IllegalArgumentException("Expected 16 cubed occupancy samples");
        float[] copy=data.clone();
        for (float value:copy) if (!Float.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("Invalid occupancy");
        pending=copy; center=origin; publishedWorld=MinecraftClient.getInstance().world; revision++;
    }
    static int prepare() {
        if (pending==null) return 0;
        if (texture==0) { texture=GL11.glGenTextures(); uploaded=-1; }
        if (uploaded==revision) return texture;
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            pixels.clear();
            for (int py=0;py<64;py++) for (int px=0;px<64;px++) {
                int z=(px/16)+4*(py/16),x=px%16,y=py%16;
                int value=Math.round(pending[x+16*(y+16*z)]*255);
                pixels.put((byte)value).put((byte)0).put((byte)0).put((byte)255);
            }
            pixels.flip(); GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            uploaded=revision;
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous); }
        return texture;
    }
    static Vec3d offset(Vec3d camera) { return camera.subtract(center); }
    static void resetGpu() {
        if (texture!=0) GL11.glDeleteTextures(texture);
        texture=0; uploaded=-1;
    }
    static void retainFor(Object currentWorld) { if(publishedWorld!=currentWorld)clear(); }
    static void clear() {
        resetGpu();pending=null;center=Vec3d.ZERO;publishedWorld=null;
    }
}
