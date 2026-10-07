package io.github.mysticism.client.spiritworld.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.mysticism.client.spiritworld.ShaderManager;
import io.github.mysticism.client.spiritworld.SpiritRenderLayers;
import io.github.mysticism.dimension.spiritworld.terrain.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import java.util.*;

/** Actual MAIN depth-writing observer terrain renderer and client-predicted collision cache. */
public final class SpiritTerrainClient {
    private SpiritTerrainClient() {}
    private static final RenderLayer LAYER=SpiritRenderLayers.texturedMain(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    private static TerrainMeshFrame frame;
    private static boolean initialized,dirty;
    private static String failure;
    private static final List<Batch> gpu=new ArrayList<>();
    private record Group(int x,int y,int z) {}
    private record Batch(VertexBuffer buffer,Box bounds) {}
    public static void init() {
        if(initialized)return;initialized=true;
        MeshCollision.clientFrames(uuid->{var p=MinecraftClient.getInstance().player;return p!=null && uuid.equals(p.getUuid())?frame:null;});
        // VBOs use positionMatrix directly, so no AFTER_ENTITIES matrix-stack dependency/order race.
        WorldRenderEvents.BEFORE_ENTITIES.register(SpiritTerrainClient::render);
        InvalidateRenderStateCallback.EVENT.register(()->{closeBuffers();dirty=true;});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->clear());
    }
    /** Transport has already validated current connection/entry epoch and reconstructed bounded deltas. */
    public static void accept(TerrainMeshFrame incoming) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread())throw new IllegalStateException("mesh client thread");
        if(frame!=null && incoming.revision()<=frame.revision())return;
        if(client.player!=null && (frame==null || frame.shallow()!=incoming.shallow()))MeshCollision.clearClient();
        frame=incoming;dirty=true;failure=null;
        publishMedium(client,incoming);
    }
    public static Optional<TerrainMeshFrame> frame(){return Optional.ofNullable(frame);}
    public static Optional<String> failure(){return Optional.ofNullable(failure);}
    public static void clear(){MeshCollision.clearClient();frame=null;dirty=false;failure=null;closeBuffers();ShaderManager.setMediumSamples(new float[4096],Vec3d.ZERO);}
    private static void closeBuffers(){for(Batch b:gpu)b.buffer.close();gpu.clear();}
    private static void upload(MinecraftClient client) {
        closeBuffers();if(frame==null)return;
        var models=client.getBlockRenderManager();List<BlockState> palette=frame.materials().stream().map(TerrainMaterials::resolve).toList();
        Map<Group,List<TerrainMeshFrame.Cell>> groups=new LinkedHashMap<>();
        Vec3d anchor=frame.carrierOrigin();
        for(var cell:frame.cells()) {
            if(cell.opacity()<=0)continue;
            var center=cell.bounds().getCenter().subtract(anchor);
            Group key=new Group((int)Math.floor(center.x/16),(int)Math.floor(center.y/16),(int)Math.floor(center.z/16));
            // Bound GPU object count. Excess far groups share a single overflow VBO, still fog/frustum culled as a batch.
            if(!groups.containsKey(key) && groups.size()>=63)key=new Group(Integer.MIN_VALUE,0,0);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(cell);
        }
        for(var cells:groups.values()) {
            BufferAllocator allocator=new BufferAllocator(65536);
            try {
                BufferBuilder builder=new BufferBuilder(allocator,LAYER.getDrawMode(),LAYER.getVertexFormat());
                Box bounds=null;
                for(var cell:cells) {
                    Box b=cell.bounds();bounds=bounds==null?b:bounds.union(b);
                    BlockState state=palette.get(cell.material());
                    VertexConsumer consumer=new AlphaConsumer(builder,cell.opacity());
                    if(state.getRenderType()==BlockRenderType.MODEL)drawModel(models,state,cell,anchor,consumer,null);
                    else for(Box shape:cell.collision())drawModel(models,Blocks.STONE.getDefaultState(),cell,anchor,consumer,shape);
                }
                BuiltBuffer built=builder.endNullable();
                if(built==null)continue;
                VertexBuffer vbo=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                try {vbo.bind();vbo.upload(built);gpu.add(new Batch(vbo,bounds));}
                catch(RuntimeException failure){vbo.close();throw failure;}
                finally{VertexBuffer.unbind();} // upload owns/closes BuiltBuffer in Yarn 1.21.1
            } finally{allocator.close();}
        }
        dirty=false;
    }
    private static void drawModel(net.minecraft.client.render.block.BlockRenderManager models,BlockState state,TerrainMeshFrame.Cell c,Vec3d anchor,VertexConsumer consumer,Box primitive) {
        Vec3d min=c.min().subtract(anchor),x=c.axisX(),y=c.axisY(),z=c.axisZ();
        if(primitive!=null){min=c.point(primitive.minX,primitive.minY,primitive.minZ).subtract(anchor);x=x.multiply(primitive.getLengthX());y=y.multiply(primitive.getLengthY());z=z.multiply(primitive.getLengthZ());}
        Matrix4f transform=new Matrix4f().m00((float)x.x).m01((float)x.y).m02((float)x.z)
                .m10((float)y.x).m11((float)y.y).m12((float)y.z).m20((float)z.x).m21((float)z.y).m22((float)z.z)
                .m30((float)min.x).m31((float)min.y).m32((float)min.z);
        MatrixStack stack=new MatrixStack();stack.multiplyPositionMatrix(transform);
        stack.peek().getNormalMatrix().set(new Matrix3f(transform).invert().transpose());
        int color=c.color();models.getModelRenderer().render(stack.peek(),consumer,state,models.getModel(state),
                ((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,c.light(),OverlayTexture.DEFAULT_UV);
    }
    private static void render(WorldRenderContext context) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(client.player==null || client.world==null || !client.world.getRegistryKey().equals(SpiritTerrainService.WORLD)) {if(frame!=null)clear();return;}
        if(frame==null)return;
        int oldDraw=GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            if(dirty)upload(client);
            Vec3d camera=context.camera().getPos();Vec3d offset=frame.carrierOrigin().subtract(camera);
            Matrix4f view=new Matrix4f(context.positionMatrix()).translate((float)offset.x,(float)offset.y,(float)offset.z);
            client.getFramebuffer().beginWrite(false);RenderSystem.enableDepthTest();RenderSystem.depthMask(true);
            LAYER.startDrawing();
            try {
                for(Batch b:gpu) {
                    if(distanceSquared(b.bounds,camera)>64*64 || context.frustum()!=null && !context.frustum().isVisible(b.bounds))continue;
                    b.buffer.bind();b.buffer.draw(view,context.projectionMatrix(),RenderSystem.getShader());
                }
            } finally{VertexBuffer.unbind();LAYER.endDrawing();}
            ShaderManager.afterMeshDepth();
        } catch(RuntimeException error) {
            closeBuffers();dirty=false;
            if(!Objects.equals(failure,error.toString())){failure=error.toString();client.player.sendMessage(net.minecraft.text.Text.literal("Spirit mesh unavailable: "+error.getMessage()),true);}
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
        }
    }
    private static double distanceSquared(Box b,Vec3d p){double x=Math.max(Math.max(b.minX-p.x,0),p.x-b.maxX),y=Math.max(Math.max(b.minY-p.y,0),p.y-b.maxY),z=Math.max(Math.max(b.minZ-p.z,0),p.z-b.maxZ);return x*x+y*y+z*z;}
    private static void publishMedium(MinecraftClient client,TerrainMeshFrame f) {
        if(client.player==null)return;Vec3d center=client.player.getEyePos();float[] medium=new float[4096];int work=0;
        outer:for(var cell:f.cells())for(Box local:cell.collision()) {
            Box b=cell.bounds(local);
            int x0=Math.max(0,(int)Math.floor((b.minX-center.x)/8)+8),x1=Math.min(15,(int)Math.floor((b.maxX-center.x)/8)+8);
            int y0=Math.max(0,(int)Math.floor((b.minY-center.y)/8)+8),y1=Math.min(15,(int)Math.floor((b.maxY-center.y)/8)+8);
            int z0=Math.max(0,(int)Math.floor((b.minZ-center.z)/8)+8),z1=Math.min(15,(int)Math.floor((b.maxZ-center.z)/8)+8);
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++) {if(work++>=16384)break outer;medium[x+16*(y+16*z)]=Math.max(medium[x+16*(y+16*z)],.5f*cell.opacity());}
        }
        ShaderManager.setMediumSamples(medium,center);
    }
    private record AlphaConsumer(VertexConsumer delegate,float alpha) implements VertexConsumer {
        @Override public VertexConsumer vertex(float x,float y,float z){delegate.vertex(x,y,z);return this;}
        @Override public VertexConsumer color(int r,int g,int b,int a){delegate.color(r,g,b,Math.round(a*alpha));return this;}
        @Override public VertexConsumer texture(float u,float v){delegate.texture(u,v);return this;}
        @Override public VertexConsumer overlay(int u,int v){delegate.overlay(u,v);return this;}
        @Override public VertexConsumer light(int u,int v){delegate.light(u,v);return this;}
        @Override public VertexConsumer normal(float x,float y,float z){delegate.normal(x,y,z);return this;}
    }
}
