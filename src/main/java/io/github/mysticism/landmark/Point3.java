package io.github.mysticism.landmark;

/** Absolute projected coordinates in realm blocks (or an explicitly documented source point). */
public record Point3(double x, double y, double z) {
    public Point3 { if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("nonfinite point"); }
    public Point3 add(Point3 b) { return new Point3(x+b.x,y+b.y,z+b.z); }
    public Point3 subtract(Point3 b) { return new Point3(x-b.x,y-b.y,z-b.z); }
    public Point3 scale(double s) { return new Point3(x*s,y*s,z*s); }
    public double distanceSquared(Point3 b) { double dx=x-b.x,dy=y-b.y,dz=z-b.z; return dx*dx+dy*dy+dz*dz; }
    public Point3 interpolate(Point3 b, double t) {
        if (!Double.isFinite(t)) throw new IllegalArgumentException("time");
        t=Math.max(0,Math.min(1,t)); return scale(1-t).add(b.scale(t));
    }
}
