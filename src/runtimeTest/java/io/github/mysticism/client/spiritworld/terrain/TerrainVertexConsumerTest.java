package io.github.mysticism.client.spiritworld.terrain;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Exercises the production vertex adapter without a Minecraft client or OpenGL context. */
public final class TerrainVertexConsumerTest {
    private static int checks;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static final class Sink implements VertexConsumer {
        Vector3f normal;int alpha,vertices,textures,overlays,lights;
        public VertexConsumer vertex(float x,float y,float z){vertices++;return this;}
        public VertexConsumer color(int r,int g,int b,int a){alpha=a;return this;}
        public VertexConsumer texture(float u,float v){textures++;return this;}
        public VertexConsumer overlay(int u,int v){overlays++;return this;}
        public VertexConsumer light(int u,int v){lights++;return this;}
        public VertexConsumer normal(float x,float y,float z){normal=new Vector3f(x,y,z);return this;}
    }
    public static void main(String[] args) {
        Sink sink=new Sink();var bounds=new TerrainVertexConsumer.Bounds();
        VertexConsumer consumer=new TerrainVertexConsumer(sink,.25f,bounds);
        consumer.vertex(-2.5f,1.5f,3).color(1,2,3,255).texture(.5f,.1f).overlay(1,2).light(3,4).normal(0,4,0);
        consumer.vertex(1,1.5f,-1);
        Vec3d anchor=new Vec3d(30_000_000,100,-30_000_000);Box b=bounds.world(anchor);
        check(b.contains(anchor.add(-2.5,1.5,3)) && b.contains(anchor.add(1,1.5,-1)),"actual non-unit/flat vertices included at large world coordinates");
        check(b.minX<anchor.x-2.5 && b.maxZ>anchor.z+3,"culling uses measured vertices, not unit box");
        check(sink.vertices==2 && sink.alpha==64 && sink.textures==1 && sink.overlays==1 && sink.lights==1,"attribute forwarding and fading retained");
        check(sink.normal.distance(new Vector3f(0,1,0))<1e-7,"scaled normals normalized before byte packing");
        Matrix3f axes=new Matrix3f().m00(-2).m01(.4f).m02(0).m10(.6f).m11(3).m12(.2f).m20(0).m21(.1f).m22(.5f);
        Vector3f inverseNormal=new Matrix3f(axes).invert().transpose().transform(new Vector3f(0,1,0));
        consumer.normal(inverseNormal.x,inverseNormal.y,inverseNormal.z);
        Vector3f tangentX=axes.transform(new Vector3f(1,0,0)),tangentZ=axes.transform(new Vector3f(0,0,1));
        check(Math.abs(sink.normal.length()-1)<1e-6,"sheared/reflected normal is unit length");
        check(Math.abs(sink.normal.dot(tangentX))<1e-6 && Math.abs(sink.normal.dot(tangentZ))<1e-6,"surface normal perpendicular to transformed face");
        check(sink.normal.dot(axes.transform(new Vector3f(0,1,0)))>0,"reflected surface normal points outward rather than with winding");
        // Simulate signed-byte normal packing: components remain in range after normalization.
        check(Math.abs(sink.normal.x)<=1 && Math.abs(sink.normal.y)<=1 && Math.abs(sink.normal.z)<=1,"packed normal components cannot clip");
        for(float value:new float[]{0,Float.NaN,Float.POSITIVE_INFINITY}) {
            try {consumer.normal(value,0,0);throw new AssertionError("bad normal accepted");}
            catch(IllegalArgumentException expected){checks++;}
        }
        try {consumer.vertex(Float.NaN,0,0);throw new AssertionError("bad vertex accepted");}
        catch(IllegalArgumentException expected){checks++;}
        try {new TerrainVertexConsumer.Bounds().world(Vec3d.ZERO);throw new AssertionError("empty bounds accepted");}
        catch(IllegalStateException expected){checks++;}
        System.out.println("TerrainVertexConsumerTest: "+checks+" checks passed");
    }
}
