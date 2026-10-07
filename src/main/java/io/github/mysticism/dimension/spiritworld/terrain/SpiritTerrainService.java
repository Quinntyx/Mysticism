package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.activity.SpiritActivityService;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Observer-local custom geometry. No placed blocks, global saved anchors or synthetic floors. */
public final class SpiritTerrainService {
    private SpiritTerrainService() {}
    public static final RegistryKey<World> WORLD=RegistryKey.of(RegistryKeys.WORLD,Identifier.of("mysticism","spirit"));
    public static final double SCALE=96;
    public record Support(String landmarkId,Vec384f embedding,Vec3d center,Vec3d normal) {
        public Support { embedding=embedding.clone(); }
        @Override public Vec384f embedding(){return embedding.clone();}
    }
    public record SourcePosition(String dimension,Vec3d position,String landmarkId) {}
    private static final Map<MinecraftServer,Context> SERVERS=new IdentityHashMap<>();
    private static BiConsumer<ServerPlayerEntity,TerrainMeshFrame> transport=(p,f)->{};
    private static boolean initialized;
    public static void init() {
        if(initialized)return;initialized=true;
        ServerTickEvents.END_SERVER_TICK.register(SpiritTerrainService::tick);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p,from,to)->{
            if(from.getRegistryKey().equals(WORLD) && !to.getRegistryKey().equals(WORLD))cancelEnter(p);
            else if(to.getRegistryKey().equals(WORLD)) {
                Session s=session(p);if(s!=null){s.carrier=p.getPos();s.entered=true;publish(p,s,true);}
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(handler.player.getWorld().getRegistryKey().equals(WORLD))restore(handler.player);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->cancelEnter(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->cancelEnter(p));
        ServerWorldEvents.UNLOAD.register((server,world)->{
            Context c=SERVERS.get(server);if(c==null)return;
            if(world.getRegistryKey().equals(WORLD))close(server);
            else for(var p:List.copyOf(server.getPlayerManager().getPlayerList())) {
                Session s=c.sessions.get(p.getUuid());if(s!=null && s.local.dimension.equals(world.getRegistryKey().getValue().toString()))cancelEnter(p);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(SpiritTerrainService::close);
        ServerLifecycleEvents.SERVER_STOPPED.register(SpiritTerrainService::close);
    }
    public static void onFrame(BiConsumer<ServerPlayerEntity,TerrainMeshFrame> listener){transport=Objects.requireNonNull(listener);}
    public static boolean prepareEnter(ServerPlayerEntity player) {
        if(player.getWorld().getRegistryKey().equals(WORLD) || player.getServer().getWorld(WORLD)==null)return false;
        try {
            Context c=SERVERS.computeIfAbsent(player.getServer(),Context::new);
            if(c.sessions.size()>=64 && !c.sessions.containsKey(player.getUuid()))return false;
            cancelEnter(player);
            var w=new Window(player.getServerWorld().getRegistryKey().getValue().toString(),player.getPos(),q(player),basis(player),"");
            captureLoaded(player.getServerWorld(),w,player.getPos(),1331,true);
            w.nodes=SourceMeshBuilder.compact(w.tiles,player.getPos());
            Session s=new Session(w,player.getPos());s.created=c.tick;c.sessions.put(player.getUuid(),s);
            s.frame=build(player,s,0); // actual local mesh exists BEFORE dimension teleport, including all known source shapes
            discoverOwner(player,s);requestSource(player,s,player.getPos(),32);
            return true;
        } catch(RuntimeException failure) {
            cancelEnter(player);player.sendMessage(Text.literal("Spirit source mesh unavailable: "+failure.getMessage()),false);return false;
        }
    }
    /** Rebuild derived meshes from the existing current-profile per-player navigation binding, never a global saved frame. */
    public static boolean restore(ServerPlayerEntity player) {
        if(!player.getWorld().getRegistryKey().equals(WORLD))return false;
        var nav=player.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        if(!nav.active() || nav.sourceDimension().isEmpty())return false;
        if(session(player)!=null)return true;
        try {
            Context c=SERVERS.computeIfAbsent(player.getServer(),Context::new);
            if(c.sessions.size()>=64)return false;
            Window w=new Window(nav.sourceDimension(),nav.sourcePosition(),q(player),basis(player),nav.landmarkId());
            Session s=new Session(w,player.getPos());s.shallow=!nav.deep();s.entered=true;s.created=c.tick;
            LandmarkMetadata owner=nav.landmarkId().isEmpty()?null:c.store.metadata(nav.landmarkId()).orElse(null);
            if(owner!=null){w.owner=owner;w.supportEmbedding=owner.header().baseEmbedding().vector();
                Vec3d offset=w.origin.subtract(owner.header().anchor().x(),owner.header().anchor().y(),owner.header().anchor().z());
                w.semantic=w.supportEmbedding.clone().add(w.sourceBasis.i.clone().mul((float)(offset.x/SCALE)))
                        .add(w.sourceBasis.j.clone().mul((float)(offset.y/SCALE))).add(w.sourceBasis.k.clone().mul((float)(offset.z/SCALE)));}
            ServerWorld source=world(player.getServer(),w.dimension);
            if(source!=null)captureLoaded(source,w,w.origin,1331,true);
            w.nodes=SourceMeshBuilder.compact(w.tiles,w.origin);c.sessions.put(player.getUuid(),s);
            if(owner==null)discoverOwner(player,s);requestSource(player,s,w.origin,32);publish(player,s,true);
            if(nav.hasShallowTarget())prefetchTarget(player,nav.targetDimension(),nav.targetLandmarkId(),nav.targetBlock(),player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(),nav.targetBasis());
            return true;
        } catch(RuntimeException failure){cancelEnter(player);player.sendMessage(Text.literal("Spirit mesh restore deferred: "+failure.getMessage()),false);return false;}
    }
    public static void cancelEnter(ServerPlayerEntity player) {
        Context c=SERVERS.get(player.getServer());if(c==null)return;
        Session s=c.sessions.remove(player.getUuid());if(s!=null)s.cancel();MeshCollision.clear(player.getUuid());
    }
    private static void close(MinecraftServer server){Context c=SERVERS.remove(server);if(c!=null){if(c.read!=null)c.read.cancel();c.sessions.forEach((id,s)->{s.cancel();MeshCollision.clear(id);});}}
    private static Session session(ServerPlayerEntity p){Context c=SERVERS.get(p.getServer());return c==null?null:c.sessions.get(p.getUuid());}
    private static Vec384f q(ServerPlayerEntity p){return p.getComponent(MysticismEntityComponents.LATENT_POS).get().clone();}
    private static Basis384f basis(ServerPlayerEntity p){return p.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone();}
    private static boolean live(ServerPlayerEntity p,Session s){return session(p)==s && p.getServer().getPlayerManager().getPlayer(p.getUuid())==p;}
    public static Optional<TerrainMeshFrame> mesh(ServerPlayerEntity p){Session s=session(p);return s==null?Optional.empty():Optional.ofNullable(s.frame);}
    public static boolean clearRay(ServerPlayerEntity p,Vec3d from,Vec3d to){return MeshCollision.clearRay(p,from,to);}
    public static void setShallow(ServerPlayerEntity p,boolean shallow) {
        Session s=session(p);if(s==null)return;
        if(s.shallow!=shallow){
            if(!shallow) {
                Vec3d delta=p.getPos().subtract(s.carrier);
                s.local.semantic=q(p).sub(s.local.sourceBasis.i.clone().mul((float)(delta.x/SCALE)))
                        .sub(s.local.sourceBasis.j.clone().mul((float)(delta.y/SCALE))).sub(s.local.sourceBasis.k.clone().mul((float)(delta.z/SCALE)));
            }
            s.shallow=shallow;publish(p,s,true);
        }
    }
    public static Optional<SourcePosition> sourcePosition(ServerPlayerEntity p) {
        Session s=session(p);if(s==null || !s.shallow)return Optional.empty();
        Vec3d at=s.local.origin.add(p.getPos().subtract(s.carrier));
        if(s.local.owner!=null && !s.local.owner.header().bounds().contains(MathHelper.floor(at.x),MathHelper.floor(at.y),MathHelper.floor(at.z)))return Optional.empty();
        if(!s.local.tiles.containsKey(BlockPos.ofFloored(at)))return Optional.empty();
        String retained=ownerId(p,s.local);
        if(!s.local.id.isEmpty() && retained.isEmpty())return Optional.empty();
        Optional<Support> grounded=support(p);
        if(grounded.isPresent() && !grounded.get().landmarkId().equals(retained))return Optional.empty();
        return Optional.of(new SourcePosition(s.local.dimension,at,retained));
    }
    /** Coordinate-profile frame: raw q is a LOCATION, not a unit text embedding. Never normalize q. */
    public static Optional<ProjectionFrame> projectionFrame(ServerPlayerEntity p) {
        if(!p.getServer().isOnThread())throw new IllegalStateException("terrain server thread");
        if(session(p)==null)return Optional.empty();
        Basis384f b=basis(p);Vec3d origin=p.getPos();
        return Optional.of(new ProjectionFrame(p.getServer().getTicks(),p.getUuid().getLeastSignificantBits(),coordinate(q(p)),
                new Point3(origin.x,origin.y,origin.z),b.i,b.j,b.k,SCALE));
    }
    /** Projection inputs are semantic coordinates, not necessarily unit text embeddings. */
    public static LandmarkEmbedding coordinate(Vec384f v) {
        var e=LandmarkProfiles.current();return new LandmarkEmbedding(new EmbeddingProfile(e.model(),e.revision(),e.tokenizer(),e.prefixPolicy(),e.dimensions(),EmbeddingProfile.Normalization.NONE,e.descriptorSchema()),v);
    }
    public static Optional<Vec3d> project(ServerPlayerEntity p,Vec384f location) {
        return projectionFrame(p).map(frame->{var point=frame.project(coordinate(location));return new Vec3d(point.x(),point.y(),point.z());});
    }
    private static String ownerId(ServerPlayerEntity p,Window w) {
        if(w.id.isEmpty())return "";
        return LandmarkStore.get(p.getServer()).metadata(w.id).map(LandmarkMetadata::id).orElse("");
    }
    public static Optional<Support> support(ServerPlayerEntity p) {
        Session s=session(p);if(s==null || !p.getWorld().getRegistryKey().equals(WORLD))return Optional.empty();
        return MeshCollision.ground(p).map(hit->{
            String id=hit.cell().landmarkId();Window w=id.equals(s.local.id)?s.local:s.regions.get(id);
            Vec384f embedding=w==null?s.local.supportEmbedding:w.supportEmbedding;
            if(w!=null && w.owner!=null)embedding=LandmarkStore.get(p.getServer()).metadata(w.id)
                    .map(m->SpiritActivityService.effectiveEmbedding(p.getServer(),m).vector()).orElse(w.supportEmbedding);
            Vec3d center=w==null?s.carrier:origin(p,s,w);
            return new Support(w==null?"":ownerId(p,w),embedding,center,hit.normal());
        });
    }
    /** Exit is exact current shallow mapping, never saved entry/spawn or safe-air substitution. */
    public static boolean exit(ServerPlayerEntity p) {
        Optional<SourcePosition> mapped=sourcePosition(p);if(mapped.isEmpty() || !p.getWorld().getRegistryKey().equals(WORLD))return false;
        SourcePosition at=mapped.get();ServerWorld world=world(p.getServer(),at.dimension);
        if(world==null || !clearBody(world,at.position))return false;
        float yaw=p.getYaw(),pitch=p.getPitch();p.teleport(world,at.position.x,at.position.y,at.position.z,yaw,pitch);
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;cancelEnter(p);return true;
    }
    public static void prefetchTarget(ServerPlayerEntity p,String dimension,String landmarkId,BlockPos position,Vec384f captured,Basis384f sourceBasis) {
        Session s=session(p);if(s==null || landmarkId.isEmpty())return;
        if(s.target!=null && s.target.dimension.equals(dimension) && s.target.id.equals(landmarkId)
                && s.target.origin.equals(new Vec3d(position.getX()+.5,position.getY(),position.getZ()+.5))
                && s.target.captured.squareDistance(captured)==0 && s.target.sourceBasis.i.squareDistance(sourceBasis.i)==0
                && s.target.sourceBasis.j.squareDistance(sourceBasis.j)==0 && s.target.sourceBasis.k.squareDistance(sourceBasis.k)==0)return;
        if(s.targetFuture!=null)s.targetFuture.cancel(false);
        Window target=new Window(dimension,new Vec3d(position.getX()+.5,position.getY(),position.getZ()+.5),captured,sourceBasis,landmarkId);s.target=target;
        if(s.targetOwnerFuture!=null)s.targetOwnerFuture.cancel(false);
        s.targetOwnerFuture=SourceLandmarks.ensureSourceLocation(p.getServer(),dimension,position).whenComplete((found,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.target!=target || error!=null || found==null || found.isEmpty())return;
            var expected=LandmarkStore.get(p.getServer()).metadata(landmarkId);
            target.owner=found.get();target.supportEmbedding=SpiritActivityService.effectiveEmbedding(p.getServer(),found.get()).vector();
            target.confirmedOrigin=expected.isPresent() && expected.get().id().equals(found.get().id());
        }));
        s.targetFuture=SourceLandmarks.region(p.getServer(),dimension,SourceMeshBuilder.range(target.origin,12),1728).whenComplete((region,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.target!=target || error!=null)return;
            ingest(p.getServer(),target,region.cells(),1728);target.nodes=SourceMeshBuilder.compact(target.tiles,target.origin);
            target.ready=region.complete();publish(p,s,true);
        }));
    }
    private static boolean currentTarget(ServerPlayerEntity p,Session s) {
        Window w=s.target;if(w==null)return false;
        var current=LandmarkStore.get(p.getServer()).metadata(w.id);
        if(current.isEmpty() || !current.get().header().dimension().equals(w.dimension))return false;
        w.owner=current.get();Vec3d offset=w.origin.subtract(w.owner.header().anchor().x(),w.owner.header().anchor().y(),w.owner.header().anchor().z());
        w.supportEmbedding=SpiritActivityService.effectiveEmbedding(p.getServer(),w.owner).vector();
        w.semantic=w.supportEmbedding.clone().add(w.sourceBasis.i.clone().mul((float)(offset.x/SCALE)))
                .add(w.sourceBasis.j.clone().mul((float)(offset.y/SCALE))).add(w.sourceBasis.k.clone().mul((float)(offset.z/SCALE)));
        return true;
    }
    /** Navigation performs the smooth basis/q approach first. Snapshot guidance is NOT current landmark placement. */
    public static boolean landingReady(ServerPlayerEntity p,String dimension,String landmarkId,BlockPos position) {
        Session s=session(p);if(s==null || s.shallow || s.target==null || !currentTarget(p,s))return false;Window target=s.target;
        if(!target.id.equals(landmarkId) || !target.dimension.equals(dimension) || !BlockPos.ofFloored(target.origin).equals(position)
                || !target.ready || !target.confirmedOrigin || !target.proofValid
                || !target.proofKeys.equals(target.owner.geometryKeys()) || target.semantic.squareDistance(target.captured)>4e-12
                || q(p).squareDistance(target.semantic)>4e-12)return false;
        Basis384f current=basis(p);
        return current.i.squareDistance(target.sourceBasis.i)+current.j.squareDistance(target.sourceBasis.j)+current.k.squareDistance(target.sourceBasis.k)<1e-8;
    }
    public static boolean tryLandTarget(ServerPlayerEntity p,String dimension,String landmarkId,BlockPos position) {
        if(!landingReady(p,dimension,landmarkId,position))return false;
        Session s=session(p);Window target=s.target;
        var metadata=LandmarkStore.get(p.getServer()).metadata(landmarkId);
        if(metadata.isEmpty() || !metadata.get().header().dimension().equals(dimension)
                || !metadata.get().header().bounds().contains(position.getX(),position.getY(),position.getZ()))return false;
        ServerWorld sourceWorld=world(p.getServer(),dimension);
        if(sourceWorld!=null && sourceWorld.isChunkLoaded(position)) {
            captureLoaded(sourceWorld,target,target.origin,1331,true);
            target.nodes=SourceMeshBuilder.compact(target.tiles,target.origin);publish(p,s,true);
            if(!clearBody(sourceWorld,target.origin))return false;
            BlockPos floor=BlockPos.ofFloored(target.origin.add(0,-.05,0));
            if(!sourceWorld.isChunkLoaded(floor) || !contact(sourceWorld.getBlockState(floor).getCollisionShape(sourceWorld,floor),floor,body(target.origin),target.origin.y))return false;
        } else if(!clearCachedBody(target,target.origin) || !hasCachedFloor(target,target.origin))return false;
        publish(p,s,true); // Current basis, exact target source shapes, current q; collision and outgoing rendering agree.
        var ground=MeshCollision.ground(p);
        if(ground.isEmpty() || !ground.get().cell().landmarkId().equals(target.id) || origin(p,s,target).distanceTo(p.getPos())>.001)return false;
        // At subpixel/physics epsilon alignment only. Physical carrier is unchanged; source origin is the exact captured pose.
        if(s.sourceFuture!=null)s.sourceFuture.cancel(false);s.sourceFuture=null;s.ingesting=null;s.sourceRequested=null;
        if(s.ownerFuture!=null)s.ownerFuture.cancel(false);
        s.local=target;s.carrier=p.getPos();s.shallow=true;target.owner=metadata.get();
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;
        publish(p,s,true);return true;
    }
    private static boolean isAir(String block){return block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air");}
    private static ServerWorld world(MinecraftServer server,String dimension){return server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(dimension)));}
    private static boolean clearBody(ServerWorld world,Vec3d at) {
        Box body=body(at);
        for(BlockPos pos:BlockPos.iterate(MathHelper.floor(body.minX),MathHelper.floor(body.minY),MathHelper.floor(body.minZ),MathHelper.floor(body.maxX),MathHelper.floor(body.maxY),MathHelper.floor(body.maxZ))) {
            if(!world.isChunkLoaded(pos))return false;
            for(Box b:world.getBlockState(pos).getCollisionShape(world,pos).getBoundingBoxes())if(b.offset(pos).intersects(body))return false;
        }
        return true;
    }
    private static Box body(Vec3d at){return new Box(at.x-.3,at.y,at.z-.3,at.x+.3,at.y+1.8,at.z+.3);}
    private static boolean clearCachedBody(Window w,Vec3d at) {
        Box body=body(at);
        for(BlockPos pos:BlockPos.iterate(MathHelper.floor(body.minX),MathHelper.floor(body.minY),MathHelper.floor(body.minZ),MathHelper.floor(body.maxX),MathHelper.floor(body.maxY),MathHelper.floor(body.maxZ))) {
            var tile=w.tiles.get(pos);if(tile==null)return false;
            for(Box b:tile.collision())if(b.offset(pos).intersects(body))return false;
        }
        return true;
    }
    private static boolean hasCachedFloor(Window w,Vec3d at) {
        BlockPos pos=BlockPos.ofFloored(at.add(0,-.05,0));var tile=w.tiles.get(pos);if(tile==null)return false;
        return tile.collision().stream().anyMatch(b->contact(b.offset(pos),body(at),at.y));
    }
    // Kept for the existing tiny production-shape smoke fixture, not an overlay lookup.
    static boolean contact(VoxelShape shape,BlockPos pos,Box body,double feet){return shape.getBoundingBoxes().stream().anyMatch(b->contact(b.offset(pos),body,feet));}
    private static boolean contact(Box b,Box body,double feet){return Math.abs(b.maxY-feet)<=.12 && b.maxX>body.minX+1e-4 && b.minX<body.maxX-1e-4 && b.maxZ>body.minZ+1e-4 && b.minZ<body.maxZ-1e-4;}
    private static void discoverOwner(ServerPlayerEntity p,Session s) {
        Window expected=s.local;
        s.ownerFuture=SourceLandmarks.ensureSourceLocation(p.getServer(),expected.dimension,BlockPos.ofFloored(expected.origin)).whenComplete((found,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.local!=expected || error!=null || found==null || found.isEmpty())return;
            Window w=expected;var m=found.get();w.id=m.id();w.owner=m;w.supportEmbedding=m.header().baseEmbedding().vector();
            // Ownership attachment is metadata only. Preserve the exact captured affine mapping, especially if already deep.
            publish(p,s,true);
        }));
    }
    private static void requestSource(ServerPlayerEntity p,Session s,Vec3d source,int side) {
        if(s.sourceFuture!=null && !s.sourceFuture.isDone())return;
        Window w=s.local;s.sourceRequested=source;
        s.sourceFuture=SourceLandmarks.region(p.getServer(),w.dimension,SourceMeshBuilder.range(source,side),side*side*side).whenComplete((region,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.local!=w || error!=null)return;
            s.ingesting=region.cells().iterator();w.ready=region.complete();
        }));
    }
    private static void ingest(MinecraftServer server,Window w,List<SourceLandmarks.Cell> cells,int budget) {
        ServerWorld source=world(server,w.dimension);int n=0;
        for(var cell:cells){if(n++>=budget)break;var point=cell.position();BlockPos at=new BlockPos(Math.toIntExact(point.x()),Math.toIntExact(point.y()),Math.toIntExact(point.z()));
            // A background generated snapshot never overwrites live shape/light/tint with EmptyBlockView/full-bright data.
            var actual=source==null?null:SourceMeshBuilder.read(source,at);
            if(actual!=null)w.tiles.put(at,actual);
            else if(!w.liveTiles.contains(at))w.tiles.put(at,SourceMeshBuilder.stored(cell.material(),at,w.tiles));
        }
        w.dirty=true;
    }
    private static void captureLoaded(ServerWorld world,Window w,Vec3d center,int budget,boolean all) {
        int x=MathHelper.floor(center.x),y=MathHelper.floor(center.y),z=MathHelper.floor(center.z);
        if(all) {
            for(BlockPos p:BlockPos.iterate(x-5,y-5,z-5,x+5,y+5,z+5))readTile(world,w,p);
            return;
        }
        // Refresh actual floor/body first, then a retained bounded sliding window. Changed source blocks are not permanent snapshots.
        int used=0;
        for(BlockPos p:BlockPos.iterate(x-1,y-1,z-1,x+1,y+2,z+1)){if(used++>=budget)return;readTile(world,w,p);}
        while(used++<budget) {
            int index=Math.floorMod(w.refreshCursor++,1331);
            readTile(world,w,new BlockPos(x-5+index%11,y-5+(index/11)%11,z-5+index/121));
        }
    }
    private static void readTile(ServerWorld world,Window w,BlockPos position) {
        var tile=SourceMeshBuilder.read(world,position);
        if(tile!=null){w.liveTiles.add(position.toImmutable());if(!tile.equals(w.tiles.put(position.toImmutable(),tile)))w.dirty=true;}
    }
    private static void tick(MinecraftServer server) {
        Context c=SERVERS.get(server);if(c==null)return;c.tick++;
        var players=server.getPlayerManager().getPlayerList();
        for(var p:players) {
            Session s=c.sessions.get(p.getUuid());if(s==null)continue;
            if(!p.getWorld().getRegistryKey().equals(WORLD)){if(c.tick-s.created>100)cancelEnter(p);continue;}
            try {
                if(s.shallow) {
                    Vec3d source=s.local.origin.add(p.getPos().subtract(s.carrier));
                    ServerWorld sourceWorld=world(server,s.local.dimension);
                    if(sourceWorld!=null)captureLoaded(sourceWorld,s.local,source,64,false);
                    if(s.sourceRequested==null || s.sourceRequested.distanceTo(source)>4)requestSource(p,s,source,16);
                }
                if(s.ingesting!=null){int count=0;while(s.ingesting.hasNext() && count++<256){var cell=s.ingesting.next();ingest(server,s.local,List.of(cell),1);}if(!s.ingesting.hasNext())s.ingesting=null;}
                if(s.local.tiles.size()>32768) {
                    Vec3d center=s.local.origin.add(p.getPos().subtract(s.carrier));
                    s.local.tiles.entrySet().removeIf(e->e.getKey().getSquaredDistance(center)>32*32);
                    s.local.liveTiles.retainAll(s.local.tiles.keySet());
                }
                if(s.local.dirty && c.tick%4==0){Vec3d source=s.local.origin.add(p.getPos().subtract(s.carrier));s.local.nodes=SourceMeshBuilder.compact(s.local.tiles,source);s.local.dirty=false;}
                // Immediate target prewarm is independent of catalog traversal/cluster locks.
                var nav=p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
                if(nav.hasShallowTarget() && s.target==null && q(p).squareDistance(p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target())<MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS)
                    prefetchTarget(p,nav.targetDimension(),nav.targetLandmarkId(),nav.targetBlock(),p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(),nav.targetBasis());
                if(c.tick%2==0)publish(p,s,false);
            } catch(RuntimeException failure){status(p,s,"Mesh deferred: "+failure.getMessage());}
        }
        // A single repository geometry cursor is advanced globally. A failed region never retries ahead of others forever.
        c.advanceGeometry();
        if(!players.isEmpty())for(int n=0;n<Math.min(2,players.size());n++) {
            ServerPlayerEntity p=players.get(Math.floorMod(c.playerCursor++,players.size()));Session s=c.sessions.get(p.getUuid());
            if(s!=null && p.getWorld().getRegistryKey().equals(WORLD))try{c.scan(p,s);}catch(RuntimeException failure){status(p,s,"Selection deferred: "+failure.getMessage());}
        }
    }
    private static void status(ServerPlayerEntity p,Session s,String message){if(!Objects.equals(message,s.status)){s.status=message;p.sendMessage(Text.literal(message),true);}}
    private static Vec3d origin(ServerPlayerEntity p,Session s,Window w) {
        if(w==s.local && s.shallow)return s.carrier;
        Vec384f delta=w.semantic.clone().sub(q(p));Basis384f b=basis(p);
        return p.getPos().add(delta.dot(b.i)*SCALE,delta.dot(b.j)*SCALE,delta.dot(b.k)*SCALE);
    }
    private static TerrainMeshFrame build(ServerPlayerEntity p,Session s,long revision) {
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();Map<TerrainMeshFrame.Material,Integer> palette=new HashMap<>();
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();Set<Long> keys=new HashSet<>();
        if(!s.shallow && s.target!=null && s.target!=s.local && currentTarget(p,s))append(p,s,s.target,materials,palette,cells,keys,512);
        append(p,s,s.local,materials,palette,cells,keys,s.shallow?1024:640);
        // Near shallow view is source-identical, not a pile of unrelated text-similar source regions.
        if(!s.shallow) {
            for(Window w:s.regions.values())if(w!=s.local && w!=s.target && !w.id.equals(s.local.id))append(p,s,w,materials,palette,cells,keys,128);
        }
        return new TerrainMeshFrame(revision,s.shallow,s.local.dimension,s.local.origin,s.carrier,materials,MeshStitcher.stitch(cells,s.local.id,s.shallow));
    }
    private static void append(ServerPlayerEntity p,Session s,Window w,List<TerrainMeshFrame.Material> materials,Map<TerrainMeshFrame.Material,Integer> palette,List<TerrainMeshFrame.Cell> cells,Set<Long> keys,int limit) {
        boolean aligned=w==s.local && s.shallow;Basis384f current=basis(p);Vec3d root=origin(p,s,w);
        Vec3d ax=aligned?new Vec3d(1,0,0):axis(w.sourceBasis.i,current),ay=aligned?new Vec3d(0,1,0):axis(w.sourceBasis.j,current),az=aligned?new Vec3d(0,0,1):axis(w.sourceBasis.k,current);
        int count=0;
        for(var node:w.nodes) {
            if(count>=limit || cells.size()>=TerrainMeshFrame.MAX_CELLS)break;
            var at=node.position();Vec3d source=new Vec3d(at.getX(),at.getY(),at.getZ()),offset=source.subtract(w.origin);
            Vec3d min=root.add(ax.multiply(offset.x)).add(ay.multiply(offset.y)).add(az.multiply(offset.z));
            int side=node.side();Vec3d ex=ax.multiply(side),ey=ay.multiply(side),ez=az.multiply(side);
            Box bounds=new Box(min.x+Math.min(0,ex.x)+Math.min(0,ey.x)+Math.min(0,ez.x),
                    min.y+Math.min(0,ex.y)+Math.min(0,ey.y)+Math.min(0,ez.y),min.z+Math.min(0,ex.z)+Math.min(0,ey.z)+Math.min(0,ez.z),
                    min.x+Math.max(0,ex.x)+Math.max(0,ey.x)+Math.max(0,ez.x),
                    min.y+Math.max(0,ex.y)+Math.max(0,ey.y)+Math.max(0,ez.y),min.z+Math.max(0,ex.z)+Math.max(0,ey.z)+Math.max(0,ez.z));
            double distance=SourceMeshBuilder.distanceSquared(bounds,p.getPos());if(distance>128*128)continue;
            if(Math.abs(ex.dotProduct(ey.crossProduct(ez)))<1e-6)continue; // collapsed model AND collision disappear together
            var tile=node.tile();Integer material=palette.get(tile.material());
            if(material==null){if(materials.size()==TerrainMeshFrame.MAX_MATERIALS)continue;material=materials.size();materials.add(tile.material());palette.put(tile.material(),material);}
            long key=SourceMeshBuilder.key(w.id,source,side);
            if(!keys.add(key))continue;
            float alpha=aligned?1:w.alpha;
            cells.add(new TerrainMeshFrame.Cell(key,material,w.id,source,min,new Vec3d(side,side,side),ex,ey,ez,tile.color(),tile.light(),alpha,tile.collision()));count++;
        }
    }
    private static Vec3d axis(Vec384f source,Basis384f observer){return new Vec3d(source.dot(observer.i),source.dot(observer.j),source.dot(observer.k));}
    private static void publish(ServerPlayerEntity p,Session s,boolean force) {
        TerrainMeshFrame frame=build(p,s,s.revision+1);
        if(!force && s.frame!=null && s.frame.shallow()==frame.shallow() && s.frame.sourceDimension().equals(frame.sourceDimension())
                && s.frame.sourceOrigin().equals(frame.sourceOrigin()) && s.frame.carrierOrigin().equals(frame.carrierOrigin())
                && s.frame.materials().equals(frame.materials()) && s.frame.cells().equals(frame.cells()))return;
        s.frame=frame;s.revision=frame.revision();
        if(p.getWorld().getRegistryKey().equals(WORLD))transport.accept(p,frame);
    }
    private static final class Window {
        final String dimension;Vec3d origin;Vec384f semantic,supportEmbedding;final Vec384f captured;final Basis384f sourceBasis;
        boolean proofValid;List<String> proofKeys=List.of();
        String id;LandmarkMetadata owner;boolean ready,dirty,confirmedOrigin;float alpha=1;int refreshCursor;
        final Set<BlockPos> liveTiles=new HashSet<>();
        final Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();List<SourceMeshBuilder.Node> nodes=List.of();
        Window(String dimension,Vec3d origin,Vec384f semantic,Basis384f basis,String id){this.dimension=dimension;this.origin=origin;this.semantic=semantic.clone();captured=semantic.clone();supportEmbedding=semantic.clone();sourceBasis=basis.clone();this.id=id;}
    }
    private static final class Session {
        Window local,target;Vec3d carrier,sourceRequested;boolean shallow=true,entered;long revision,created;
        TerrainMeshFrame frame;CompletableFuture<?> ownerFuture,sourceFuture,targetFuture,targetOwnerFuture;Iterator<SourceLandmarks.Cell> ingesting;
        final Map<String,Window> regions=new LinkedHashMap<>();final MeshRepresentatives selection=new MeshRepresentatives();
        String scanCursor,status;int dimensionCursor;boolean scanning;
        Session(Window local,Vec3d carrier){this.local=local;this.carrier=carrier;}
        void cancel(){if(ownerFuture!=null)ownerFuture.cancel(false);if(sourceFuture!=null)sourceFuture.cancel(false);if(targetFuture!=null)targetFuture.cancel(false);if(targetOwnerFuture!=null)targetOwnerFuture.cancel(false);regions.clear();}
    }
    private static final class Context {
        final MinecraftServer server;final LandmarkStore store;final Map<UUID,Session> sessions=new HashMap<>();
        long tick;int playerCursor;LandmarkStore.GeometryRead read;Session readingSession;Window readingWindow;
        Iterator<io.github.mysticism.landmark.SparseOctree.Cell<BlockSample>> leaves;GeometryPage page;int readTicks,pagesVisited;
        boolean proving;final Map<BlockPos,BlockPalette.State> proofSamples=new HashMap<>();
        final ArrayDeque<Map.Entry<Session,LandmarkMetadata>> pending=new ArrayDeque<>();
        Context(MinecraftServer server){this.server=server;store=LandmarkStore.get(server);}
        void scan(ServerPlayerEntity p,Session s) {
            List<ServerWorld> worlds=new ArrayList<>();for(var world:server.getWorlds())if(!world.getRegistryKey().equals(WORLD))worlds.add(world);
            if(worlds.isEmpty())return;
            if(!s.scanning){s.selection.begin(q(p),basis(p));s.scanning=true;s.dimensionCursor=0;s.scanCursor=null;}
            ServerWorld world=worlds.get(Math.min(s.dimensionCursor,worlds.size()-1));
            Bounds all=new Bounds(-30000000,world.getBottomY(),-30000000,30000000,world.getTopY(),30000000);
            var batch=store.sourceRangePage(world.getRegistryKey().getValue().toString(),all,s.scanCursor,8,8);s.scanCursor=batch.nextId();
            for(var m:batch.landmarks())s.selection.offer(m,SpiritActivityService.effectiveEmbedding(server,m).vector(),SpiritActivityService.importance(server,m));
            if(batch.end()){s.scanCursor=null;s.dimensionCursor++;}
            if(s.dimensionCursor<worlds.size())return;
            s.scanning=false;Set<String> selected=new HashSet<>();
            for(var m:s.selection.finish()){
                // A long catalog sweep uses a snapshot q; stale candidates cannot activate outside the CURRENT radius.
                if(SpiritActivityService.effectiveEmbedding(server,m).vector().squareDistance(q(p))>MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS)continue;
                selected.add(m.id());if(m.id().equals(s.local.id) || s.regions.containsKey(m.id()))continue;
                if(pending.size()<32 && pending.stream().noneMatch(e->e.getKey()==s && e.getValue().id().equals(m.id())))pending.add(Map.entry(s,m));}
            for(var e:s.regions.entrySet()) {
                Window w=e.getValue();if(w==s.target)continue;
                double distance=origin(p,s,w).distanceTo(p.getPos());
                if(s.frame!=null)for(var cell:s.frame.cells())if(cell.landmarkId().equals(w.id))
                    distance=Math.min(distance,Math.sqrt(SourceMeshBuilder.distanceSquared(cell.bounds(),p.getPos())));
                if(!selected.contains(e.getKey()) && distance>80)w.alpha=Math.max(0,w.alpha-.2f);else w.alpha=Math.min(1,w.alpha+.2f);
            }
            s.regions.entrySet().removeIf(e->e.getValue()!=s.target && e.getValue().alpha<=0);
        }
        void advanceGeometry() {
            try {
                if(read==null) {
                    for(var entry:sessions.entrySet()) {
                        var s=entry.getValue();var p=server.getPlayerManager().getPlayer(entry.getKey());Window target=s.target;
                        if(p==null || target==null || !target.ready || !target.confirmedOrigin || !currentTarget(p,s))continue;
                        if(target.proofKeys.equals(target.owner.geometryKeys()))continue;
                        if(!store.geometryReadAvailable())return;
                        read=store.beginGeometryRead(target.id);readingSession=s;readingWindow=target;proving=true;
                        target.proofValid=false;proofSamples.clear();readTicks=0;pagesVisited=0;break;
                    }
                    while(read==null && !pending.isEmpty()) {
                        var next=pending.removeFirst();Session s=next.getKey();
                        if(!sessions.containsValue(s))continue;
                        LandmarkMetadata m=store.metadata(next.getValue().id()).orElse(null);if(m==null)continue;
                        if(s.regions.size()>=8)continue;
                        Window w=new Window(m.header().dimension(),new Vec3d(m.header().anchor().x(),m.header().anchor().y(),m.header().anchor().z()),SpiritActivityService.effectiveEmbedding(server,m).vector(),s.local.sourceBasis,m.id());
                        w.owner=m;w.supportEmbedding=w.semantic.clone();w.alpha=.2f;
                        read=store.beginGeometryRead(m.id());readingSession=s;readingWindow=w;readTicks=0;pagesVisited=0;break;
                    }
                    if(read==null)return;
                }
                if(!sessions.containsValue(readingSession) || !read.isCurrent()){read.cancel();read=null;leaves=null;return;}
                if(proving) {
                    read.advance(1,256);
                    for(var geometry:read.drain()) {
                        Box area=body(readingWindow.origin);BlockPos floor=BlockPos.ofFloored(readingWindow.origin.add(0,-.05,0));
                        for(BlockPos point:BlockPos.iterate(MathHelper.floor(area.minX),floor.getY(),MathHelper.floor(area.minZ),MathHelper.floor(area.maxX),MathHelper.floor(area.maxY),MathHelper.floor(area.maxZ))) {
                            if(!geometry.bounds().contains(point.getX(),point.getY(),point.getZ()))continue;
                            BlockSample sample=geometry.cells().sample(point.getX(),point.getY(),point.getZ());
                            if(sample!=null)proofSamples.put(point.toImmutable(),geometry.palette().state(sample.paletteIndex()));
                        }
                    }
                    if(read.complete()) {
                        Window target=readingWindow;boolean valid=read.isCurrent();Box area=body(target.origin);
                        for(BlockPos point:BlockPos.iterate(MathHelper.floor(area.minX),MathHelper.floor(area.minY),MathHelper.floor(area.minZ),MathHelper.floor(area.maxX),MathHelper.floor(area.maxY),MathHelper.floor(area.maxZ))) {
                            var material=proofSamples.get(point);if(material==null || !isAir(material.blockId()))valid=false;
                        }
                        var floor=proofSamples.get(BlockPos.ofFloored(target.origin.add(0,-.05,0)));
                        valid &=floor!=null && !isAir(floor.blockId());
                        target.proofKeys=List.copyOf(read.metadata().geometryKeys());target.proofValid=valid;
                        if(valid) {
                            ServerWorld source=world(server,target.dimension);
                            for(var sample:proofSamples.entrySet()) {
                                var actual=source==null?null:SourceMeshBuilder.read(source,sample.getKey());
                                target.tiles.put(sample.getKey(),actual!=null?actual:SourceMeshBuilder.stored(sample.getValue(),sample.getKey(),target.tiles));
                            }
                            target.nodes=SourceMeshBuilder.compact(target.tiles,target.origin);
                        }
                        read=null;proving=false;proofSamples.clear();
                    }
                    return;
                }
                if(++readTicks>32 || pagesVisited>=4 || readingWindow.nodes.size()>=128) {
                    // Bounded geometry window, not a whole-feature cap. Yield even a pathological cold page to other regions.
                    read.cancel();read=null;leaves=null;readingWindow.ready=!readingWindow.nodes.isEmpty();
                    readingSession.regions.put(readingWindow.id,readingWindow);return;
                }
                if(leaves==null){read.advance(1,256);var pages=read.drain();if(!pages.isEmpty()){pagesVisited++;page=pages.getFirst();leaves=page.knownCells().iterator();}}
                int work=0;while(leaves!=null && leaves.hasNext() && work++<256) {
                    var cell=leaves.next();if(cell.value().occupancy()!=BlockSample.Occupancy.SOLID)continue;
                    if(readingWindow.nodes.size()>=128)continue;
                    Bounds b=cell.bounds();var at=new BlockPos(Math.toIntExact(b.minX()),Math.toIntExact(b.minY()),Math.toIntExact(b.minZ()));
                    var tile=SourceMeshBuilder.stored(page.palette().state(cell.value().paletteIndex()),at);
                    var nodes=new ArrayList<>(readingWindow.nodes);nodes.add(new SourceMeshBuilder.Node(at,Math.toIntExact(b.maxX()-b.minX()),tile));readingWindow.nodes=List.copyOf(nodes);
                }
                if(leaves!=null && !leaves.hasNext())leaves=null;
                // Partial pages are published incrementally; any page count works and other requests advance after completion.
                readingSession.regions.put(readingWindow.id,readingWindow);
                if(read.complete() && leaves==null){readingWindow.ready=true;read=null;}
            } catch(RuntimeException deferred) {if(read!=null)read.cancel();read=null;leaves=null;proving=false;proofSamples.clear();}
        }
    }
}
