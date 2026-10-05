package io.github.mysticism.landmark;

import io.github.mysticism.vector.Vec384f;
import java.util.Objects;

/** Frozen orthonormal basis, semantic origin, absolute realm origin and epoch. Never camera-relative. */
public final class ProjectionFrame {
    private final long epoch, seed;
    private final LandmarkEmbedding semanticOrigin;
    private final Point3 realmOrigin;
    private final double blocksPerSemanticUnit;
    private final Vec384f x,y,z;
    public ProjectionFrame(long epoch,long seed,LandmarkEmbedding semanticOrigin,Point3 realmOrigin,
                           Vec384f x,Vec384f y,Vec384f z,double blocksPerSemanticUnit) {
        if(epoch<0 || !Double.isFinite(blocksPerSemanticUnit) || blocksPerSemanticUnit<=0 || !Double.isFinite(blocksPerSemanticUnit*blocksPerSemanticUnit) || blocksPerSemanticUnit*blocksPerSemanticUnit==0) throw new IllegalArgumentException("projection metadata");
        this.epoch=epoch; this.seed=seed; this.semanticOrigin=Objects.requireNonNull(semanticOrigin); this.realmOrigin=Objects.requireNonNull(realmOrigin);
        this.x=new Vec384f(x.data()); this.y=new Vec384f(y.data()); this.z=new Vec384f(z.data()); this.blocksPerSemanticUnit=blocksPerSemanticUnit;
        Vec384f[] axes={this.x,this.y,this.z};
        for(int i=0;i<3;i++) {
            for(float f:axes[i].data()) if(!Float.isFinite(f)) throw new IllegalArgumentException("nonfinite basis");
            if(Math.abs(axes[i].l2sq()-1)>1e-4) throw new IllegalArgumentException("non-unit basis");
            for(int j=0;j<i;j++) if(Math.abs(axes[i].dot(axes[j]))>1e-4) throw new IllegalArgumentException("non-orthogonal basis");
        }
    }
    public long epoch() { return epoch; }
    public long seed() { return seed; }
    public LandmarkEmbedding semanticOrigin() { return semanticOrigin; }
    public Point3 realmOrigin() { return realmOrigin; }
    public double blocksPerSemanticUnit() { return blocksPerSemanticUnit; }
    public Vec384f axisX() { return new Vec384f(x.data()); }
    public Vec384f axisY() { return new Vec384f(y.data()); }
    public Vec384f axisZ() { return new Vec384f(z.data()); }
    public Point3 project(LandmarkEmbedding vector) {
        return realmOrigin.add(new Point3(vector.dotDifference(semanticOrigin,x),vector.dotDifference(semanticOrigin,y),vector.dotDifference(semanticOrigin,z)).scale(blocksPerSemanticUnit));
    }
    public Placement place(Landmark landmark,double blocksPerSourceBlock) {
        return new Placement(landmark.id(),epoch,project(landmark.baseEmbedding()),landmark.anchor(),blocksPerSourceBlock);
    }
}
