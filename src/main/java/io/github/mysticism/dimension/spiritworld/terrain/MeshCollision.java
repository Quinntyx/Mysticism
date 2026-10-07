package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.Function;

/** Swept AABB against visible affine block-shape parallelepipeds, shared by server and prediction. */
public final class MeshCollision {
    private MeshCollision() {}
    private static volatile Function<UUID,TerrainMeshFrame> client = id -> null;
    private static final Map<UUID,Index> SERVER = new java.util.concurrent.ConcurrentHashMap<>(), CLIENT = new java.util.concurrent.ConcurrentHashMap<>();
    public static void clientFrames(Function<UUID,TerrainMeshFrame> provider) { client=Objects.requireNonNull(provider); }
    public static void clear(UUID player) { SERVER.remove(player); CLIENT.remove(player); }
    public static void clearClient() { CLIENT.clear(); }
    public record Hit(double time, Vec3d normal, TerrainMeshFrame.Cell cell) {}
    private record Bucket(int x,int y,int z) {}
    private record Shape(TerrainMeshFrame.Cell cell,Box local,Box bounds) {}
    public static final class Index {
        final TerrainMeshFrame frame;
        final Map<Bucket,List<Shape>> buckets=new HashMap<>();
        final List<Shape> large=new ArrayList<>();
        public Index(TerrainMeshFrame frame) {
            this.frame=frame;
            for(var cell:frame.cells())for(Box local:cell.collision()) {
                // Render/collision are BOTH absent once a fog-hidden region has finished fading out.
                if(cell.opacity()<=0)continue;
                var shape=new Shape(cell,local,cell.bounds(local));Box b=shape.bounds;
                int x0=grid(b.minX),y0=grid(b.minY),z0=grid(b.minZ),x1=grid(b.maxX),y1=grid(b.maxY),z1=grid(b.maxZ);
                long n=(long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1);
                if(n>64){large.add(shape);continue;}
                for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)
                    buckets.computeIfAbsent(new Bucket(x,y,z),k->new ArrayList<>()).add(shape);
            }
        }
        private List<Shape> nearby(Box query) {
            var result=new LinkedHashSet<Shape>();
            int x0=grid(query.minX),y0=grid(query.minY),z0=grid(query.minZ),x1=grid(query.maxX),y1=grid(query.maxY),z1=grid(query.maxZ);
            if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>4096)return List.of(); // caller splits long travel
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)
                for(Shape s:buckets.getOrDefault(new Bucket(x,y,z),List.of()))if(s.bounds.intersects(query))result.add(s);
            for(Shape s:large)if(s.bounds.intersects(query))result.add(s);
            return List.copyOf(result);
        }
        public Optional<Hit> sweep(Box body,Vec3d delta) {
            Hit best=null;
            for(Shape s:nearby(body.stretch(delta).expand(1e-5))) {
                Hit hit=intersect(body,delta,s);
                if(hit!=null && (best==null || hit.time<best.time))best=hit;
            }
            return Optional.ofNullable(best);
        }
        public boolean clearRay(Vec3d from,Vec3d to) {
            return sweep(new Box(from.x-1e-4,from.y-1e-4,from.z-1e-4,from.x+1e-4,from.y+1e-4,from.z+1e-4),to.subtract(from)).isEmpty();
        }
        private Vec3d depenetrate(Box body) {
            Vec3d moved=Vec3d.ZERO;
            for(int step=0;step<8;step++) {
                Vec3d correction=null;
                for(Shape shape:nearby(body.offset(moved).expand(1e-5))) {
                    Vec3d depth=penetration(body.offset(moved),shape);
                    if(depth!=null && (correction==null || depth.lengthSquared()<correction.lengthSquared()))correction=depth;
                }
                if(correction==null)break;
                if(correction.length()>4)correction=correction.normalize().multiply(4);
                moved=moved.add(correction);if(moved.length()>4)break;
            }
            return moved;
        }
        private Vec3d slide(Box body,Vec3d wanted) {
            Vec3d moved=Vec3d.ZERO,remaining=wanted;
            for(int i=0;i<4 && remaining.lengthSquared()>1e-12;i++) {
                var hit=sweep(body.offset(moved),remaining);
                if(hit.isEmpty()){moved=moved.add(remaining);break;}
                double t=Math.max(0,hit.get().time-1e-5/Math.max(1e-5,remaining.length()));
                moved=moved.add(remaining.multiply(t));remaining=remaining.multiply(1-t);
                double into=remaining.dotProduct(hit.get().normal);
                if(into<0)remaining=remaining.subtract(hit.get().normal.multiply(into));else break;
            }
            return moved;
        }
    }
    private static int grid(double x){return (int)Math.floor(x/8);}
    private static Index index(PlayerEntity player) {
        TerrainMeshFrame frame=player instanceof ServerPlayerEntity server ? SpiritTerrainService.mesh(server).orElse(null) : client.apply(player.getUuid());
        if(frame==null)return null;
        var cache=player.getWorld().isClient?CLIENT:SERVER;
        Index index=cache.get(player.getUuid());
        if(index==null || index.frame!=frame){index=new Index(frame);cache.put(player.getUuid(),index);}
        return index;
    }
    public static Vec3d move(PlayerEntity player,Vec3d wanted) {
        Index i=index(player);
        // No fabricated floor. Preparation supplies geometry before entry; packet delay simply has no surfaces yet.
        if(i==null)return wanted;
        Box body=player.getBoundingBox();
        if(wanted.length()>256)wanted=wanted.multiply(256/wanted.length());
        Vec3d result=i.depenetrate(body);
        // Bound swept traversal for unusually fast flight/teleports rather than tunnelling through a grid overflow.
        int pieces=Math.min(64,Math.max(1,(int)Math.ceil(wanted.length()/4)));
        Vec3d piece=wanted.multiply(1.0/pieces);
        for(int p=0;p<pieces;p++)result=result.add(i.slide(body.offset(result),piece));
        if(!player.getAbilities().flying && (player.isOnGround() || wanted.y<0 && result.y>wanted.y+1e-5)
                && result.subtract(wanted).horizontalLengthSquared()>1e-8) {
            double step=player.getStepHeight();
            if(step>0) {
                Vec3d up=i.slide(body,new Vec3d(0,step,0));
                Vec3d over=i.slide(body.offset(up),new Vec3d(wanted.x,0,wanted.z));
                Vec3d down=i.slide(body.offset(up).offset(over),new Vec3d(0,wanted.y-up.y,0));
                Vec3d stepped=up.add(over).add(down);
                if(stepped.horizontalLengthSquared()>result.horizontalLengthSquared()+1e-8)result=stepped;
            }
        }
        return result;
    }
    /** Vanilla's carrier-air ledge check would prevent all sneaking; use the real mesh below the future footprint. */
    public static Vec3d sneak(PlayerEntity p,Vec3d wanted,MovementType type) {
        Index i=index(p);
        if(i==null || p.getAbilities().flying || !p.isSneaking() || !p.isOnGround() || type!=MovementType.SELF && type!=MovementType.PLAYER)return wanted;
        Box body=p.getBoundingBox();Vec3d down=new Vec3d(0,-Math.max(.6,p.getStepHeight()),0);double x=wanted.x,z=wanted.z;int guard=0;
        while(x!=0 && !supported(i,body.offset(x,0,0),down) && guard++<128)x=trim(x);
        if(guard>=128)x=0;guard=0;
        while(z!=0 && !supported(i,body.offset(0,0,z),down) && guard++<128)z=trim(z);
        if(guard>=128)z=0;guard=0;
        while(x!=0 && z!=0 && !supported(i,body.offset(x,0,z),down) && guard++<128){x=trim(x);z=trim(z);}
        if(guard>=128){x=0;z=0;}return new Vec3d(x,wanted.y,z);
    }
    private static boolean supported(Index i,Box body,Vec3d down){return i.sweep(body,down).filter(h->h.normal.y>.3).isPresent();}
    private static double trim(double x){return Math.abs(x)<=.05?0:x-Math.copySign(.05,x);}
    public static Optional<Hit> ground(PlayerEntity player) {
        Index i=index(player);
        return i==null?Optional.empty():i.sweep(player.getBoundingBox().offset(0,.025,0),new Vec3d(0,-.15,0))
                .filter(h->h.normal.y>.3);
    }
    public static boolean clearRay(ServerPlayerEntity player,Vec3d from,Vec3d to) {
        Index i=index(player);return i!=null && from.distanceTo(to)<=128 && i.clearRay(from,to);
    }
    /** Conservative continuous affine-motion guard: the union of endpoint AABBs contains EVERY intermediate
     * vertex under linear affine interpolation. An ambiguous swept/body overlap is held, never sampled through. */
    public static boolean transitionClear(TerrainMeshFrame old,TerrainMeshFrame next,Box body) {
        if(old==null)return true;
        Map<Long,TerrainMeshFrame.Cell> previous=new HashMap<>();for(var c:old.cells())previous.put(c.key(),c);
        Box local=body.expand(5),strict=body.expand(-1e-5);
        for(var c:next.cells()) {
            var before=previous.get(c.key());
            if(before!=null && c.min().equals(before.min()) && c.axisX().equals(before.axisX()) && c.axisY().equals(before.axisY())
                    && c.axisZ().equals(before.axisZ()) && c.collision().equals(before.collision()))continue;
            if(!c.bounds().intersects(local) && (before==null || !before.bounds().intersects(local)))continue;
            if(before==null) {for(Box b:c.collision())if(penetration(strict,new Shape(c,b,c.bounds(b)))!=null)return false;continue;}
            if(c.collision().isEmpty())continue;
            for(Box collision:c.collision()) {
                Box swept=c.bounds(collision).union(before.bounds(collision));
                if(swept.intersects(strict))return false;
            }
        }
        return true;
    }
    private static Vec3d penetration(Box body,Shape shape) {
        var c=shape.cell;Box b=shape.local;
        Vec3d e0=c.axisX().multiply(b.getLengthX()/2),e1=c.axisY().multiply(b.getLengthY()/2),e2=c.axisZ().multiply(b.getLengthZ()/2);
        Vec3d separation=c.point((b.minX+b.maxX)/2,(b.minY+b.maxY)/2,(b.minZ+b.maxZ)/2).subtract(body.getCenter());
        Vec3d[] world={new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1)};var axes=new ArrayList<Vec3d>(15);
        Collections.addAll(axes,world);axes.add(e1.crossProduct(e2));axes.add(e2.crossProduct(e0));axes.add(e0.crossProduct(e1));
        for(Vec3d w:world){axes.add(w.crossProduct(e0));axes.add(w.crossProduct(e1));axes.add(w.crossProduct(e2));}
        double minimum=Double.POSITIVE_INFINITY;Vec3d correction=null;
        for(Vec3d raw:axes) {
            double length=raw.length();if(length<1e-9)continue;Vec3d axis=raw.multiply(1/length);
            double radius=Math.abs(e0.dotProduct(axis))+Math.abs(e1.dotProduct(axis))+Math.abs(e2.dotProduct(axis))
                    +body.getLengthX()/2*Math.abs(axis.x)+body.getLengthY()/2*Math.abs(axis.y)+body.getLengthZ()/2*Math.abs(axis.z);
            double distance=separation.dotProduct(axis),depth=radius-Math.abs(distance);if(depth<=1e-7)return null;
            if(depth<minimum){minimum=depth;correction=axis.multiply((distance>=0?-1:1)*(depth+1e-5));}
        }
        return correction;
    }
    private static Hit intersect(Box body,Vec3d velocity,Shape shape) {
        Vec3d overlap=penetration(body,shape);
        if(overlap!=null)return velocity.dotProduct(overlap)<0?new Hit(0,overlap.normalize(),shape.cell):null;
        var c=shape.cell;Box b=shape.local;
        Vec3d e0=c.axisX().multiply(b.getLengthX()/2),e1=c.axisY().multiply(b.getLengthY()/2),e2=c.axisZ().multiply(b.getLengthZ()/2);
        Vec3d center=c.point((b.minX+b.maxX)/2,(b.minY+b.maxY)/2,(b.minZ+b.maxZ)/2);
        Vec3d separation=center.subtract(body.getCenter());
        Vec3d[] world={new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1)};
        var axes=new ArrayList<Vec3d>(15);Collections.addAll(axes,world);
        axes.add(e1.crossProduct(e2));axes.add(e2.crossProduct(e0));axes.add(e0.crossProduct(e1));
        for(Vec3d w:world){axes.add(w.crossProduct(e0));axes.add(w.crossProduct(e1));axes.add(w.crossProduct(e2));}
        double enter=-Double.MAX_VALUE,exit=1;
        Vec3d normal=null;
        for(Vec3d raw:axes) {
            double length=raw.length();if(length<1e-9)continue;
            Vec3d axis=raw.multiply(1/length);
            double radius=Math.abs(e0.dotProduct(axis))+Math.abs(e1.dotProduct(axis))+Math.abs(e2.dotProduct(axis))
                    +body.getLengthX()/2*Math.abs(axis.x)+body.getLengthY()/2*Math.abs(axis.y)+body.getLengthZ()/2*Math.abs(axis.z);
            double distance=separation.dotProduct(axis),speed=velocity.dotProduct(axis);
            if(Math.abs(speed)<1e-12){if(Math.abs(distance)>=radius-1e-8)return null;continue;}
            double t0=(distance-radius)/speed,t1=(distance+radius)/speed;
            Vec3d n=axis.multiply(speed>0?-1:1);
            if(t0>t1){double swap=t0;t0=t1;t1=swap;}
            if(t0>enter){enter=t0;normal=n;}
            exit=Math.min(exit,t1);if(enter>exit+1e-10)return null;
        }
        // Initial overlap is resolved by depenetration above; touching inward remains a time-zero contact.
        if(normal==null || enter< -1e-6 || enter>1 || exit<0)return null;
        return new Hit(Math.max(0,enter),normal,c);
    }
}
