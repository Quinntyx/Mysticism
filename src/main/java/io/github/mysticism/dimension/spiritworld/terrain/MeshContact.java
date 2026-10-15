package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exact affine contact witness: clip both polyhedra's edges, never clamp a foot center to a voxel. */
final class MeshContact {
    private MeshContact() {}
    static Vec3d local(TerrainMeshFrame.Cell c,Vec3d world) {
        Vec3d v=world.subtract(c.min());double det=c.axisX().dotProduct(c.axisY().crossProduct(c.axisZ()));
        return new Vec3d(v.dotProduct(c.axisY().crossProduct(c.axisZ()))/det,
                v.dotProduct(c.axisZ().crossProduct(c.axisX()))/det,v.dotProduct(c.axisX().crossProduct(c.axisY()))/det);
    }
    static Vec3d source(TerrainMeshFrame.Cell c,Vec3d world) {
        Vec3d v=local(c,world);
        return c.sourceMin().add(v.x*c.size().x,v.y*c.size().y,v.z*c.size().z);
    }
    static Optional<Vec3d> point(TerrainMeshFrame.Cell cell,Box shape,Box body) {
        // Collapsed cells have no invertible rendered volume and cannot prove support.
        if(Math.abs(cell.axisX().dotProduct(cell.axisY().crossProduct(cell.axisZ())))<1e-12)return Optional.empty();
        Vec3d[] a=corners(shape),b=corners(body);List<Vec3d> points=new ArrayList<>(48);
        for(int i=0;i<8;i++)for(int axis=0;axis<3;axis++) {
            int j=i^(1<<axis);if(j<i)continue;
            Vec3d from=cell.point(a[i].x,a[i].y,a[i].z),to=cell.point(a[j].x,a[j].y,a[j].z);
            clip(from,to,body,from,to,points);
            clip(local(cell,b[i]),local(cell,b[j]),shape,b[i],b[j],points);
        }
        if(points.isEmpty())return Optional.empty();
        Vec3d sum=Vec3d.ZERO;for(Vec3d p:points)sum=sum.add(p);
        // Convex average remains inside the actual intersection, including edge-only footprint overlap.
        return Optional.of(sum.multiply(1.0/points.size()));
    }
    private static Vec3d[] corners(Box b) {
        Vec3d[] result=new Vec3d[8];
        for(int i=0;i<8;i++)result[i]=new Vec3d((i&1)==0?b.minX:b.maxX,(i&2)==0?b.minY:b.maxY,(i&4)==0?b.minZ:b.maxZ);
        return result;
    }
    private static void clip(Vec3d a,Vec3d b,Box box,Vec3d worldA,Vec3d worldB,List<Vec3d> points) {
        double lo=0,hi=1;double[] start={a.x,a.y,a.z},end={b.x,b.y,b.z};
        double[] min={box.minX,box.minY,box.minZ},max={box.maxX,box.maxY,box.maxZ};
        for(int axis=0;axis<3;axis++) {
            double speed=end[axis]-start[axis];
            if(Math.abs(speed)<1e-12) {if(start[axis]<min[axis]-1e-7 || start[axis]>max[axis]+1e-7)return;continue;}
            double t0=(min[axis]-start[axis])/speed,t1=(max[axis]-start[axis])/speed;
            lo=Math.max(lo,Math.min(t0,t1));hi=Math.min(hi,Math.max(t0,t1));
            if(lo>hi+1e-7)return;
        }
        Vec3d delta=worldB.subtract(worldA);
        points.add(worldA.add(delta.multiply(Math.max(0,Math.min(1,lo)))));
        points.add(worldA.add(delta.multiply(Math.max(0,Math.min(1,hi)))));
    }
}
