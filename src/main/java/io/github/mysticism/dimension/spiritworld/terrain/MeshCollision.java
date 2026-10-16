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
    private static final double SUPPORT_LIFT = .025, GROUND_REACH = .125, WALKABLE_Y = .3;
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
            int x0=grid(query.minX),y0=grid(query.minY),z0=grid(query.minZ),x1=grid(query.maxX),y1=grid(query.maxY),z1=grid(query.maxZ);
            if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>4096)return exhaustive(query); // Oversized swept query: exact scan, NEVER an empty result.
            var result=new LinkedHashSet<Shape>();
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)
                for(Shape s:buckets.getOrDefault(new Bucket(x,y,z),List.of()))if(s.bounds.intersects(query))result.add(s);
            for(Shape s:large)if(s.bounds.intersects(query))result.add(s);
            return List.copyOf(result);
        }
        /** An oversized query (long travel/ray) must disable NOTHING: scan every indexed shape instead of
         * returning an empty list that would silently let motion tunnel straight through displayed terrain. */
        private List<Shape> exhaustive(Box query) {
            var result=new LinkedHashSet<Shape>();
            for(var shapes:buckets.values())for(Shape s:shapes)if(s.bounds.intersects(query))result.add(s);
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
            // Chunked so each segment stays on the bucket grid; a hit in ANY segment blocks the whole ray.
            Vec3d total=to.subtract(from);double length=total.length();
            if(length<=1e-9) {
                // A stationary sweep has no entering normal, including when embedded. Test
                // occupancy explicitly instead of inverting an empty hit into "blocked air".
                Box point=tinyBody(from);
                for(Shape shape:nearby(point))if(penetration(point,shape)!=null)return false;
                return true;
            }
            int pieces=(int)Math.min(64,Math.max(1,(int)Math.ceil(length/60)));
            Vec3d piece=total.multiply(1.0/pieces);Vec3d at=from;
            for(int i=0;i<pieces;i++) {
                if(!sweep(tinyBody(at),piece).isEmpty())return false;
                at=at.add(piece);
            }
            return true;
        }
        private static Box tinyBody(Vec3d at){return new Box(at.x-1e-4,at.y-1e-4,at.z-1e-4,at.x+1e-4,at.y+1e-4,at.z+1e-4);}
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
        /** Find actual upward contact, not the first wall/ceiling hit or a carrier block flag.
         * The small lift tolerates the solver's contact skin; deep initial penetration is not support. */
        public Optional<Hit> support(Box body,double reach) {
            return supportSweep(body,Math.max(0,reach)+SUPPORT_LIFT);
        }
        private Optional<Hit> supportSweep(Box body,double depth) {
            Box probe=body.offset(0,SUPPORT_LIFT,0);
            Vec3d down=new Vec3d(0,-depth,0);
            Hit best=null;
            for(Shape shape:nearby(probe.stretch(down).expand(1e-5))) {
                if(penetration(probe,shape)!=null)continue;
                Hit hit=intersect(probe,down,shape);
                if(hit!=null && hit.normal.y>WALKABLE_Y && (best==null || hit.time<best.time))best=hit;
            }
            return Optional.ofNullable(best);
        }
        public Optional<Hit> ground(Box body) { return support(body,GROUND_REACH); }

        private Vec3d slide(Box body,Vec3d wanted,boolean walking) {
            Vec3d moved=Vec3d.ZERO,remaining=wanted;
            for(int i=0;i<4 && remaining.lengthSquared()>1e-12;i++) {
                var hit=sweep(body.offset(moved),remaining);
                if(hit.isEmpty()){moved=moved.add(remaining);break;}
                double t=Math.max(0,hit.get().time-1e-5/Math.max(1e-5,remaining.length()));
                moved=moved.add(remaining.multiply(t));remaining=remaining.multiply(1-t);
                double into=remaining.dotProduct(hit.get().normal);
                if(into<0) {
                    Vec3d normal=hit.get().normal;
                    // A grounded upright walker keeps their requested X/Z. Projecting gravity
                    // onto a tilted face creates sideways creep, undoing crouch's edge limit.
                    // Solve the floor constraint vertically instead; walls/flight/jumps still slide.
                    if(walking && normal.y>WALKABLE_Y)remaining=remaining.add(0,-into/normal.y,0);
                    else remaining=remaining.subtract(normal.multiply(into));
                } else break;
            }
            return moved;
        }

        /** Shared server/client movement kernel, independent of vanilla block support. */
        public Vec3d move(Box original,Vec3d wanted,boolean flying,double stepHeight) {
            if(wanted.length()>256)wanted=wanted.multiply(256/wanted.length());
            Vec3d correction=depenetrate(original);
            Box body=original.offset(correction);
            boolean grounded=!flying && ground(body).isPresent();
            boolean walking=grounded && wanted.y<=0;
            Vec3d result=Vec3d.ZERO;
            int pieces=Math.min(64,Math.max(1,(int)Math.ceil(wanted.length()/4)));
            Vec3d piece=wanted.multiply(1.0/pieces);
            for(int p=0;p<pieces;p++)result=result.add(slide(body.offset(result),piece,walking));
            if(!flying && (grounded || wanted.y<0 && result.y>wanted.y+1e-5)
                    && result.subtract(wanted).horizontalLengthSquared()>1e-8 && stepHeight>0) {
                Vec3d up=slide(body,new Vec3d(0,stepHeight,0),false);
                Vec3d over=slide(body.offset(up),new Vec3d(wanted.x,0,wanted.z),walking);
                Vec3d down=slide(body.offset(up).offset(over),new Vec3d(0,wanted.y-up.y,0),walking);
                Vec3d stepped=up.add(over).add(down);
                if(stepped.horizontalLengthSquared()>result.horizontalLengthSquared()+1e-8)result=stepped;
            }
            // Both ordinary and stepped paths start AFTER depenetration; never lose that correction.
            return correction.add(result);
        }

        /** Vanilla-style axis/diagonal backoff, but enabled and limited by mesh contacts only. */
        public Vec3d sneak(Box body,Vec3d wanted,double stepHeight) {
            if(wanted.y>0 || ground(body).isEmpty())return wanted;
            double reach=Math.max(GROUND_REACH,stepHeight),x=wanted.x,z=wanted.z;int guard=0;
            while(x!=0 && !supportedAfterMove(body,x,0,stepHeight,reach) && guard++<128)x=trim(x);
            if(guard>=128)x=0;guard=0;
            while(z!=0 && !supportedAfterMove(body,0,z,stepHeight,reach) && guard++<128)z=trim(z);
            if(guard>=128)z=0;guard=0;
            while(x!=0 && z!=0 && !supportedAfterMove(body,x,z,stepHeight,reach) && guard++<128){x=trim(x);z=trim(z);}
            if(guard>=128){x=0;z=0;}
            return new Vec3d(x,wanted.y,z);
        }
        private boolean supportedAfterMove(Box body,double x,double z,double stepHeight,double reach) {
            // Probe the collision-resolved footprint: on a rising affine face, a horizontal
            // offset alone embeds the body and falsely treats the entire slope as a ledge.
            // Zero Y preserves the reach limit (gravity must not extend safe-drop distance).
            Vec3d resolved=move(body,new Vec3d(x,0,z),false,stepHeight);
            return support(body.offset(resolved),reach).isPresent();
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
        return i.move(player.getBoundingBox(),wanted,player.getAbilities().flying,player.getStepHeight());
    }
    /** Entity-free adapter retained for movement/arrival regressions. Vanilla's stale onGround
     * flag is deliberately not support authority: the shared kernel probes this exact frame. */
    public static Vec3d move(Index i,Box body,Vec3d wanted,boolean flying,double stepHeight,boolean onGround) {
        return i.move(body,wanted,flying,stepHeight);
    }
    /** Vanilla's carrier-air ledge check would prevent all sneaking; use the real mesh below the future footprint. */
    public static Vec3d sneak(PlayerEntity p,Vec3d wanted,MovementType type) {
        Index i=index(p);
        if(i==null || p.getAbilities().flying || !p.isSneaking() || type!=MovementType.SELF && type!=MovementType.PLAYER)return wanted;
        return i.sneak(p.getBoundingBox(),wanted,p.getStepHeight());
    }
    public static Vec3d sneak(Index i,Box body,Vec3d wanted,double stepHeight) {
        return i.sneak(body,wanted,stepHeight);
    }
    private static double trim(double x){return Math.abs(x)<=.05?0:x-Math.copySign(.05,x);}
    public static Optional<Hit> ground(PlayerEntity player) {
        Index i=index(player);
        return i==null?Optional.empty():i.ground(player.getBoundingBox());
    }
    public static Optional<Hit> ground(Index i,Box body) { return i.ground(body); }
    /** Depth is the full downward sweep from the lifted body (as used to invert contact time).
     * Approach discovery grants no acquisition authority; ordinary contact stays at .15. */
    public static Optional<Hit> ground(PlayerEntity player,double depth) {
        Index i=index(player);return i==null?Optional.empty():ground(player.getBoundingBox(),i,depth);
    }
    public static Optional<Hit> ground(Box body,Index index,double depth) {
        if(!Double.isFinite(depth) || depth<=0)return Optional.empty();
        return index.supportSweep(body,depth);
    }
    public static boolean clearRay(ServerPlayerEntity player,Vec3d from,Vec3d to) {
        Index i=index(player);return i!=null && from.distanceTo(to)<=128 && i.clearRay(from,to);
    }
    /** Actual affine SAT body clearance, including a newly acquired source window. */
    public static boolean bodyClear(TerrainMeshFrame frame,Box body) {
        Box strict=body.expand(-1e-5);
        for(var cell:frame.cells())if(cell.bounds().intersects(strict))
            for(Box collision:cell.collision())if(penetration(strict,new Shape(cell,collision,cell.bounds(collision)))!=null)return false;
        return true;
    }
    /** Continuous affine-motion guard. Endpoint hulls bound EVERY intermediate vertex. A fixed
     * separating axis certifies a whole interval, not just sampled poses; ambiguous intervals are
     * subdivided under a hard budget and still fail closed. This lets a safely touching tilted floor
     * align even when its enclosing world AABB overlaps the body. */
    public static boolean transitionClear(TerrainMeshFrame old,TerrainMeshFrame next,Box body) {
        if(old==null)return true;
        Map<Long,TerrainMeshFrame.Cell> previous=new HashMap<>();for(var c:old.cells())previous.put(c.key(),c);
        Box strict=body.expand(-1e-5);
        for(var c:next.cells()) {
            var before=previous.get(c.key());
            if(before!=null && c.min().equals(before.min()) && c.axisX().equals(before.axisX()) && c.axisY().equals(before.axisY())
                    && c.axisZ().equals(before.axisZ()) && c.collision().equals(before.collision()))continue;
            if(before==null) {for(Box b:c.collision())if(penetration(strict,new Shape(c,b,c.bounds(b)))!=null)return false;continue;}
            if(c.collision().isEmpty())continue;
            for(Box collision:c.collision()) {
                Box swept=c.bounds(collision).union(before.bounds(collision));
                if(!swept.intersects(strict))continue;
                // Only corresponding local shapes have a defined affine trajectory. Topology changes
                // retain the conservative hold rather than guessing a correspondence through the body.
                if(!c.collision().equals(before.collision()) || !affineTransitionClear(
                        AffineBox.of(before,collision),AffineBox.of(c,collision),strict,0,new int[]{128}))return false;
            }
        }
        return true;
    }
    /** Center and half-edges also interpolate linearly, including sheared/fractional local shapes. */
    private record AffineBox(Vec3d center,Vec3d x,Vec3d y,Vec3d z) {
        static AffineBox of(TerrainMeshFrame.Cell c,Box b) {
            return new AffineBox(c.point((b.minX+b.maxX)/2,(b.minY+b.maxY)/2,(b.minZ+b.maxZ)/2),
                    c.axisX().multiply(b.getLengthX()/2),c.axisY().multiply(b.getLengthY()/2),c.axisZ().multiply(b.getLengthZ()/2));
        }
        AffineBox midpoint(AffineBox other) {
            return new AffineBox(center.add(other.center).multiply(.5),x.add(other.x).multiply(.5),
                    y.add(other.y).multiply(.5),z.add(other.z).multiply(.5));
        }
        double radius(Vec3d axis) { return Math.abs(x.dotProduct(axis))+Math.abs(y.dotProduct(axis))+Math.abs(z.dotProduct(axis)); }
    }
    private static final Vec3d[] WORLD_AXES={new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1)};
    private static boolean affineTransitionClear(AffineBox from,AffineBox to,Box body,int depth,int[] budget) {
        if(--budget[0]<0)return false;
        for(Vec3d axis:WORLD_AXES)if(separatesInterval(axis,from,to,body))return true;
        // Endpoint SAT axes are merely candidate directions. Each successful direction must
        // separate BOTH endpoints on the SAME side, thereby certifying their entire convex hull.
        if(separatingAxes(from,from,to,body) || separatingAxes(to,from,to,body))return true;
        if(depth>=16)return false;
        AffineBox mid=from.midpoint(to);
        // No midpoint-clearance shortcut: both complete half-intervals need a certificate.
        return affineTransitionClear(from,mid,body,depth+1,budget)
                && affineTransitionClear(mid,to,body,depth+1,budget);
    }
    private static boolean separatingAxes(AffineBox directions,AffineBox from,AffineBox to,Box body) {
        if(separatesInterval(directions.y.crossProduct(directions.z),from,to,body)
                || separatesInterval(directions.z.crossProduct(directions.x),from,to,body)
                || separatesInterval(directions.x.crossProduct(directions.y),from,to,body))return true;
        for(Vec3d world:WORLD_AXES) {
            if(separatesInterval(world.crossProduct(directions.x),from,to,body)
                    || separatesInterval(world.crossProduct(directions.y),from,to,body)
                    || separatesInterval(world.crossProduct(directions.z),from,to,body))return true;
        }
        return false;
    }
    private static boolean separatesInterval(Vec3d raw,AffineBox from,AffineBox to,Box body) {
        double length=raw.length();if(length<1e-9)return false;
        Vec3d axis=raw.multiply(1/length);
        // Work relative to the body center, avoiding large-world projection cancellation.
        double a=from.center.subtract(body.getCenter()).dotProduct(axis),b=to.center.subtract(body.getCenter()).dotProduct(axis);
        double ra=from.radius(axis),rb=to.radius(axis);
        double r=body.getLengthX()/2*Math.abs(axis.x)+body.getLengthY()/2*Math.abs(axis.y)+body.getLengthZ()/2*Math.abs(axis.z);
        return Math.min(a-ra,b-rb)>r+1e-9 || Math.max(a+ra,b+rb)<-r-1e-9;
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
