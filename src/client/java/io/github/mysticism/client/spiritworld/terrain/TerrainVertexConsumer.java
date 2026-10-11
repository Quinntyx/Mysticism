package io.github.mysticism.client.spiritworld.terrain;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Bounds the actual uploaded vertices, including non-unit baked models, and emits unit normals. */
final class TerrainVertexConsumer implements VertexConsumer {
    static final class Bounds {
        private double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        private double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        void add(float x,float y,float z) {
            if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("nonfinite terrain vertex");
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        Box world(Vec3d anchor) {
            if(minX==Double.POSITIVE_INFINITY)throw new IllegalStateException("empty terrain bounds");
            // Vertices in the VBO are floats relative to the carrier anchor. Bound those exact
            // values, not a guessed unit model or a pre-rounded transform; include flat quads.
            return new Box(minX,minY,minZ,maxX,maxY,maxZ).offset(anchor).expand(1e-4);
        }
    }
    private final VertexConsumer delegate;
    private final float alpha;
    private final Bounds bounds;
    TerrainVertexConsumer(VertexConsumer delegate,float alpha,Bounds bounds) {
        this.delegate=delegate;this.alpha=alpha;this.bounds=bounds;
    }
    @Override public VertexConsumer vertex(float x,float y,float z){bounds.add(x,y,z);delegate.vertex(x,y,z);return this;}
    @Override public VertexConsumer color(int r,int g,int b,int a){delegate.color(r,g,b,Math.round(a*alpha));return this;}
    @Override public VertexConsumer texture(float u,float v){delegate.texture(u,v);return this;}
    @Override public VertexConsumer overlay(int u,int v){delegate.overlay(u,v);return this;}
    @Override public VertexConsumer light(int u,int v){delegate.light(u,v);return this;}
    @Override public VertexConsumer normal(float x,float y,float z) {
        // Inverse-transpose is correct for shear/reflection, but not unit length under scale.
        // Normalize before BufferBuilder quantizes to signed bytes (otherwise components clip).
        double length=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        if(!Double.isFinite(length) || length==0)throw new IllegalArgumentException("invalid terrain normal");
        delegate.normal((float)(x/length),(float)(y/length),(float)(z/length));return this;
    }
}
