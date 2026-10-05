package io.github.mysticism.landmark;

/** Half-open absolute block bounds [min,max). Touching faces do not intersect. */
public record Bounds(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
    public Bounds {
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) throw new IllegalArgumentException("empty bounds");
        Math.subtractExact(maxX, minX); Math.subtractExact(maxY, minY); Math.subtractExact(maxZ, minZ);
    }
    public static Bounds cube(long x, long y, long z, long side) {
        if (side <= 0) throw new IllegalArgumentException("side");
        return new Bounds(x, y, z, Math.addExact(x, side), Math.addExact(y, side), Math.addExact(z, side));
    }
    public boolean contains(long x, long y, long z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }
    public boolean contains(Bounds b) {
        return minX <= b.minX && minY <= b.minY && minZ <= b.minZ && maxX >= b.maxX && maxY >= b.maxY && maxZ >= b.maxZ;
    }
    public boolean intersects(Bounds b) {
        return minX < b.maxX && b.minX < maxX && minY < b.maxY && b.minY < maxY && minZ < b.maxZ && b.minZ < maxZ;
    }
    public Bounds union(Bounds b) {
        return new Bounds(Math.min(minX,b.minX), Math.min(minY,b.minY), Math.min(minZ,b.minZ),
                Math.max(maxX,b.maxX), Math.max(maxY,b.maxY), Math.max(maxZ,b.maxZ));
    }
    public double distanceSquared(Point3 p) {
        double dx = Math.max(Math.max(minX-p.x(), p.x()-maxX),0);
        double dy = Math.max(Math.max(minY-p.y(), p.y()-maxY),0);
        double dz = Math.max(Math.max(minZ-p.z(), p.z()-maxZ),0);
        return dx*dx+dy*dy+dz*dz;
    }
    public Point3 center() { return new Point3(minX+(maxX-minX)*0.5, minY+(maxY-minY)*0.5, minZ+(maxZ-minZ)*0.5); }
}
