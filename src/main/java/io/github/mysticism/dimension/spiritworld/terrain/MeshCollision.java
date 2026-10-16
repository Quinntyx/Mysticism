package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.Function;

/** Swept SAT against visible affine mesh cells, shared by server and prediction. Grounding, stepping,
 * sneaking and support all read the actual distorted mesh; vanilla block shapes/flags never stand in. */
public final class MeshCollision {
    private MeshCollision() {}
    private static volatile Function<UUID,TerrainMeshFrame> client = id -> null;
    private static final Map<UUID,Index> SERVER = new java.util.concurrent.ConcurrentHashMap<>(), CLIENT = new java.util.concurrent.ConcurrentHashMap<>();
    public static void clientFrames(Function<UUID,TerrainMeshFrame> provider) { client=Objects.requireNonNull(provider); }
    public static void clear(UUID player) { SERVER.remove(player); CLIENT.remove(player); SERVER_CORRECTIONS.remove(player); CLIENT_CORRECTIONS.remove(player); }
    public static void clearClient() { CLIENT.clear(); CLIENT_CORRECTIONS.clear(); }
    /** Movement-response accounting for the integrators: depenetration displacement applied to this
     *  body since the last drain. Collision corrections are not chosen movement; integration that
     *  folds them in repeats the correction every tick and jitters/rubber-bands. */
    public static Vec3d drainCorrection(PlayerEntity p) { return drainCorrection(p.getUuid(), p.getWorld().isClient); }
    public static Vec3d drainCorrection(UUID player, boolean clientSide) {
        var drained = (clientSide ? CLIENT_CORRECTIONS : SERVER_CORRECTIONS).put(player, Vec3d.ZERO);
        return drained == null ? Vec3d.ZERO : drained;
    }
    public static void recordCorrection(UUID player, boolean clientSide, Vec3d correction) {
        if (player == null || correction.equals(Vec3d.ZERO)) return;
        (clientSide ? CLIENT_CORRECTIONS : SERVER_CORRECTIONS).merge(player, correction, Vec3d::add);
    }
    private static final Map<UUID,Vec3d> SERVER_CORRECTIONS=new java.util.concurrent.ConcurrentHashMap<>(), CLIENT_CORRECTIONS=new java.util.concurrent.ConcurrentHashMap<>();
    private static void recordCorrection(PlayerEntity p,Vec3d correction) { recordCorrection(p.getUuid(),p.getWorld().isClient,correction); }
    public record Hit(double time, Vec3d normal, TerrainMeshFrame.Cell cell) {}
    /** Point lies on the contacted affine shape, not the player's center or a proxy AABB. */
    public record GroundContact(Hit hit, Vec3d point, Vec3d interiorPoint) {}
    /** Player input snapshot shared with entity-free regressions. The vanilla ground flag is an
     * untrusted carrier/packet claim, NOT evidence of support on this observer's mesh. */
    record MotionState(Box body,double stepHeight,boolean flying,boolean sneaking,boolean vanillaOnGround) {
        private static MotionState of(PlayerEntity player) {
            return new MotionState(player.getBoundingBox(),player.getStepHeight(),player.getAbilities().flying,
                    player.isSneaking(),player.isOnGround());
        }
    }
    private record Slide(Vec3d movement,boolean landed) {}
    private static final double SUPPORT_LIFT = .025, GROUND_REACH = .125, WALKABLE_Y = .3;
    private record Bucket(int x,int y,int z) {}
    private record Shape(TerrainMeshFrame.Cell cell,Box local,Box bounds) {}
    public static final class Index {
        public final TerrainMeshFrame frame;
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
        public Optional<GroundContact> groundContact(Box body) {
            Box raised=body.offset(0,.025,0);Vec3d down=new Vec3d(0,-.15,0);
            GroundContact best=null;
            for(Shape shape:nearby(raised.stretch(down).expand(1e-5))) {
                Hit hit=intersect(raised,down,shape);
                // A simultaneous wall contact must not mask a real supporting floor.
                if(hit==null || hit.normal.y<=.3 || best!=null && hit.time>=best.hit.time)continue;
                var point=MeshContact.point(shape.cell,shape.local,raised.offset(down.multiply(hit.time)));
                if(point.isPresent()) {
                    // A fixed world-space inset can cross an entire thin/distorted primitive.
                    // Convex interpolation towards its own center stays inside the contacted shape.
                    Vec3d center=shape.cell.point((shape.local.minX+shape.local.maxX)/2,
                            (shape.local.minY+shape.local.maxY)/2,(shape.local.minZ+shape.local.maxZ)/2);
                    best=new GroundContact(hit,point.get(),point.get().lerp(center,1e-6));
                }
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
        Vec3d depenetrate(Box body) { return depenetrate(body,4); }
        /** Minimal-penetration escape, displacement bounded to `limit` per move. Public for runtime regressions. */
        private static Box tinyBody(Vec3d at){return new Box(at.x-1e-4,at.y-1e-4,at.z-1e-4,at.x+1e-4,at.y+1e-4,at.z+1e-4);}
        /** Minimal-penetration escape, displacement bounded to `limit`. Public for runtime regressions. */
        public Vec3d depenetrate(Box body,double limit) {
            Vec3d moved=Vec3d.ZERO;
            for(int step=0;step<8;step++) {
                Vec3d correction=null;
                for(Shape shape:nearby(body.offset(moved).expand(1e-5))) {
                    Vec3d depth=penetration(body.offset(moved),shape);
                    if(depth!=null && (correction==null || depth.lengthSquared()<correction.lengthSquared()))correction=depth;
                }
                if(correction==null)break;
                if(correction.length()>limit)correction=correction.normalize().multiply(limit);
                moved=moved.add(correction);
                if(moved.length()>limit){moved=moved.normalize().multiply(limit);break;}
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

        private Slide slide(Box body,Vec3d wanted,boolean walking) {
            Vec3d moved=Vec3d.ZERO,remaining=wanted;boolean landed=false;
            for(int i=0;i<4 && remaining.lengthSquared()>1e-12;i++) {
                var hit=sweep(body.offset(moved),remaining);
                if(hit.isEmpty()){moved=moved.add(remaining);break;}
                // A clipped vertical delta alone is not a landing: wall sliding and depenetration
                // can also move the body upward. Keep evidence of an actual up-facing swept contact.
                if(remaining.y<0 && hit.get().normal.y>.3)landed=true;
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
            return new Slide(moved,landed);
        }

        /** Shared server/client movement kernel, independent of vanilla block support. */
        public Vec3d move(Box original,Vec3d wanted,boolean flying,double stepHeight) {
            return moveResolved(this,new MotionState(original,stepHeight,flying,false,false),wanted).movement();
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
        return index(player.getUuid(),frame,player.getWorld().isClient);
    }
    /** Per-player cache resolution shared by live entities and the mesh-isolation regressions. */
    static Index index(UUID id,TerrainMeshFrame frame,boolean clientWorld) {
        if(frame==null)return null;
        var cache=clientWorld?CLIENT:SERVER;
        Index index=cache.get(id);
        if(index==null || index.frame!=frame){index=new Index(frame);cache.put(id,index);}
        return index;
    }
    public static Vec3d move(PlayerEntity player,Vec3d wanted) {
        Resolution result=moveResolved(index(player),MotionState.of(player),wanted);
        recordCorrection(player,result.correction());
        return result.movement();
    }
    /** Same movement path used by server/prediction, without requiring a live entity for tests. */
    static Vec3d move(Index i,MotionState state,Vec3d wanted) {
        return moveResolved(i,state,wanted).movement();
    }
    public static Vec3d predicted(Index index,Box body,Vec3d wanted,boolean flying,boolean sneaking,double step) {
        if(index==null)return wanted;
        if(!flying && sneaking)wanted=index.sneak(body,wanted,step);
        return moveResolved(index,new MotionState(body,step,flying,sneaking,false),wanted).movement();
    }
    public static Vec3d move(Index index,Box body,Vec3d wanted,boolean flying,boolean groundClaim,double step) {
        return moveResolved(index,new MotionState(body,step,flying,false,groundClaim),wanted).movement();
    }
    public static Vec3d simulate(TerrainMeshFrame frame,Box body,Vec3d wanted,boolean flying,boolean groundClaim,double step) {
        return move(new Index(frame),body,wanted,flying,groundClaim,step);
    }
    /** The correction must belong to the SELECTED path, including step-up, not just the direct sweep. */
    record Resolution(Vec3d movement,Vec3d correction,boolean landed) {}
    static Resolution moveResolved(Index i,MotionState state,Vec3d wanted) {
        // No fabricated floor. Preparation supplies geometry before entry; packet delay simply has no surfaces yet.
        if(i==null)return new Resolution(wanted,Vec3d.ZERO,false);
        if(wanted.length()>256)wanted=wanted.multiply(256/wanted.length());
        Box body=state.body();
        boolean walking=!state.flying() && i.ground(body.offset(i.depenetrate(body,4))).isPresent() && wanted.y<=0;
        Resolution direct=resolveMotion(i,body,wanted,walking,state.flying()?.5:4);
        Vec3d applied=direct.correction(),result=direct.movement();
        Box from=body.offset(applied);
        // Only real mesh support (after depenetration) or a genuine swept landing grants step-up.
        // A vanilla ground claim or an upward wall slide is never evidence of support.
        if(!state.flying() && (supported(i,from) || direct.landed())
                && result.subtract(wanted).horizontalLengthSquared()>1e-8)
            result=stepUp(i,from,applied,wanted,state.stepHeight(),result,walking);
        return new Resolution(result,applied,direct.landed());
    }
    /** Vanilla-style up/over/down selection starts at the depenetrated body and carries the
     * applied correction into the selected displacement for exact movement-response accounting. */
    public static Vec3d stepUp(Index i,Box from,Vec3d applied,Vec3d wanted,double step,Vec3d direct) {
        return stepUp(i,from,applied,wanted,step,direct,i.ground(from).isPresent() && wanted.y<=0);
    }
    private static Vec3d stepUp(Index i,Box from,Vec3d applied,Vec3d wanted,double step,Vec3d direct,boolean walking) {
        if(step<=0)return direct;
        Vec3d up=i.slide(from,new Vec3d(0,step,0),false).movement();
        Vec3d over=i.slide(from.offset(up),new Vec3d(wanted.x,0,wanted.z),walking).movement();
        Vec3d down=i.slide(from.offset(up).offset(over),new Vec3d(0,wanted.y-up.y,0),walking).movement();
        Vec3d stepped=applied.add(up).add(over).add(down);
        return stepped.horizontalLengthSquared()>direct.horizontalLengthSquared()+1e-8?stepped:direct;
    }
    /** Pure swept resolution shared by move(): reports depenetration separately from chosen travel. */
    public static Vec3d resolve(Index i,Box body,Vec3d wanted,Vec3d[] correction) {
        Resolution result=resolveMotion(i,body,wanted,false);
        if(correction!=null && correction.length>0)correction[0]=result.correction();
        return result.movement();
    }
    private static Resolution resolveMotion(Index i,Box body,Vec3d wanted,boolean walking) {
        return resolveMotion(i,body,wanted,walking,4);
    }
    private static Resolution resolveMotion(Index i,Box body,Vec3d wanted,boolean walking,double escapeLimit) {
        Vec3d applied=i.depenetrate(body,escapeLimit),result=applied;
        // Bound swept traversal for unusually fast flight/teleports rather than tunnelling through a grid overflow.
        int pieces=Math.min(64,Math.max(1,(int)Math.ceil(wanted.length()/4)));
        Vec3d piece=wanted.multiply(1.0/pieces);boolean landed=false;
        for(int p=0;p<pieces;p++) {
            Slide slide=i.slide(body.offset(result),piece,walking);
            result=result.add(slide.movement());landed|=slide.landed();
        }
        return new Resolution(result,applied,landed);
    }
    /** Unspent requested displacement on one axis, never reversing against the request. */
    private static double remaining(double spent,double wanted) {
        return wanted>=0?Math.max(0,wanted-spent):Math.min(0,wanted-spent);
    }
    /** Vanilla's carrier-air ledge check would prevent all sneaking; use the real mesh below the future footprint. */
    public static Vec3d sneak(PlayerEntity p,Vec3d wanted,MovementType type) {
        return sneak(index(p),MotionState.of(p),wanted,type);
    }
    static Vec3d sneak(Index i,MotionState state,Vec3d wanted,MovementType type) {
        if(i==null || state.flying() || !state.sneaking() || !supported(i,state.body())
                || type!=MovementType.SELF && type!=MovementType.PLAYER)return wanted;
        return i.sneak(state.body(),wanted,state.stepHeight());
    }
    /** Compatibility adapters use the same mesh authority, never a vanilla ground flag. */
    public static Vec3d move(Index i,Box body,Vec3d wanted,boolean flying,double stepHeight,boolean onGround) {
        return move(i,new MotionState(body,stepHeight,flying,false,onGround),wanted);
    }
    public static Vec3d sneak(Index i,Box body,Vec3d wanted,double stepHeight) {return i.sneak(body,wanted,stepHeight);}
    private static double trim(double x){return Math.abs(x)<=.05?0:x-Math.copySign(.05,x);}
    private static final double GROUND_RISE=.025,GROUND_DROP=.15;
    /** Source readiness resolves the same footprint as the real mesh grounding probe. */
    static Box supportFootprint(Box body) {
        return body.union(body.offset(0,GROUND_RISE,0).stretch(new Vec3d(0,-GROUND_DROP,0)));
    }
    public static Optional<GroundContact> groundContact(PlayerEntity player) {
        Index i=index(player);return i==null?Optional.empty():i.groundContact(player.getBoundingBox());
    }
    /** Replay accepted displacement without applying depenetration twice. */
    public static Vec3d replayClaimed(Index i,Box body,Vec3d claimed,boolean flying) {
        Vec3d correction=i.depenetrate(body,flying?.5:4);
        return resolveMotion(i,body,claimed.subtract(correction),!flying && supported(i,body.offset(correction)),flying?.5:4).movement();
    }
    /** Actual mesh support contact, shared by the player adapter and entity-free regressions. */
    public static Optional<Hit> ground(Index index,Box body) {
        return index.ground(body);
    }
    public static Optional<Hit> ground(PlayerEntity player) {
        Index i=index(player);
        return i==null?Optional.empty():i.ground(player.getBoundingBox());
    }
    /** Depth is the full downward sweep from the lifted body (as used to invert contact time).
     * Approach discovery grants no acquisition authority; ordinary contact stays at .15. */
    public static Optional<Hit> ground(PlayerEntity player,double depth) {
        Index i=index(player);return i==null?Optional.empty():ground(player.getBoundingBox(),i,depth);
    }
    public static Optional<Hit> ground(Box body,Index index,double depth) {
        if(!Double.isFinite(depth) || depth<=0)return Optional.empty();
        return index.supportSweep(body,depth);
    }
    /** Mesh-verdict grounded state for movement gates; the vanilla flag is a client claim on the server. */
    public static boolean supported(Index index,Box body){return ground(index,body).isPresent();}
    public static boolean clearRay(ServerPlayerEntity player,Vec3d from,Vec3d to) {
        Index i=index(player);return i!=null && from.distanceTo(to)<=128 && i.clearRay(from,to);
    }
    /** No carrier blocks, entity boxes or world border may substitute for observer-local terrain. */
    public static boolean bodyClear(PlayerEntity player,Box body) {
        Index i=index(player);
        return i==null || bodyClear(i.frame,body);
    }
    /** Actual affine SAT body clearance, including shapes extending outside the unit model. */
    public static boolean bodyClear(TerrainMeshFrame frame,Box body) {
        Box strict=body.expand(-1e-5);
        for(var cell:frame.cells())if(cell.opacity()>0)
            for(Box collision:cell.collision()) {
                Box bounds=cell.bounds(collision);
                if(bounds.intersects(strict) && penetration(strict,new Shape(cell,collision,bounds))!=null)return false;
            }
        return true;
    }
    /** Bound for a claimed destination's shallow overlap that the client's own next collision tick resolves. */
    static final double PENETRATION_TOLERANCE=1.0/16;
    /** Server movement verdict for a claimed destination, in the vanilla isPlayerNotCollidingWithBlocks
     * polarity: TRUE rejects the claim and teleports the player back to the server pose, FALSE accepts it.
     * The spirit dimension is a void world, where vanilla ALWAYS teleports whenever straight-line server
     * re-simulation of a claim disagrees by more than 0.25 blocks; re-simulation of swept mesh walking
     * (slopes, steps above step height, late input, frame latency) routinely diverges that far, so every
     * such correction rubber-bands the player. This verdict instead accepts every collision-clear endpoint
     * — client and server resolve the same shared sweep math — still rejects genuinely penetrating claims
     * (server authority retained), tolerates only sub-tick overlap the client settles next tick, and
     * accepts recovery claims from an already-overlapping body instead of teleport-oscillating inside
     * moving geometry. */
    public static boolean movementRejected(TerrainMeshFrame frame,Box previous,Box destination) {
        if(frame==null)return false;
        if(bodyClear(frame,destination))return false;
        if(!bodyClear(frame,previous))return false;
        return new Index(frame).depenetrate(destination).length()>PENETRATION_TOLERANCE;
    }
    /** Server-session frame lookup plus the shared verdict. */
    public static boolean movementRejected(PlayerEntity player,Box previous,Box destination) {
        Index i=index(player);
        return i!=null && movementRejected(i.frame,previous,destination);
    }
    /** Vanilla's packet endpoint guard rejects NEW intersections, not an existing overlap being escaped.
     * Movement itself is already swept by move(); retain that guard without rebuilding block voxels. */
    public static boolean newlyColliding(PlayerEntity player,Box before,Box after) {
        Index i=index(player);
        return i!=null && newlyColliding(i.frame,before,after);
    }
    public static boolean newlyColliding(TerrainMeshFrame frame,Box before,Box after) {
        Box oldBody=before.expand(-1e-5),newBody=after.expand(-1e-5);
        for(var cell:frame.cells())if(cell.opacity()>0)
            for(Box collision:cell.collision()) {
                Shape shape=new Shape(cell,collision,cell.bounds(collision));
                if(shape.bounds.intersects(newBody) && penetration(newBody,shape)!=null
                        && penetration(oldBody,shape)==null)return true;
            }
        return false;
    }
    /** Continuous affine-motion guard. Endpoint AABBs are broad phase only; the narrow phase
     * certifies a separating plane over the entire interpolation, never just sampled endpoints. */
    public static boolean transitionClear(TerrainMeshFrame old,TerrainMeshFrame next,Box body) {
        return transitionClear(old,next,body,Vec3d.ZERO);
    }
    /** Same guard, aware of how far the OBSERVER moved since the old frame was accepted. A cell that
     * translated exactly with the observer (teleport re-anchor, observer-following re-root) keeps the
     * accepted relative relationship to the body and cannot sweep through it; only true surface motion,
     * or geometry appearing near the body, is held. */
    public static boolean transitionClear(TerrainMeshFrame old,TerrainMeshFrame next,Box body,Vec3d observerDelta) {
        return transitionClear(old,next,body,observerDelta,Set.of());
    }
    /** Aligned shallow cells follow the accepted CARRIER, not ordinary walking since frame acceptance.
     * Other producers (including deep representatives retained in a shallow view) still follow the observer.
     * Callers supply only keys with the same local producer in both accepted and candidate frames. */
    static boolean transitionClear(TerrainMeshFrame old,TerrainMeshFrame next,Box body,Vec3d observerDelta,Set<Long> shallowLocalKeys) {
        if(old==null)return true;
        Vec3d carrierDelta=next.carrierOrigin().subtract(old.carrierOrigin());
        Map<Long,TerrainMeshFrame.Cell> previous=new HashMap<>();for(var c:old.cells())previous.put(c.key(),c);
        Box strict=body.expand(-1e-5);
        for(var c:next.cells()) {
            if(c.opacity()<=0)continue;
            var before=previous.get(c.key());
            if(before!=null && before.opacity()<=0)before=null;
            if(before!=null && c.min().equals(before.min()) && c.axisX().equals(before.axisX()) && c.axisY().equals(before.axisY())
                    && c.axisZ().equals(before.axisZ()) && c.collision().equals(before.collision()))continue;
            if(before!=null && c.axisX().equals(before.axisX()) && c.axisY().equals(before.axisY())
                    && c.axisZ().equals(before.axisZ()) && c.collision().equals(before.collision())
                    && rigidWith(c.min().subtract(before.min()),old.shallow() && next.shallow() && shallowLocalKeys.contains(c.key())
                            ?carrierDelta:observerDelta))continue;
            // Do not cull by endpoint locality: a distant-to-distant surface may cross the body.
            // The swept bounds below are the broad phase for true affine surface motion.
            if(before==null) {for(Box b:c.collision())if(penetration(strict,new Shape(c,b,c.bounds(b)))!=null)return false;continue;}
            if(c.collision().isEmpty())continue;
            for(Box collision:c.collision()) {
                Box swept=c.bounds(collision).union(before.bounds(collision));
                if(!swept.intersects(strict))continue;
                if(!c.collision().equals(before.collision()))return false;
                Shape from=new Shape(before,collision,before.bounds(collision)),to=new Shape(c,collision,c.bounds(collision));
                if(penetration(strict,from)!=null || penetration(strict,to)!=null)return false;
                if(!affineSweepClear(strict,AffineSolid.of(from),AffineSolid.of(to),0,new int[]{MAX_AFFINE_INTERVALS}))return false;
            }
        }
        return true;
    }
    /** A local collision box expressed as a center and three world-space half edges. */
    private record AffineSolid(Vec3d center,Vec3d x,Vec3d y,Vec3d z) {
        static AffineSolid of(Shape shape) {
            var c=shape.cell;var b=shape.local;
            return new AffineSolid(c.point((b.minX+b.maxX)/2,(b.minY+b.maxY)/2,(b.minZ+b.maxZ)/2),
                    c.axisX().multiply(b.getLengthX()/2),c.axisY().multiply(b.getLengthY()/2),c.axisZ().multiply(b.getLengthZ()/2));
        }
        AffineSolid midpoint(AffineSolid other) {
            return new AffineSolid(center.add(other.center).multiply(.5),x.add(other.x).multiply(.5),
                    y.add(other.y).multiply(.5),z.add(other.z).multiply(.5));
        }
        double radius(Vec3d axis) {
            return Math.abs(x.dotProduct(axis))+Math.abs(y.dotProduct(axis))+Math.abs(z.dotProduct(axis));
        }
    }
    private static final int MAX_AFFINE_INTERVALS=256,MAX_AFFINE_DEPTH=16;
    private static final double SWEEP_CLEARANCE=1e-7;
    private static final Vec3d[] WORLD_AXES={new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1)};
    private static boolean affineSweepClear(Box body,AffineSolid from,AffineSolid to,int depth,int[] budget) {
        // Bound both recursion depth and total work per shape. Unproven motion still fails closed.
        if(--budget[0]<0)return false;
        for(Vec3d axis:WORLD_AXES)if(separatesSweep(body,from,to,axis))return true;
        if(sweepAxesSeparate(body,from,to,from) || sweepAxesSeparate(body,from,to,to))return true;
        if(rotatingFaceSeparates(body,from,to))return true;
        if(depth>=MAX_AFFINE_DEPTH)return false;
        AffineSolid middle=from.midpoint(to);
        return affineSweepClear(body,from,middle,depth+1,budget) && affineSweepClear(body,middle,to,depth+1,budget);
    }
    /** Cubic Bernstein coefficients bound each signed plane distance throughout the interval.
     * A moving face can separate a resting body even when no single fixed plane can. */
    private static boolean positiveCubic(java.util.function.DoubleUnaryOperator value,double tolerance) {
        double a=value.applyAsDouble(0),b=value.applyAsDouble(1.0/3),c=value.applyAsDouble(2.0/3),d=value.applyAsDouble(1);
        return Math.min(Math.min(a,d),Math.min((18*b-9*c-5*a+2*d)/6,(-9*b+18*c+2*a-5*d)/6))>tolerance;
    }
    private static boolean rotatingFaceSeparates(Box body,AffineSolid from,AffineSolid to) {
        Vec3d[] f={from.x,from.y,from.z},t={to.x,to.y,to.z};
        double sign=Math.signum(f[0].dotProduct(f[1].crossProduct(f[2])));
        if(sign==0 || !positiveCubic(u->f[0].lerp(t[0],u).dotProduct(
                f[1].lerp(t[1],u).crossProduct(f[2].lerp(t[2],u)))*sign,1e-12))return false;
        for(int axis=0;axis<3;axis++)for(int side:new int[]{-1,1}) {
            int i=axis,j=(axis+1)%3,k=(axis+2)%3;
            double normalBound=Math.max(f[j].length(),t[j].length())*Math.max(f[k].length(),t[k].length());
            boolean separate=true;
            for(int corner=0;corner<8 && separate;corner++) {
                Vec3d point=new Vec3d((corner&1)==0?body.minX:body.maxX,
                        (corner&2)==0?body.minY:body.maxY,(corner&4)==0?body.minZ:body.maxZ);
                separate=positiveCubic(u-> {
                    Vec3d face=from.center.lerp(to.center,u).add(f[i].lerp(t[i],u).multiply(side));
                    Vec3d normal=f[j].lerp(t[j],u).crossProduct(f[k].lerp(t[k],u)).multiply(side*sign);
                    return point.subtract(face).dotProduct(normal);
                },SWEEP_CLEARANCE*normalBound);
            }
            if(separate)return true;
        }
        return false;
    }
    private static boolean sweepAxesSeparate(Box body,AffineSolid from,AffineSolid to,AffineSolid axes) {
        if(separatesSweep(body,from,to,axes.y.crossProduct(axes.z))
                || separatesSweep(body,from,to,axes.z.crossProduct(axes.x))
                || separatesSweep(body,from,to,axes.x.crossProduct(axes.y)))return true;
        for(Vec3d world:WORLD_AXES)
            if(separatesSweep(body,from,to,world.crossProduct(axes.x))
                    || separatesSweep(body,from,to,world.crossProduct(axes.y))
                    || separatesSweep(body,from,to,world.crossProduct(axes.z)))return true;
        return false;
    }
    private static boolean separatesSweep(Box body,AffineSolid from,AffineSolid to,Vec3d raw) {
        double length=raw.length();if(length<1e-9)return false;
        Vec3d axis=raw.multiply(1/length),center=body.getCenter();
        double radius=body.getLengthX()/2*Math.abs(axis.x)+body.getLengthY()/2*Math.abs(axis.y)+body.getLengthZ()/2*Math.abs(axis.z);
        double a=from.center.subtract(center).dotProduct(axis),b=to.center.subtract(center).dotProduct(axis);
        double ar=from.radius(axis),br=to.radius(axis);
        // The SAME side/plane must separate both endpoints. Positive clearance avoids numerical
        // acceptance of an ambiguous grazing interval; strict body shrink is the contact tolerance.
        return a-ar>radius+SWEEP_CLEARANCE && b-br>radius+SWEEP_CLEARANCE
                || a+ar< -radius-SWEEP_CLEARANCE && b+br< -radius-SWEEP_CLEARANCE;
    }
    /** Exact rigid translation (floating-point tolerant): the cell moved with the observer, not across it. */
    private static boolean rigidWith(Vec3d motion,Vec3d observerDelta) {
        return Math.abs(motion.x-observerDelta.x)<1e-6 && Math.abs(motion.y-observerDelta.y)<1e-6
                && Math.abs(motion.z-observerDelta.z)<1e-6;
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
