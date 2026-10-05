package io.github.mysticism.landmark;

/** Half-open projected AABB, in realm blocks. */
public record RealmBounds(Point3 min, Point3 max) {
    public RealmBounds { if(min.x()>=max.x() || min.y()>=max.y() || min.z()>=max.z()) throw new IllegalArgumentException("realm bounds"); }
    public double distanceSquared(Point3 p) {
        double x=Math.max(Math.max(min.x()-p.x(),p.x()-max.x()),0),y=Math.max(Math.max(min.y()-p.y(),p.y()-max.y()),0),z=Math.max(Math.max(min.z()-p.z(),p.z()-max.z()),0);
        return x*x+y*y+z*z;
    }
}
