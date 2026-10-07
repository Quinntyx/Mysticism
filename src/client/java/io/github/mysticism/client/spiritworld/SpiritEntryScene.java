package io.github.mysticism.client.spiritworld;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.util.UUID;

/** Last actual source WORLD color, before hand/HUD. One bounded framebuffer, never scene geometry. */
final class SpiritEntryScene {
    private static SimpleFramebuffer image;
    private static Object handler;
    private static UUID player;
    private static String dimension;
    private static long captured;
    private SpiritEntryScene() {}
    static void capture(MinecraftClient client) {
        if(client.world==null || client.player==null || client.getNetworkHandler()==null) { clear(); return; }
        if(client.world.getRegistryKey().getValue().toString().equals("mysticism:spirit")) return;
        var main=client.getFramebuffer(); int width=main.textureWidth,height=main.textureHeight;
        if(width<=0 || height<=0)return;
        if(image==null)image=new SimpleFramebuffer(width,height,false,MinecraftClient.IS_SYSTEM_MAC);
        else if(image.textureWidth!=width || image.textureHeight!=height) image.resize(width,height,MinecraftClient.IS_SYSTEM_MAC);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,main.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,image.fbo);
            GL30.glBlitFramebuffer(0,0,width,height,0,0,width,height,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
        } finally { main.beginWrite(false); }
        handler=client.getNetworkHandler();player=client.player.getUuid();
        dimension=client.world.getRegistryKey().getValue().toString();captured=System.nanoTime();
    }
    /** Initial CCA/mesh delivery gap: keep the actual last scene, never invented placement. */
    static boolean hold(MinecraftClient client) {
        if(image==null || client.world==null || client.player==null
                || !client.world.getRegistryKey().getValue().toString().equals("mysticism:spirit")
                || handler!=client.getNetworkHandler() || !player.equals(client.player.getUuid())
                || System.nanoTime()-captured>5_000_000_000L
                || !ClientSpiritCache.sourceDimension().isEmpty() && !dimension.equals(ClientSpiritCache.sourceDimension()))return false;
        var main=client.getFramebuffer();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,image.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,main.fbo);
            GL30.glBlitFramebuffer(0,0,image.textureWidth,image.textureHeight,0,0,main.textureWidth,main.textureHeight,
                    GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
        } finally {main.beginWrite(false);}
        return true;
    }
    static int original(MinecraftClient client,int live) {
        if(image==null || client.player==null || handler!=client.getNetworkHandler()
                || !player.equals(client.player.getUuid()) || !ClientSpiritCache.active()
                || !dimension.equals(ClientSpiritCache.sourceDimension())
                || System.nanoTime()-captured>5_000_000_000L || ShaderManager.effectStrength()>=.999f)return live;
        return image.getColorAttachment();
    }
    static void clear() {
        if(image!=null)image.delete();image=null;handler=null;player=null;dimension=null;captured=0;
    }
}
