package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Observer-local, slow deep-flight deformation of the actual rendered/collided affine cells.
 *
 * A nonlinear wave fitted independently to each cuboid cannot preserve its shared faces (four
 * sampled corners do not determine the other four). Use ONE bounded affine wave per producing
 * source window instead. Its translation and shear evolve together, preserving shared faces,
 * partial block shapes, octree LOD boundaries and support inversion without new mesh primitives.
 * A window touching the protected radius stays wholly unchanged; this deliberately favors floor
 * continuity over bending the distant part of a large connected window. No fabricated surfaces.
 */
final class DeepDistortion {
    private DeepDistortion() {}
    static final double NEAR=8, FULL=40, MAX_SHIFT=2.5;
    private static final long PERIOD=2400;
    private static final double MAX_SHEAR=.18;

    static double strength(double distance) {
        double x=Math.max(0,Math.min(1,(distance-NEAR)/(FULL-NEAR)));
        return x*x*(3-2*x);
    }

    /** Production build hook: ownership labels are not projection-window identities. Preserve the
     * frame order/keys and do not mutate provenance while building a canAlign/transition preview. */
    static List<TerrainMeshFrame.Cell> apply(List<TerrainMeshFrame.Cell> cells,Map<Long,?> producers,
                                            boolean shallow,Vec3d observer,long tick,long seed) {
        if(shallow || cells.isEmpty())return cells;
        Map<Object,List<TerrainMeshFrame.Cell>> patches=new LinkedHashMap<>();
        for(var c:cells)patches.computeIfAbsent(Objects.requireNonNull(producers.get(c.key()),"mesh producer"),
                ignored->new ArrayList<>()).add(c);
        Map<Long,TerrainMeshFrame.Cell> deformed=new HashMap<>();
        for(var patch:patches.values())for(var c:apply(patch,false,observer,tick,seed))deformed.put(c.key(),c);
        return cells.stream().map(c->deformed.get(c.key())).toList();
    }

    /** Bypasses shallow frames exactly. Call after border stitching, separately for each producer.
     * Model AND collision extents participate in proximity and displacement bounds: shapes can
     * extend outside the unit model. The same final cells go to transport, renderer and SAT.
     */
    static List<TerrainMeshFrame.Cell> apply(List<TerrainMeshFrame.Cell> patch,boolean shallow,
                                            Vec3d observer,long tick,long seed) {
        if(shallow || patch.isEmpty())return patch;
        Box bounds=null;double nearest=Double.POSITIVE_INFINITY;
        for(var c:patch) {
            Box extent=c.bounds();
            for(Box shape:c.collision())extent=extent.union(c.bounds(shape));
            nearest=Math.min(nearest,SourceMeshBuilder.distanceSquared(extent,observer));
            bounds=bounds==null?extent:bounds.union(extent);
        }
        // Empty space between separate distant cells is not a supporting surface.
        double amount=strength(Math.sqrt(nearest));
        if(amount==0)return patch;
        Vec3d center=bounds.getCenter();
        double radius=new Vec3d(bounds.getLengthX(),bounds.getLengthY(),bounds.getLengthZ()).length()/2;
        // Never multiply an unbounded UUID by a floating phase: its ULP can exceed the entire
        // animation step. Mix the full seed, then reduce both seed and tick before conversion.
        double phase=Math.floorMod(tick,PERIOD)*(2*Math.PI/PERIOD)+seedPhase(seed);
        Vec3d relative=center.subtract(observer);
        phase+=(relative.x*.43+relative.y*.29+relative.z*.19)*(2*Math.PI/64);
        Vec3d translation=new Vec3d(Math.sin(phase),Math.sin(phase+2.1),Math.sin(phase+4.2))
                .normalize().multiply(MAX_SHIFT*.55*amount);
        Vec3d direction=new Vec3d(.35,.85+.3*Math.sin(phase+.5),.25).normalize();
        Vec3d gradient=new Vec3d(Math.cos(phase),Math.sin(phase+.7),Math.cos(phase+1.9))
                .normalize().multiply(Math.min(MAX_SHEAR,MAX_SHIFT*.45/Math.max(1,radius))*amount);
        // F(p)=p+translation+direction*(gradient dot (p-center)). Every model/shape vertex
        // moves <= MAX_SHIFT*amount. det(I+direction*gradient^T) >= 1-MAX_SHEAR > .8,
        // including skewed/reflected source projections: no per-cell fallback or seam tearing.
        var result=new ArrayList<TerrainMeshFrame.Cell>(patch.size());
        for(var c:patch) {
            Vec3d min=c.min().add(translation).add(direction.multiply(gradient.dotProduct(c.min().subtract(center))));
            result.add(new TerrainMeshFrame.Cell(c.key(),c.material(),c.landmarkId(),c.sourceMin(),min,c.size(),
                    shear(c.axisX(),direction,gradient),shear(c.axisY(),direction,gradient),shear(c.axisZ(),direction,gradient),
                    c.color(),c.light(),c.opacity(),c.collision()));
        }
        return List.copyOf(result);
    }
    private static Vec3d shear(Vec3d axis,Vec3d direction,Vec3d gradient) {
        return axis.add(direction.multiply(gradient.dotProduct(axis)));
    }
    private static double seedPhase(long seed) {
        long mixed=(seed^(seed>>>30))*0xbf58476d1ce4e5b9L;
        mixed=(mixed^(mixed>>>27))*0x94d049bb133111ebL;
        mixed^=mixed>>>31;
        return (mixed>>>11)*0x1.0p-53*(2*Math.PI);
    }
}
