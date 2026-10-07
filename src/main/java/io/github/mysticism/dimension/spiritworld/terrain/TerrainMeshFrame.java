package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An observer's immutable visible geometry. The same affine cells drive rendering and physics. */
public record TerrainMeshFrame(long revision, boolean shallow, String sourceDimension,
        Vec3d sourceOrigin, Vec3d carrierOrigin, List<Material> materials, List<Cell> cells) {
    public static final int MAX_CELLS = 2048, MAX_MATERIALS = 256, MAX_SHAPES = 8;
    public TerrainMeshFrame {
        if (revision < 0 || sourceDimension == null || sourceDimension.length() > 256)
            throw new IllegalArgumentException("mesh identity");
        finite(sourceOrigin); finite(carrierOrigin);
        materials = List.copyOf(materials); cells = List.copyOf(cells);
        if (materials.size() > MAX_MATERIALS || cells.size() > MAX_CELLS)
            throw new IllegalArgumentException("mesh budget");
        var keys = new java.util.HashSet<Long>();
        for (Cell c : cells) {
            if (c.material() < 0 || c.material() >= materials.size() || !keys.add(c.key()))
                throw new IllegalArgumentException("mesh palette/key");
        }
    }
    public record Material(String blockId, Map<String,String> properties) {
        public Material {
            if (blockId == null || blockId.length() > 256 || properties.size() > 64)
                throw new IllegalArgumentException("mesh material");
            properties = java.util.Collections.unmodifiableMap(new TreeMap<>(properties));
            properties.forEach((k,v)-> { if(k.length()>128 || v.length()>128) throw new IllegalArgumentException("mesh property"); });
        }
    }
    /** axisX/Y/Z are the world-space columns for a unit baked block model.
     * collision boxes are LOCAL model coordinates, not conservative world AABBs. */
    public record Cell(long key, int material, String landmarkId, Vec3d sourceMin, Vec3d min,
            Vec3d size, Vec3d axisX, Vec3d axisY, Vec3d axisZ,
            int color, int light, float opacity, List<Box> collision) {
        public Cell {
            if(landmarkId==null || landmarkId.length()>256)throw new IllegalArgumentException("mesh landmark");
            finite(sourceMin); finite(min); finite(size); finite(axisX); finite(axisY); finite(axisZ);
            if(size.x<=0 || size.y<=0 || size.z<=0 || size.x>64 || size.y>64 || size.z>64
                    || axisX.length()>128 || axisY.length()>128 || axisZ.length()>128
                    || !Float.isFinite(opacity) || opacity<0 || opacity>1)
                throw new IllegalArgumentException("mesh cell");
            collision=List.copyOf(collision);
            if(collision.size()>MAX_SHAPES)throw new IllegalArgumentException("shape budget");
            for(Box b:collision) {
                finite(new Vec3d(b.minX,b.minY,b.minZ));finite(new Vec3d(b.maxX,b.maxY,b.maxZ));
                if(b.minX < -2 || b.minY < -2 || b.minZ < -2 || b.maxX > 3 || b.maxY > 3 || b.maxZ > 3
                        || b.getLengthX()<=0 || b.getLengthY()<=0 || b.getLengthZ()<=0)
                    throw new IllegalArgumentException("local collision bounds");
            }
        }
        public Vec3d point(double x,double y,double z) {
            return min.add(axisX.multiply(x)).add(axisY.multiply(y)).add(axisZ.multiply(z));
        }
        public Box bounds(Box b) {
            Vec3d base=point(b.minX,b.minY,b.minZ);
            double xx=axisX.x*b.getLengthX(),yx=axisY.x*b.getLengthY(),zx=axisZ.x*b.getLengthZ();
            double xy=axisX.y*b.getLengthX(),yy=axisY.y*b.getLengthY(),zy=axisZ.y*b.getLengthZ();
            double xz=axisX.z*b.getLengthX(),yz=axisY.z*b.getLengthY(),zz=axisZ.z*b.getLengthZ();
            return new Box(base.x+Math.min(0,xx)+Math.min(0,yx)+Math.min(0,zx),
                    base.y+Math.min(0,xy)+Math.min(0,yy)+Math.min(0,zy),base.z+Math.min(0,xz)+Math.min(0,yz)+Math.min(0,zz),
                    base.x+Math.max(0,xx)+Math.max(0,yx)+Math.max(0,zx),
                    base.y+Math.max(0,xy)+Math.max(0,yy)+Math.max(0,zy),base.z+Math.max(0,xz)+Math.max(0,yz)+Math.max(0,zz));
        }
        public Box bounds() { return bounds(new Box(0,0,0,1,1,1)); }
    }
    private static void finite(Vec3d p) {
        if(p==null || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)
                || Math.abs(p.x)>6.0e7 || Math.abs(p.y)>6.0e7 || Math.abs(p.z)>6.0e7)
            throw new IllegalArgumentException("nonfinite mesh coordinate");
    }
}
