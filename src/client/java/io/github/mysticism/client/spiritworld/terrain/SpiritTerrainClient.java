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
    private static final SpiritMediumField mediumField=new SpiritMediumField();
    private static boolean initialized,dirty;
    private static String failure;
    private static final List<Batch> gpu=new ArrayList<>();
    private static List<SpiritMediumField.Surface> visibleSurfaces=List.of();
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
        // Medium is refreshed from the actual render camera with this mesh, not a packet-time eye pose.
    }
    public static Optional<TerrainMeshFrame> frame(){return Optional.ofNullable(frame);}
    public static Optional<String> failure(){return Optional.ofNullable(failure);}
    public static void clear(){MeshCollision.clearClient();frame=null;dirty=false;failure=null;closeBuffers();clearMedium();}
    private static void clearMedium(){mediumField.clear();ShaderManager.clearMediumSamples();}
    private static void closeBuffers(){for(Batch b:gpu)b.buffer.close();gpu.clear();visibleSurfaces=List.of();}
    private static void upload(MinecraftClient client) {
        closeBuffers();if(frame==null)return;
        var models=client.getBlockRenderManager();List<BlockState> palette=frame.materials().stream().map(TerrainMaterials::resolve).toList();
        List<SpiritMediumField.Surface> surfaces=new ArrayList<>();
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
                TerrainVertexConsumer.Bounds emitted=new TerrainVertexConsumer.Bounds();
                for(var cell:cells) {
                    BlockState state=palette.get(cell.material());
                    TerrainVertexConsumer.Bounds modelBounds=new TerrainVertexConsumer.Bounds();
                    VertexConsumer consumer=new TerrainVertexConsumer(builder,cell.opacity(),modelBounds);
                    if(state.getRenderType()==BlockRenderType.MODEL)drawModel(models,state,cell,anchor,consumer,null);
                    else for(Box shape:cell.collision())drawModel(models,Blocks.STONE.getDefaultState(),cell,anchor,consumer,shape);
                    if(!modelBounds.empty()) {
                        emitted.include(modelBounds);
                        surfaces.add(new SpiritMediumField.Surface(modelBounds.world(anchor),cell.opacity()));
                    }
                }
                BuiltBuffer built=builder.endNullable();
                if(built==null)continue;
                VertexBuffer vbo=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                try {vbo.bind();vbo.upload(built);gpu.add(new Batch(vbo,emitted.world(anchor)));}
                catch(RuntimeException failure){vbo.close();throw failure;}
                finally{VertexBuffer.unbind();} // upload owns/closes BuiltBuffer in Yarn 1.21.1
            } finally{allocator.close();}
        }
        visibleSurfaces=List.copyOf(surfaces);dirty=false;
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
            int viewDistanceChunks=client.options.getClampedViewDistance();
            if(gpu.isEmpty())clearMedium();
            else mediumField.update(frame,camera,visibleSurfaces).ifPresent(samples->ShaderManager.setMediumSamples(samples.occupancy(),samples.center()));
            Matrix4f view=new Matrix4f(context.positionMatrix()).translate((float)offset.x,(float)offset.y,(float)offset.z);
            client.getFramebuffer().beginWrite(false);RenderSystem.enableDepthTest();RenderSystem.depthMask(true);
            LAYER.startDrawing();
            boolean drewMesh=false;
            try {
                for(Batch b:gpu) {
                    if(!TerrainVisibility.isVisible(b.bounds,camera,viewDistanceChunks,context.frustum()))continue;
                    b.buffer.bind();b.buffer.draw(view,context.projectionMatrix(),RenderSystem.getShader());drewMesh=true;
                }
            } finally{VertexBuffer.unbind();LAYER.endDrawing();}
            if(drewMesh)ShaderManager.afterMeshDepth();
        } catch(RuntimeException error) {
            closeBuffers();dirty=false;clearMedium();
            if(!Objects.equals(failure,error.toString())){failure=error.toString();client.player.sendMessage(net.minecraft.text.Text.literal("Spirit mesh unavailable: "+error.getMessage()),true);}
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
        }
    }
}
