package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.activity.SpiritActivityService;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.navigation.SpiritNavigationService;
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
    private static final java.util.concurrent.atomic.AtomicLong WINDOW_IDS=new java.util.concurrent.atomic.AtomicLong();
    private static BiConsumer<ServerPlayerEntity,TerrainMeshFrame> transport=(p,f)->{};
    public interface SourceAnchorListener { void anchor(ServerPlayerEntity player,SourcePosition source,Basis384f sourceBasis); }
    private static SourceAnchorListener anchorListener=(p,source,grid)->{};
    public static void onSourceAnchor(SourceAnchorListener listener){anchorListener=Objects.requireNonNull(listener);}
    private static boolean initialized;
    public static void init() {
        if(initialized)return;initialized=true;
        SpiritNavigationService.installLandingSafety(new SpiritNavigationService.LandingSafety() {
            public boolean canAlign(ServerPlayerEntity p,Basis384f proposed){return SpiritTerrainService.canAlign(p,proposed);}
            public boolean ready(ServerPlayerEntity p,String dimension,String id,BlockPos point){return landingReady(p,dimension,id,point);}
            @Override public boolean ready(ServerPlayerEntity p,String dimension,String id,Vec3d point){return landingReady(p,dimension,id,point);}
        });
        onSourceAnchor(SpiritNavigationService::anchorSource);
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
            w.nodes=compact(player.getServer(),w,player.getPos());
            Session s=new Session(w,player.getPos());s.created=c.tick;c.sessions.put(player.getUuid(),s);
            BuiltMesh initial=buildMesh(player,s,0,basis(player));s.frame=initial.frame();s.frameProducers=initial.producers(); // actual local mesh exists BEFORE dimension teleport
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
            w.nodes=compact(player.getServer(),w,w.origin);s.anchorDelivered=nav.semanticReady();c.sessions.put(player.getUuid(),s);
            if(owner==null)discoverOwner(player,s);requestSource(player,s,w.origin,32);publish(player,s,true);
            if(nav.hasShallowTarget())prefetchTarget(player,nav.targetDimension(),nav.targetLandmarkId(),nav.targetPosition(),player.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(),nav.targetBasis());
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
        if(!s.local.tiles.containsKey(BlockPos.ofFloored(at)))return Optional.empty();
        Optional<String> actual=exactOwner(p,s,s.local,at);
        if(actual.isEmpty()) {
            // Initial native shallow view does not require a pre-known semantic feature. This grants NO support/exit authority.
            if(!s.anchorDelivered && (s.local.id.isEmpty() || ownershipPending(p)))return Optional.of(new SourcePosition(s.local.dimension,at,""));
            return Optional.empty();
        }
        String retained=ownerId(p,s.local);
        if(!retained.isEmpty() && !actual.get().equals(retained))return Optional.empty();
        Optional<Support> grounded=support(p);
        if(grounded.isPresent() && !grounded.get().landmarkId().equals(actual.get()))return Optional.empty();
        return Optional.of(new SourcePosition(s.local.dimension,at,actual.get()));
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
        return MeshCollision.ground(p).flatMap(hit->{
            var cell=hit.cell();Window w=windowFor(s,cell);
            if(w==null)return Optional.empty();
            // Invert the SAME deformed cell used by rendering/SAT, then sample inside the producing source voxel.
            Vec3d contact=p.getPos().add(0,.025-.15*hit.time(),0).subtract(hit.normal().multiply(.001));
            Vec3d source=sourceContact(cell,contact);Optional<String> id=exactOwner(p,s,w,source);
            if(id.isEmpty())return Optional.empty();
            var metadata=LandmarkStore.get(p.getServer()).metadata(id.get());if(metadata.isEmpty())return Optional.empty();
            var anchor=metadata.get().header().anchor();
            Vec3d center=cell.point((anchor.x()-cell.sourceMin().x)/cell.size().x,(anchor.y()-cell.sourceMin().y)/cell.size().y,(anchor.z()-cell.sourceMin().z)/cell.size().z);
            return Optional.of(new Support(metadata.get().id(),SpiritActivityService.effectiveEmbedding(p.getServer(),metadata.get()).vector(),center,hit.normal()));
        });
    }
    /** Exit is exact current shallow mapping, never saved entry/spawn or safe-air substitution. */
    public record WalkSupport(String landmarkId,String sourceDimension,Vec3d sourcePosition,
            Basis384f sourceBasis,Vec384f sourceCoordinate,Vec3d normal,long windowIdentity,long ownershipRevision) {
        public WalkSupport{sourceBasis=sourceBasis.clone();sourceCoordinate=sourceCoordinate.clone();}
        @Override public Basis384f sourceBasis(){return sourceBasis.clone();}
        @Override public Vec384f sourceCoordinate(){return sourceCoordinate.clone();}
    }
    private record WalkCandidate(Window field,WalkSupport support) {}
    /** Current visible contact only; no target attraction, q replacement or remembered-entry rebinding. */
    public static Optional<WalkSupport> currentSupport(ServerPlayerEntity p) {return walkCandidate(p).map(WalkCandidate::support);}
    private static Optional<WalkCandidate> walkCandidate(ServerPlayerEntity p) {
        Session s=session(p);
        if(s==null || s.shallow || !MysticismEntityComponents.SPIRIT_NAVIGATION.get(p).semanticReady())return Optional.empty();
        var hit=MeshCollision.ground(p);if(hit.isEmpty())return Optional.empty();
        var cell=hit.get().cell();Window w=windowFor(s,cell);if(w==null)return Optional.empty();
        Vec3d contact=p.getPos().add(0,.025-.15*hit.get().time(),0).subtract(hit.get().normal().multiply(.001));
        Optional<String> owner=exactOwner(p,s,w,sourceContact(cell,contact));if(owner.isEmpty())return Optional.empty();
        if(!owner.get().equals(ownerId(p,w)))return Optional.empty();
        Vec3d feet=sourcePoint(cell,p.getPos());if(!ownedBody(p,s,w,feet,owner.get()))return Optional.empty();
        Vec3d offset=feet.subtract(w.origin);Vec384f coordinate=w.semantic.clone()
                .add(w.sourceBasis.i.clone().mul((float)(offset.x/SCALE)))
                .add(w.sourceBasis.j.clone().mul((float)(offset.y/SCALE)))
                .add(w.sourceBasis.k.clone().mul((float)(offset.z/SCALE)));
        WalkSupport support=new WalkSupport(owner.get(),w.dimension,feet,w.sourceBasis,coordinate,hit.get().normal(),w.identity,w.owners.ownershipRevision());
        ensureWalkProof(p,s,w,support);return Optional.of(new WalkCandidate(w,support));
    }
    private static boolean ownedBody(ServerPlayerEntity p,Session s,Window w,Vec3d at,String id) {
        Box b=body(at);
        for(BlockPos pos:BlockPos.iterate(MathHelper.floor(b.minX),MathHelper.floor(b.minY),MathHelper.floor(b.minZ),MathHelper.floor(b.maxX),MathHelper.floor(b.maxY),MathHelper.floor(b.maxZ)))
            if(!exactOwner(p,s,w,new Vec3d(pos.getX(),pos.getY(),pos.getZ())).filter(id::equals).isPresent())return false;
        return true;
    }
    public static void cancelCurrentSupport(ServerPlayerEntity p) {
        Session s=session(p);if(s==null)return;if(s.walkFuture!=null)s.walkFuture.cancel(false);
        s.walkProbe=null;s.walkFuture=null;s.walkField=0;s.walkNextProbe=0;
    }
    private static void ensureWalkProof(ServerPlayerEntity p,Session s,Window field,WalkSupport hint) {
        Window probe=s.walkProbe;var owner=LandmarkStore.get(p.getServer()).metadata(hint.landmarkId());
        boolean failed=s.walkFuture!=null && s.walkFuture.isCompletedExceptionally();
        if(probe!=null && !failed && s.walkField==field.identity && probe.id.equals(hint.landmarkId())
                && probe.owner!=null && owner.isPresent() && probe.owner.geometryKeys().equals(owner.get().geometryKeys())
                && probe.origin.squaredDistanceTo(hint.sourcePosition())<4) {probe.owners=field.owners;return;}
        Context context=SERVERS.get(p.getServer());if(context==null || context.tick<s.walkNextProbe)return;s.walkNextProbe=context.tick+20;
        if(s.walkFuture!=null)s.walkFuture.cancel(false);
        probe=new Window(hint.sourceDimension(),hint.sourcePosition(),hint.sourceCoordinate(),hint.sourceBasis(),hint.landmarkId());
        probe.owner=LandmarkStore.get(p.getServer()).metadata(probe.id).orElse(null);probe.owners=field.owners;
        s.walkProbe=probe;s.walkField=field.identity;Window expected=probe;
        var future=SourceLandmarks.region(p.getServer(),probe.dimension,SourceMeshBuilder.range(probe.origin,12),1728);s.walkFuture=future;
        future.whenComplete((region,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.walkProbe!=expected || s.walkFuture!=future || error!=null || region==null)return;
            ingest(p.getServer(),expected,region.cells(),1728);expected.ready=true; // Acquisition still requires every body/floor sample, not unknown outer cells.
        }));
    }
    public static boolean acquireCurrentSupport(ServerPlayerEntity p) {
        var current=currentSupport(p);return current.isPresent() && acquireCurrentSupport(p,current.get());
    }
    public static boolean acquireCurrentSupport(ServerPlayerEntity p,WalkSupport expected) {
        var candidate=walkCandidate(p);if(candidate.isEmpty())return false;
        Session s=session(p);WalkSupport current=candidate.get().support();Window field=candidate.get().field(),probe=s.walkProbe;
        if(expected.windowIdentity()!=current.windowIdentity() || !expected.landmarkId().equals(current.landmarkId())
                || expected.ownershipRevision()!=current.ownershipRevision() || current.normal().y<.99
                || probe==null || !probe.ready || s.walkField!=field.identity || probe.owner==null
                || !LandmarkStore.get(p.getServer()).metadata(probe.id).map(m->m.geometryKeys().equals(probe.owner.geometryKeys())).orElse(false))return false;
        Basis384f b=basis(p),source=current.sourceBasis();
        if(b.i.squareDistance(source.i)+b.j.squareDistance(source.j)+b.k.squareDistance(source.k)>1e-8)return false;
        Vec384f error=current.sourceCoordinate().sub(q(p));
        if(new Vec3d(error.dot(b.i)*SCALE,error.dot(b.j)*SCALE,error.dot(b.k)*SCALE).lengthSquared()>1e-6)return false;
        ServerWorld world=world(p.getServer(),probe.dimension);
        if(world!=null)captureLoaded(world,probe,current.sourcePosition(),200,false);
        if(!clearCachedBody(probe,current.sourcePosition()) || !hasCachedFloor(probe,current.sourcePosition()))return false;
        Window next=new Window(current.sourceDimension(),current.sourcePosition(),q(p),source,current.landmarkId());
        next.owner=LandmarkStore.get(p.getServer()).metadata(next.id).orElse(null);if(next.owner==null)return false;
        next.owners=field.owners;next.geometryNodes=field.geometryNodes.isEmpty()?field.nodes:field.geometryNodes;
        // Acquiring a shallow view must not forget this source window's negative observations
        // and expose its old persisted geometry. Clone bounded state; never share mutable masks.
        next.tiles.putAll(field.tiles);next.liveTiles.addAll(field.liveTiles);next.negative.copyFrom(field.negative);
        enforceRetention(next,current.sourcePosition());
        for(var sample:probe.tiles.entrySet())if(!next.putTile(sample.getKey(),sample.getValue(),probe.liveTiles.contains(sample.getKey())))return false;
        if(!clearCachedBody(next,current.sourcePosition()) || !hasCachedFloor(next,current.sourcePosition()))return false;
        next.nodes=compact(p.getServer(),next,next.origin);next.ready=true;
        Session preview=new Session(next,p.getPos());preview.shallow=true;preview.acquiredView=true;preview.revision=s.revision;
        preview.regions.putAll(s.regions);preview.target=s.target;preview.closestId=s.closestId;
        if(!s.local.id.isEmpty() && !s.local.id.equals(next.id))preview.regions.putIfAbsent(s.local.id,s.local);
        BuiltMesh built=buildMesh(p,preview,s.revision+1,b);TerrainMeshFrame frame=built.frame();
        if(!MeshCollision.bodyClear(frame,p.getBoundingBox()) || !MeshCollision.transitionClear(s.frame,frame,p.getBoundingBox()))return false;
        // All validation precedes publication. No q/basis/position/velocity/attunement mutation.
        Window previous=s.local;Vec3d previousCarrier=s.carrier;
        boolean previousShallow=s.shallow,previousAcquired=s.acquiredView,previousAnchor=s.anchorDelivered;
        Map<String,Window> previousRegions=new LinkedHashMap<>(s.regions);
        s.local=next;s.carrier=p.getPos();s.shallow=true;s.acquiredView=true;s.anchorDelivered=true;
        s.regions.clear();s.regions.putAll(preview.regions);
        try {acceptFrame(p,s,built);}
        catch(RuntimeException failure) {
            s.local=previous;s.carrier=previousCarrier;s.shallow=previousShallow;s.acquiredView=previousAcquired;s.anchorDelivered=previousAnchor;
            s.regions.clear();s.regions.putAll(previousRegions);
            status(p,s,"Walking acquisition held: mesh publication failed.");return false;
        }
        s.ingesting=null;s.sourceRequested=null;
        if(s.sourceFuture!=null)s.sourceFuture.cancel(false);if(s.ownerFuture!=null)s.ownerFuture.cancel(false);
        if(previous.ownershipFuture!=null)previous.ownershipFuture.cancel(false);
        s.walkProbe=null;s.walkField=0;if(s.walkFuture!=null)s.walkFuture.cancel(false);
        confirmTargetFade(s);return true;
    }
    public static boolean exit(ServerPlayerEntity p) {
        Optional<SourcePosition> mapped=sourcePosition(p);if(mapped.isEmpty() || !p.getWorld().getRegistryKey().equals(WORLD))return false;
        SourcePosition at=mapped.get();if(at.landmarkId().isEmpty())return false; // Pending native entry view is not permission to materialize.
        ServerWorld world=world(p.getServer(),at.dimension);
        if(world==null || !clearBody(world,at.position))return false;
        float yaw=p.getYaw(),pitch=p.getPitch();p.teleport(world,at.position.x,at.position.y,at.position.z,yaw,pitch);
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;cancelEnter(p);return true;
    }
    private static Vec3d blockFeet(BlockPos position){return new Vec3d(position.getX()+.5,position.getY(),position.getZ()+.5);}
    public static void prefetchTarget(ServerPlayerEntity p,String dimension,String landmarkId,BlockPos position,Vec384f captured,Basis384f sourceBasis) {
        prefetchTarget(p,dimension,landmarkId,blockFeet(position),captured,sourceBasis);
    }
    public static void prefetchTarget(ServerPlayerEntity p,String dimension,String landmarkId,Vec3d position,Vec384f captured,Basis384f sourceBasis) {
        Session s=session(p);if(s==null || landmarkId.isEmpty())return;
        if(s.target!=null && s.target.dimension.equals(dimension) && s.target.id.equals(landmarkId)
                && s.target.origin.equals(position)
                && s.target.captured.squareDistance(captured)==0 && s.target.sourceBasis.i.squareDistance(sourceBasis.i)==0
                && s.target.sourceBasis.j.squareDistance(sourceBasis.j)==0 && s.target.sourceBasis.k.squareDistance(sourceBasis.k)==0)return;
        if(s.targetFuture!=null)s.targetFuture.cancel(false);
        Window target=new Window(dimension,position,captured,sourceBasis,landmarkId);target.alpha=0;s.target=target;
        if(s.targetOwnerFuture!=null)s.targetOwnerFuture.cancel(false);
        s.targetOwnerFuture=SourceLandmarks.ensureSourceLocation(p.getServer(),dimension,BlockPos.ofFloored(position)).whenComplete((found,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.target!=target || error!=null || found==null || found.isEmpty())return;
            var expected=LandmarkStore.get(p.getServer()).metadata(landmarkId);
            target.owner=found.get();target.supportEmbedding=SpiritActivityService.effectiveEmbedding(p.getServer(),found.get()).vector();
            target.confirmedOrigin=expected.isPresent() && expected.get().id().equals(found.get().id());
        }));
        s.targetFuture=SourceLandmarks.region(p.getServer(),dimension,SourceMeshBuilder.range(target.origin,12),1728).whenComplete((region,error)->p.getServer().execute(()->{
            if(!live(p,s) || s.target!=target || error!=null)return;
            ingest(p.getServer(),target,region.cells(),1728);target.nodes=compact(p.getServer(),target,target.origin);
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
        return landingReady(p,dimension,landmarkId,blockFeet(position));
    }
    public static boolean landingReady(ServerPlayerEntity p,String dimension,String landmarkId,Vec3d position) {
        Session s=session(p);if(s==null || s.shallow || s.target==null || !currentTarget(p,s))return false;Window target=s.target;
        if(!target.id.equals(landmarkId) || !target.dimension.equals(dimension) || !target.origin.equals(position)
                || target.publishedAlpha<.99f || !target.ready || !target.confirmedOrigin || !target.proofValid
                || !target.proofKeys.equals(target.owner.geometryKeys()) || target.semantic.squareDistance(target.captured)>4e-12
                || q(p).squareDistance(target.semantic)>4e-12)return false;
        Basis384f current=basis(p);
        return current.i.squareDistance(target.sourceBasis.i)+current.j.squareDistance(target.sourceBasis.j)+current.k.squareDistance(target.sourceBasis.k)<1e-8;
    }
    public static boolean tryLandTarget(ServerPlayerEntity p,String dimension,String landmarkId,BlockPos position) {
        return tryLandTarget(p,dimension,landmarkId,blockFeet(position));
    }
    public static boolean tryLandTarget(ServerPlayerEntity p,String dimension,String landmarkId,Vec3d position) {
        if(!landingReady(p,dimension,landmarkId,position))return false;
        Session s=session(p);Window target=s.target;
        var metadata=LandmarkStore.get(p.getServer()).metadata(landmarkId);
        if(metadata.isEmpty() || !metadata.get().header().dimension().equals(dimension)
                || !metadata.get().header().bounds().contains(MathHelper.floor(position.x),MathHelper.floor(position.y),MathHelper.floor(position.z)))return false;
        ServerWorld sourceWorld=world(p.getServer(),dimension);
        if(sourceWorld!=null && sourceWorld.isChunkLoaded(BlockPos.ofFloored(position))) {
            captureLoaded(sourceWorld,target,target.origin,1331,true);
            target.nodes=compact(p.getServer(),target,target.origin);publish(p,s,true);
            if(!clearBody(sourceWorld,target.origin))return false;
            BlockPos floor=BlockPos.ofFloored(target.origin.add(0,-.05,0));
            if(!sourceWorld.isChunkLoaded(floor) || !contact(sourceWorld.getBlockState(floor).getCollisionShape(sourceWorld,floor),floor,body(target.origin),target.origin.y))return false;
        } else if(!clearCachedBody(target,target.origin) || !hasCachedFloor(target,target.origin))return false;
        if(!publish(p,s,true))return false; // A held transition is not permission to land on the previous frame.
        var ground=MeshCollision.ground(p);
        if(ground.isEmpty() || !ground.get().cell().landmarkId().equals(target.id) || origin(p,s,target).distanceTo(p.getPos())>.001)return false;
        // At subpixel/physics epsilon alignment only. Physical carrier is unchanged; source origin is the exact captured pose.
        Window previous=s.local;Vec3d previousCarrier=s.carrier;
        s.local=target;s.carrier=p.getPos();s.shallow=true;target.owner=metadata.get();
        if(!publish(p,s,true)){s.local=previous;s.carrier=previousCarrier;s.shallow=false;return false;}
        if(s.sourceFuture!=null)s.sourceFuture.cancel(false);s.sourceFuture=null;s.ingesting=null;s.sourceRequested=null;
        if(s.ownerFuture!=null)s.ownerFuture.cancel(false);
        p.setVelocity(Vec3d.ZERO);p.fallDistance=0;return true;
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
            // Attach ownership without moving geometry. Deliver NAV anchoring only after exact octree ownership is current.
            Vec3d source=s.shallow?w.origin.add(p.getPos().subtract(s.carrier)):w.origin;
            requestOwnership(p,s,w,source);notifyAnchor(p,s,w);publish(p,s,true);
        }));
    }
    /** NAV may wait on this transient async state instead of treating an invalidated snapshot as walking off a region. */
    public static boolean ownershipPending(ServerPlayerEntity p) {
        Session s=session(p);if(s==null)return false;Window w=s.local;
        Vec3d at=s.shallow?w.origin.add(p.getPos().subtract(s.carrier)):w.origin;
        return w.owners==null || !w.owners.isCurrent(p.getServer()) || !w.owners.bounds().contains(MathHelper.floor(at.x),MathHelper.floor(at.y),MathHelper.floor(at.z));
    }
    private static Optional<String> exactOwner(ServerPlayerEntity p,Session s,Window w,Vec3d source) {
        BlockPos point=BlockPos.ofFloored(source);
        if(w.owners!=null && w.owners.isCurrent(p.getServer()) && w.owners.bounds().contains(point.getX(),point.getY(),point.getZ()))return w.owners.ownerAt(point);
        requestOwnership(p,s,w,source);return Optional.empty();
    }
    private static void requestOwnership(ServerPlayerEntity p,Session s,Window w,Vec3d source) {
        Context c=SERVERS.get(p.getServer());if(c==null || c.tick<w.nextOwnerRequest || w.ownershipFuture!=null && !w.ownershipFuture.isDone())return;
        w.nextOwnerRequest=c.tick+20;
        var future=SourceLandmarks.owners(p.getServer(),w.dimension,SourceMeshBuilder.range(source,16),4096);w.ownershipFuture=future;
        future.whenComplete((region,error)->p.getServer().execute(()->{
            if(!live(p,s) || w.ownershipFuture!=future)return;
            if(error!=null || region==null || !region.isCurrent(p.getServer()))return;
            w.owners=region;
            if(!w.tiles.isEmpty() || !w.negative.isEmpty())w.nodes=compact(p.getServer(),w,source);
            else if(!w.geometryNodes.isEmpty())w.nodes=SourceMeshBuilder.splitOwnership(w.geometryNodes,region.owners(),region.bounds(),source);
            notifyAnchor(p,s,w);publish(p,s,true);
        }));
    }
    private static void notifyAnchor(ServerPlayerEntity p,Session s,Window w) {
        if(w!=s.local || s.anchorDelivered || w.owners==null || !w.owners.isCurrent(p.getServer()))return;
        Vec3d source=s.shallow?w.origin.add(p.getPos().subtract(s.carrier)):w.origin;
        String id=w.owners.ownerAt(BlockPos.ofFloored(source)).orElse("");
        if(id.isEmpty() || !id.equals(ownerId(p,w)))return;
        Vec384f before=q(p);anchorListener.anchor(p,new SourcePosition(w.dimension,source,id),w.sourceBasis.clone());
        w.semantic.add(q(p).sub(before));s.anchorDelivered=true;publish(p,s,true);
    }
    private static List<SourceMeshBuilder.Node> compact(MinecraftServer server,Window w,Vec3d center) {
        Map<BlockPos,String> owners=w.owners!=null && w.owners.isCurrent(server)?w.owners.owners():Map.of();
        w.compactedOwnership=w.owners!=null && w.owners.isCurrent(server)?w.owners.ownershipRevision():-1;
        List<SourceMeshBuilder.Node> near=SourceMeshBuilder.compact(w.tiles,center,owners);
        if(w.geometryNodes.isEmpty())return near;
        // Retained AIR masks also suppress stale base geometry, including after tile eviction/stream restart.
        return SourceMeshBuilder.replaceNear(w.geometryNodes,near,w.tiles.keySet(),w.negative,center);
    }
    private static Window windowFor(Session s,TerrainMeshFrame.Cell cell) {
        // Server-local provenance belongs to the accepted frame, not mutable ownership or latest compaction.
        // Held frames keep their original producer even after source cells are relabeled or a new stream starts.
        return s.frameProducers.get(cell.key());
    }
    private static Vec3d sourcePoint(TerrainMeshFrame.Cell c,Vec3d world) {
        Vec3d v=world.subtract(c.min());double det=c.axisX().dotProduct(c.axisY().crossProduct(c.axisZ()));
        return c.sourceMin().add(v.dotProduct(c.axisY().crossProduct(c.axisZ()))/det*c.size().x,
                v.dotProduct(c.axisZ().crossProduct(c.axisX()))/det*c.size().y,v.dotProduct(c.axisX().crossProduct(c.axisY()))/det*c.size().z);
    }
    private static Vec3d sourceContact(TerrainMeshFrame.Cell c,Vec3d world) {
        Vec3d v=world.subtract(c.min());double det=c.axisX().dotProduct(c.axisY().crossProduct(c.axisZ()));
        double x=v.dotProduct(c.axisY().crossProduct(c.axisZ()))/det,y=v.dotProduct(c.axisZ().crossProduct(c.axisX()))/det,z=v.dotProduct(c.axisX().crossProduct(c.axisY()))/det;
        return c.sourceMin().add(Math.max(0,Math.min(1-1e-6,x))*c.size().x,Math.max(0,Math.min(1-1e-6,y))*c.size().y,Math.max(0,Math.min(1-1e-6,z))*c.size().z);
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
            if(actual!=null)w.putTile(at,actual,true); // live reads also supersede old negative observations
            else if(!w.liveTiles.contains(at))w.putTile(at,SourceMeshBuilder.stored(cell.material(),at,w.tiles),false);
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
        if(tile!=null)w.putTile(position.toImmutable(),tile,true);
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
                enforceRetention(s.local,s.local.origin.add(p.getPos().subtract(s.carrier)));
                enforceSessionRetention(s);
                if(s.local.budgetPaused)status(p,s,"Discovery budget full: new samples deferred; retained AIR still suppresses stale terrain.");
                if(s.local.dirty && c.tick%4==0){Vec3d source=s.local.origin.add(p.getPos().subtract(s.carrier));s.local.nodes=compact(server,s.local,source);s.local.dirty=false;}
                // Immediate target prewarm is independent of catalog traversal/cluster locks.
                var nav=p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
                if(nav.hasShallowTarget() && s.target==null && q(p).squareDistance(p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target())<MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS)
                    prefetchTarget(p,nav.targetDimension(),nav.targetLandmarkId(),nav.targetPosition(),p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(),nav.targetBasis());
                c.animate(p,s);if(c.tick%2==0)publish(p,s,false);
            } catch(RuntimeException failure){status(p,s,"Mesh deferred: "+failure.getMessage());}
        }
        // A single repository geometry cursor is advanced globally. A failed region never retries ahead of others forever.
        c.advanceNear();c.advanceGeometry();
        if(!players.isEmpty())for(int n=0;n<Math.min(2,players.size());n++) {
            ServerPlayerEntity p=players.get(Math.floorMod(c.playerCursor++,players.size()));Session s=c.sessions.get(p.getUuid());
            if(s!=null && p.getWorld().getRegistryKey().equals(WORLD))try{c.maintain(p,s);c.scan(p,s);}catch(RuntimeException failure){status(p,s,"Selection deferred: "+failure.getMessage());}
        }
    }
    /** Bounded farthest-first retention. Never a single all-at-once radius cut: the exact support radius is
     *  protected first, work is capped per pass, and discovered coverage survives until genuine memory pressure. */
    private static void enforceRetention(Window w,Vec3d focus) {
        var victims=DiscoveryBudget.evictionPlan(w.tiles.size(),w.tiles.keySet(),focus,
                pos->w.tiles.get(pos).air(),pos->!w.tiles.get(pos).air() || w.negative.canRecord(pos));
        if(victims.isEmpty())return;
        for(BlockPos p:victims) {
            if(w.tiles.get(p).air()) {
                if(!w.negative.canRecord(p))continue; // earlier victims may have filled the last mask bucket
                w.negative.add(p);
            }
            w.tiles.remove(p);w.liveTiles.remove(p);w.dirty=true;
        }
        if(w.tiles.size()<DiscoveryBudget.MAX_STAGED_TILES)w.budgetPaused=false;
    }
    /** Retention is scheduled for EVERY retained window each tick, not only s.local: commit-time passes
     *  alone (512 of up to 4096 merged samples) cannot keep pace with sustained near-patch commits, and
     *  nonlocal windows would otherwise grow without bound. */
    private static void enforceSessionRetention(Session s) {
        if(s.target!=null)enforceRetention(s.target,s.target.nearFocus==null?s.target.origin:s.target.nearFocus);
        for(var w:s.regions.values())enforceRetention(w,w.nearFocus==null?w.origin:w.nearFocus);
        for(var w:s.prepared.values())enforceRetention(w,w.nearFocus==null?w.origin:w.nearFocus);
    }
    private static void status(ServerPlayerEntity p,Session s,String message){if(!Objects.equals(message,s.status)){s.status=message;p.sendMessage(Text.literal(message),true);}}
    private static Vec3d origin(ServerPlayerEntity p,Session s,Window w) {
        if(w==s.local && s.shallow)return s.carrier;
        Vec384f delta=w.semantic.clone().sub(q(p));Basis384f b=basis(p);
        return p.getPos().add(delta.dot(b.i)*SCALE,delta.dot(b.j)*SCALE,delta.dot(b.k)*SCALE);
    }
    public static boolean canAlign(ServerPlayerEntity p,Basis384f proposed) {
        Session s=session(p);return s!=null && MeshCollision.transitionClear(s.frame,build(p,s,s.revision+1,proposed),p.getBoundingBox());
    }
    private static TerrainMeshFrame build(ServerPlayerEntity p,Session s,long revision) {return build(p,s,revision,basis(p));}
    private record BuiltMesh(TerrainMeshFrame frame,Map<Long,Window> producers) {}
    private static TerrainMeshFrame build(ServerPlayerEntity p,Session s,long revision,Basis384f observer) {
        return buildMesh(p,s,revision,observer).frame();
    }
    private static BuiltMesh buildMesh(ServerPlayerEntity p,Session s,long revision,Basis384f observer) {
        Map<Long,Window> producers=new HashMap<>();
        refreshCompaction(p,s.local);if(s.target!=null)refreshCompaction(p,s.target);for(Window w:s.regions.values())refreshCompaction(p,w);
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();Map<TerrainMeshFrame.Material,Integer> palette=new HashMap<>();
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();Set<Long> keys=new HashSet<>();
        if((!s.shallow || s.acquiredView) && s.target!=null && s.target!=s.local && currentTarget(p,s)
                && s.target.proofValid && s.target.proofKeys.equals(s.target.owner.geometryKeys())
                && s.target.semantic.squareDistance(q(p))<MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS)append(p,s,s.target,materials,palette,cells,keys,producers,512,observer);
        append(p,s,s.local,materials,palette,cells,keys,producers,s.shallow?1024:640,observer);
        // Near shallow view is source-identical, not a pile of unrelated text-similar source regions.
        if(!s.shallow || s.acquiredView) {
            Window closest=s.regions.get(s.closestId);
            if(closest!=null && closest!=s.local && closest!=s.target)append(p,s,closest,materials,palette,cells,keys,producers,512,observer);
            for(Window w:s.regions.values())if(w!=closest && w!=s.local && w!=s.target && !w.id.equals(s.local.id))append(p,s,w,materials,palette,cells,keys,producers,128,observer);
        }
        var stitched=MeshStitcher.stitch(cells,materials,s.local.id,s.shallow,p.getPos());
        Set<Long> retained=new HashSet<>();for(var cell:stitched)retained.add(cell.key());producers.keySet().retainAll(retained);
        return new BuiltMesh(new TerrainMeshFrame(revision,s.shallow,s.local.dimension,s.local.origin,s.carrier,materials,stitched),Map.copyOf(producers));
    }
    private static void refreshCompaction(ServerPlayerEntity p,Window w) {
        if(w.tiles.isEmpty())return;
        long epoch=w.owners!=null && w.owners.isCurrent(p.getServer())?w.owners.ownershipRevision():-1;
        if(w.compactedOwnership!=epoch)w.nodes=compact(p.getServer(),w,w.origin);
    }
    private static void append(ServerPlayerEntity p,Session s,Window w,List<TerrainMeshFrame.Material> materials,Map<TerrainMeshFrame.Material,Integer> palette,List<TerrainMeshFrame.Cell> cells,Set<Long> keys,Map<Long,Window> producers,int limit,Basis384f current) {
        boolean aligned=w==s.local && s.shallow;Vec3d root;
        if(aligned)root=s.carrier;else {Vec384f delta=w.semantic.clone().sub(q(p));root=p.getPos().add(delta.dot(current.i)*SCALE,delta.dot(current.j)*SCALE,delta.dot(current.k)*SCALE);}
        Vec3d ax=aligned?new Vec3d(1,0,0):axis(w.sourceBasis.i,current),ay=aligned?new Vec3d(0,1,0):axis(w.sourceBasis.j,current),az=aligned?new Vec3d(0,0,1):axis(w.sourceBasis.k,current);
        for(var cell:MeshPublication.append(w.nodes,w.dimension,w.origin,root,ax,ay,az,p.getPos(),aligned?1:w.alpha,
                limit,materials,palette,cells,keys))producers.put(cell.key(),w);
    }
    private static Vec3d axis(Vec384f source,Basis384f observer){return new Vec3d(source.dot(observer.i),source.dot(observer.j),source.dot(observer.k));}
    private static boolean publish(ServerPlayerEntity p,Session s,boolean force) {
        BuiltMesh built=buildMesh(p,s,s.revision+1,basis(p));TerrainMeshFrame frame=built.frame();
        if(!force && s.frame!=null && s.frame.shallow()==frame.shallow() && s.frame.sourceDimension().equals(frame.sourceDimension())
                && s.frame.sourceOrigin().equals(frame.sourceOrigin()) && s.frame.carrierOrigin().equals(frame.carrierOrigin())
                && s.frame.materials().equals(frame.materials()) && s.frame.cells().equals(frame.cells())) {
            s.frameProducers=built.producers();confirmTargetFade(s);return true;
        }
        if(!MeshCollision.transitionClear(s.frame,frame,p.getBoundingBox())) {
            status(p,s,"Terrain transition held: moving surface intersects your body; move clear to continue.");return false;
        }
        acceptFrame(p,s,built);confirmTargetFade(s);return true;
    }
    /** All runtime frame replacements commit provenance together and roll both back on transport failure. */
    private static void acceptFrame(ServerPlayerEntity p,Session s,BuiltMesh built) {
        TerrainMeshFrame previous=s.frame;Map<Long,Window> previousProducers=s.frameProducers;long previousRevision=s.revision;
        s.frame=built.frame();s.frameProducers=built.producers();s.revision=s.frame.revision();
        try {if(p.getWorld().getRegistryKey().equals(WORLD))transport.accept(p,s.frame);}
        catch(RuntimeException failure){s.frame=previous;s.frameProducers=previousProducers;s.revision=previousRevision;throw failure;}
    }
    private static void confirmTargetFade(Session s) {
        Window target=s.target;if(target==null || target==s.local)return;
        float visible=1;boolean found=false;
        for(var cell:s.frame.cells())if(windowFor(s,cell)==target){visible=Math.min(visible,cell.opacity());found=true;}
        // Stitching can retain an already-opaque overlapping skin. It must not skip our requested fade steps.
        target.publishedAlpha=found?Math.min(target.alpha,visible):0;
    }
    private static final class Window {
        final long identity=WINDOW_IDS.incrementAndGet();final String dimension;Vec3d origin;Vec384f semantic,supportEmbedding;final Vec384f captured;final Basis384f sourceBasis;
        boolean proofValid;List<String> proofKeys=List.of();SourceOwnership.Region owners;CompletableFuture<SourceOwnership.Region> ownershipFuture;long nextOwnerRequest;
        String id;LandmarkMetadata owner;boolean ready,dirty,confirmedOrigin,replacing,nearReady,retired;float alpha=1,publishedAlpha;int refreshCursor;
        TerrainGeometryStream stream;long metadataChecked,observedOwnership=-1;
        Vec3d nearFocus;Bounds nearCoverage;List<String> nearKeys=List.of();boolean nearComplete;Window nearSamples;CompletableFuture<SourceLandmarks.Region> nearFuture;Iterator<SourceLandmarks.Cell> nearCells;
        final Set<BlockPos> liveTiles=new HashSet<>();final NegativeCoverage negative=new NegativeCoverage();boolean budgetPaused;
        boolean putTile(BlockPos at,SourceMeshBuilder.Tile tile,boolean live) {
            var result=DiscoveryBudget.admit(tiles,liveTiles,negative,at,tile,live);
            if(result==DiscoveryBudget.Admission.FULL)budgetPaused=true;
            if(result==DiscoveryBudget.Admission.UPDATED)dirty=true;
            return result==DiscoveryBudget.Admission.UPDATED || result==DiscoveryBudget.Admission.UNCHANGED;
        }
        final Map<BlockPos,SourceMeshBuilder.Tile> tiles=new HashMap<>();List<SourceMeshBuilder.Node> nodes=List.of(),geometryNodes=List.of();long compactedOwnership=-1;
        Window(String dimension,Vec3d origin,Vec384f semantic,Basis384f basis,String id){this.dimension=dimension;this.origin=origin;this.semantic=semantic.clone();captured=semantic.clone();supportEmbedding=semantic.clone();sourceBasis=basis.clone();this.id=id;}
    }
    private static final class Session {
        Window local,target,walkProbe;Vec3d carrier,sourceRequested;boolean shallow=true,entered,anchorDelivered,acquiredView;long revision,created,walkField,walkNextProbe;
        TerrainMeshFrame frame;Map<Long,Window> frameProducers=Map.of();CompletableFuture<?> ownerFuture,sourceFuture,targetFuture,targetOwnerFuture,walkFuture;Iterator<SourceLandmarks.Cell> ingesting;
        final Map<String,Window> regions=new LinkedHashMap<>();final MeshRepresentatives selection=new MeshRepresentatives();
        final Map<String,Window> prepared=new LinkedHashMap<>();
        String scanCursor,status,closestId="";int dimensionCursor,regionCursor;boolean scanning;Set<String> selected=Set.of();
        Session(Window local,Vec3d carrier){this.local=local;this.carrier=carrier;}
        void cancel(){if(ownerFuture!=null)ownerFuture.cancel(false);if(sourceFuture!=null)sourceFuture.cancel(false);if(targetFuture!=null)targetFuture.cancel(false);if(targetOwnerFuture!=null)targetOwnerFuture.cancel(false);if(walkFuture!=null)walkFuture.cancel(false);cancelWindow(local);if(target!=null)cancelWindow(target);for(var w:regions.values())cancelWindow(w);for(var w:prepared.values())cancelWindow(w);regions.clear();prepared.clear();}
    }
    private static void cancelWindow(Window w){if(w.ownershipFuture!=null)w.ownershipFuture.cancel(false);if(w.nearFuture!=null)w.nearFuture.cancel(false);w.stream=null;w.nearCells=null;w.nearSamples=null;}
    private static final class Context {
        final MinecraftServer server;final LandmarkStore store;final Map<UUID,Session> sessions=new HashMap<>();
        long tick;int playerCursor;LandmarkStore.GeometryRead read;Session readingSession;Window readingWindow;
        int geometryCursor,nearCursor;

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
            s.scanning=false;Set<String> selected=new HashSet<>();s.closestId="";
            for(var m:s.selection.finish()){
                // A long catalog sweep uses a snapshot q; stale candidates cannot activate outside the CURRENT radius.
                if(SpiritActivityService.effectiveEmbedding(server,m).vector().squareDistance(q(p))>MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS)continue;
                selected.add(m.id());if(s.closestId.isEmpty())s.closestId=m.id();
                if(m.id().equals(s.local.id) || s.regions.containsKey(m.id()) || s.prepared.containsKey(m.id()))continue;
                if(pending.size()<32 && pending.stream().noneMatch(e->e.getKey()==s && e.getValue().id().equals(m.id())))pending.add(Map.entry(s,m));}
            s.selected=Set.copyOf(selected);
            s.regions.entrySet().removeIf(e->{if(e.getValue().alpha>0 || protectedNear(p,s,e.getValue()))return false;cancelWindow(e.getValue());return true;});
            s.prepared.entrySet().removeIf(e->{if(selected.contains(e.getKey()))return false;cancelWindow(e.getValue());return true;});
        }
        private List<Window> windows(Session s) {
            var all=new LinkedHashSet<Window>();all.add(s.local);if(s.target!=null)all.add(s.target);all.addAll(s.regions.values());all.addAll(s.prepared.values());return List.copyOf(all);
        }
        private boolean retained(Session s,Window w){return sessions.containsValue(s) && (s.local==w || s.target==w || s.regions.get(w.id)==w || s.prepared.get(w.id)==w);}
        private record View(Vec3d root,Vec3d x,Vec3d y,Vec3d z,Vec3d source,Vec3d viewer) {
            double distance(SourceMeshBuilder.Node node) {
                BlockPos at=node.position();Vec3d d=new Vec3d(at.getX(),at.getY(),at.getZ()).subtract(source);
                Vec3d min=root.add(x.multiply(d.x)).add(y.multiply(d.y)).add(z.multiply(d.z));
                int side=node.side();Vec3d a=x.multiply(side),b=y.multiply(side),c=z.multiply(side);
                Box bounds=new Box(min.x+Math.min(0,a.x)+Math.min(0,b.x)+Math.min(0,c.x),min.y+Math.min(0,a.y)+Math.min(0,b.y)+Math.min(0,c.y),min.z+Math.min(0,a.z)+Math.min(0,b.z)+Math.min(0,c.z),
                        min.x+Math.max(0,a.x)+Math.max(0,b.x)+Math.max(0,c.x),min.y+Math.max(0,a.y)+Math.max(0,b.y)+Math.max(0,c.y),min.z+Math.max(0,a.z)+Math.max(0,b.z)+Math.max(0,c.z));
                return SourceMeshBuilder.distanceSquared(bounds,viewer);
            }
            Vec3d focus(Bounds bounds) {
                Vec3d offset=viewer.subtract(root);double determinant=x.dotProduct(y.crossProduct(z));
                Vec3d p=Math.abs(determinant)<1e-6?source:source.add(offset.dotProduct(y.crossProduct(z))/determinant,offset.dotProduct(z.crossProduct(x))/determinant,offset.dotProduct(x.crossProduct(y))/determinant);
                return new Vec3d(MathHelper.clamp(p.x,bounds.minX()+.5,bounds.maxX()-.5),MathHelper.clamp(p.y,bounds.minY()+.5,bounds.maxY()-.5),MathHelper.clamp(p.z,bounds.minZ()+.5,bounds.maxZ()-.5));
            }
        }
        private View view(ServerPlayerEntity p,Session s,Window w) {
            if(s.shallow && w==s.local)return new View(s.carrier,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),w.origin,p.getPos());
            Basis384f b=basis(p);return new View(origin(p,s,w),axis(w.sourceBasis.i,b),axis(w.sourceBasis.j,b),axis(w.sourceBasis.k,b),w.origin,p.getPos());
        }
        void maintain(ServerPlayerEntity p,Session s) {
            var all=windows(s);Window w=all.get(Math.floorMod(s.regionCursor++,all.size()));
            if(!w.id.isEmpty() && tick-w.metadataChecked>=20) {
                w.metadataChecked=tick;LandmarkMetadata current=store.metadata(w.id).orElse(null);
                if(current!=null && current.header().dimension().equals(w.dimension)) {
                    boolean geometry=w.owner!=null && !w.owner.geometryKeys().equals(current.geometryKeys());
                    w.owner=current;w.supportEmbedding=SpiritActivityService.effectiveEmbedding(server,current).vector();
                    w.retired=false;if(geometry){w.replacing=true;w.nearReady=false;w.nearCoverage=null;w.nearFocus=null;if(w.nearFuture!=null)w.nearFuture.cancel(false);w.nearCells=null;w.nearSamples=null;w.stream=null;}
                    // Physical/source anchors and the user's captured target are never recreated on revision changes.
                    if(w!=s.target && (!s.shallow || w!=s.local)) {
                        View v=view(p,s,w);Vec3d focus=v.focus(current.header().bounds());
                        if(w.stream==null || w.stream.complete && w.stream.focus.squaredDistanceTo(focus)>64)w.stream=new TerrainGeometryStream(current,focus);
                        if(w.nearFocus==null || w.nearFocus.squaredDistanceTo(focus)>16)requestNear(p,s,w,focus);
                    }
                    if(w.observedOwnership!=store.ownershipRevision()) {
                        w.observedOwnership=store.ownershipRevision();requestOwnership(p,s,w,view(p,s,w).focus(current.header().bounds()));
                    }
                } else {w.retired=true;cancelWindow(w);}
            }
            if(w.stream!=null && w.stream.changed)install(p,s,w);
        }
        void advanceNear() {
            var waiting=new ArrayList<Map.Entry<Session,Window>>();for(Session s:sessions.values())for(Window w:windows(s))if(w.nearCells!=null)waiting.add(Map.entry(s,w));
            if(waiting.isEmpty())return;var entry=waiting.get(Math.floorMod(nearCursor++,waiting.size()));Window w=entry.getValue();
            int budget=128;while(w.nearCells.hasNext() && budget-->0)ingest(server,w.nearSamples,List.of(w.nearCells.next()),1);
            if(!w.nearCells.hasNext()) {
                w.nearCells=null;w.nearReady=w.nearComplete && store.metadata(w.id).map(m->m.geometryKeys().equals(w.nearKeys)).orElse(false);
                Session s=entry.getKey();ServerPlayerEntity p=player(s);
                boolean covered=w.nearReady && p!=null && retained(s,w)
                        && (!protectedNear(p,s,w) || replacementCovered(p,s,w));
                if(covered) {
                    // Gate BEFORE pruning/compaction as well as stream installation: complete source samples
                    // need not cover the accepted projected five-block patch under compressed affine geometry.
                    for(var sample:w.nearSamples.tiles.entrySet())w.putTile(sample.getKey(),sample.getValue(),w.nearSamples.liveTiles.contains(sample.getKey()));
                    enforceRetention(w,w.nearFocus);
                    w.nodes=compact(server,w,w.nearFocus);w.ready=!w.nodes.isEmpty();
                } else {w.nearReady=false;w.nearFocus=null;} // Unknown/outside coverage preserves all tiles/nodes, including held-frame support.
                w.nearSamples=null;
            }
        }
        private void requestNear(ServerPlayerEntity p,Session s,Window w,Vec3d focus) {
            if(w.nearFuture!=null && !w.nearFuture.isDone())return;
            w.nearFocus=focus;w.nearReady=false;w.nearComplete=false;w.nearCoverage=null;List<String> keys=List.copyOf(w.owner.geometryKeys());w.nearKeys=keys;
            var future=SourceLandmarks.region(server,w.dimension,SourceMeshBuilder.range(focus,16),4096);w.nearFuture=future;
            future.whenComplete((region,error)->server.execute(()->{
                if(!live(p,s) || !retained(s,w) || w.nearFuture!=future)return;
                if(error!=null || region==null || !store.metadata(w.id).map(m->m.geometryKeys().equals(keys)).orElse(false)){w.nearFocus=null;return;}
                // Resolve bounded material batches off to the side; incomplete samples never mutate the published patch.
                w.nearSamples=new Window(w.dimension,focus,w.semantic,w.sourceBasis,w.id);
                w.nearComplete=region.complete();w.nearCoverage=region.complete()?region.bounds():null;
                w.nearCells=region.cells().iterator();
            }));
        }
        private void install(ServerPlayerEntity p,Session s,Window w) {
            if(w.stream==null || !w.stream.changed)return;
            View v=view(p,s,w);
            // Incoming-intersection SAT cannot detect an omitted floor. Keep the previous stream until the
            // complete sampled patch actually covers the accepted five-block producer geometry, including held frames.
            if(protectedNear(p,s,w) && !replacementCovered(p,s,w))return;
            boolean hidden=!w.geometryNodes.isEmpty() && w.geometryNodes.stream().allMatch(n->v.distance(n)>=64*64);
            if(w.replacing && !hidden && !protectedNear(p,s,w) && (w.alpha>.01f || !w.nearReady))return;
            w.geometryNodes=w.stream.snapshot(v::distance);w.nodes=compact(server,w,w.nearFocus==null?w.origin:w.nearFocus);
            if(w.owners!=null && w.owners.isCurrent(server))w.nodes=SourceMeshBuilder.splitOwnership(w.nodes,w.owners.owners(),w.owners.bounds(),w.nearFocus==null?w.origin:w.nearFocus);
            w.ready=!w.nodes.isEmpty();w.replacing=false;
        }
        void animate(ServerPlayerEntity p,Session s) {
            double radius=MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS;Vec384f now=q(p);
            for(Window w:s.regions.values()) {
                boolean protectedBody=protectedNear(p,s,w),eligible=!w.retired && s.selected.contains(w.id) && w.semantic.squareDistance(now)<radius;
                if(protectedBody && w.alpha>0)w.alpha=Math.max(w.alpha,.99f);
                else w.alpha=MathHelper.clamp(w.alpha+(eligible && !w.replacing?.05f:-.05f),0,1);
            }
            if(s.target!=null && s.target!=s.local) {
                Window target=s.target;boolean ready=target.ready && currentTarget(p,s) && target.proofValid && target.semantic.squareDistance(now)<radius;
                // Publish runs every two ticks. Rejected/held frames cannot bank invisible opacity progress.
                target.alpha=MathHelper.clamp(target.publishedAlpha+(ready?.1f:-.1f),0,1);
            }
            // Prepare before activation. Prefer hidden geometry at the actual opaque horizon; a current in-radius close
            // representative is admitted with a 20-tick fade, never an impossible fog gate or a body/q teleport.
            for(var iterator=s.prepared.entrySet().iterator();iterator.hasNext();) {
                Window w=iterator.next().getValue();if(!w.ready || !s.selected.contains(w.id) || w.semantic.squareDistance(now)>=radius)continue;
                View v=view(p,s,w);boolean hidden=w.nodes.stream().allMatch(n->v.distance(n)>=64*64);
                if(!hidden && !w.nearReady)continue;
                if(!admit(p,s,w.id))continue;
                w.alpha=0;s.regions.put(w.id,w);iterator.remove();
            }
        }
        private boolean replacementCovered(ServerPlayerEntity p,Session s,Window w) {
            if(!w.nearReady || w.nearCoverage==null)return false;
            if(s.frame!=null)for(var cell:s.frame.cells()) {
                if(windowFor(s,cell)!=w || SourceMeshBuilder.distanceSquared(cell.bounds(),p.getPos())>=25)continue;
                Vec3d at=cell.sourceMin(),size=cell.size();
                Bounds source=new Bounds(Math.round(at.x),Math.round(at.y),Math.round(at.z),
                        Math.round(at.x+size.x),Math.round(at.y+size.y),Math.round(at.z+size.z));
                if(!w.nearCoverage.contains(source))return false;
            }
            // Complete includes known AIR: genuine source holes may remove old floor once that coverage is proven.
            return true;
        }
        private boolean protectedNear(ServerPlayerEntity p,Session s,Window w) {
            if(w==s.local)return true;
            if(s.frame!=null)for(var cell:s.frame.cells())if(windowFor(s,cell)==w && SourceMeshBuilder.distanceSquared(cell.bounds(),p.getPos())<25)return true;
            return false;
        }
        private boolean immediate(ServerPlayerEntity p,Session s,Window w) {
            if(MeshCollision.ground(p).filter(h->windowFor(s,h.cell())==w).isPresent())return true;
            if(s.frame!=null)for(var cell:s.frame.cells())if(windowFor(s,cell)==w && cell.bounds().intersects(p.getBoundingBox().expand(.15)))return true;
            return false;
        }
        private boolean admit(ServerPlayerEntity p,Session s,String id) {
            int limit=id.equals(s.closestId)?9:8; // One bounded reserved slot that stale projected-near regions cannot occupy.
            if(s.regions.size()<limit)return true;
            for(var iterator=s.regions.entrySet().iterator();iterator.hasNext();) {
                var e=iterator.next();if(s.selected.contains(e.getKey()) || protectedNear(p,s,e.getValue()))continue;
                // animate performs the fade; capacity pressure never makes an opaque region disappear in one tick.
                if(e.getValue().alpha<=0){cancelWindow(e.getValue());iterator.remove();return s.regions.size()<limit;}
            }
            return false;
        }
        private ServerPlayerEntity player(Session s){for(var entry:sessions.entrySet())if(entry.getValue()==s)return server.getPlayerManager().getPlayer(entry.getKey());return null;}
        private void advanceStream() {
            var work=new ArrayList<Map.Entry<Session,Window>>();
            for(Session s:sessions.values())for(Window w:windows(s))if(w.stream!=null && !w.stream.complete)work.add(Map.entry(s,w));
            if(work.isEmpty())return;
            var entry=work.get(Math.floorMod(geometryCursor++,work.size()));Session s=entry.getKey();Window w=entry.getValue();ServerPlayerEntity p=player(s);
            if(p==null || !w.stream.current(store.metadata(w.id).orElse(null))){w.stream=null;return;}
            if(w.stream.cells!=null){w.stream.advance(w.id,view(p,s,w)::distance);install(p,s,w);return;}
            if(!store.geometryReadAvailable())return;
            readingSession=s;readingWindow=w;proving=false;
            read=store.beginGeometryRead(w.id,w.stream.range(w.owner),w.stream.pageIndex);advanceRead();
        }
        private void advanceRead() {
            TerrainGeometryStream stream=readingWindow.stream;ServerPlayerEntity p=player(readingSession);
            if(stream==null || p==null || !stream.current(store.metadata(readingWindow.id).orElse(null))){read.cancel();read=null;return;}
            int before=read.nextPageIndex();read.advance(1,256);var pages=read.drain();stream.pageIndex=read.nextPageIndex();
            boolean complete=read.complete();
            if(!pages.isEmpty()) {
                stream.accept(pages.getFirst());read.cancel();read=null;
                stream.advance(readingWindow.id,view(p,readingSession,readingWindow)::distance);install(p,readingSession,readingWindow);
            } else if(complete){stream.endPass();read.cancel();read=null;}
            // Skipped page headers also yield the shared cursor, but a partially decoded page must finish to avoid replay starvation.
            else if(stream.pageIndex>before){read.cancel();read=null;}
        }
        void advanceGeometry() {
            try {
                if(read==null) {
                    for(var entry:sessions.entrySet()) {
                        var s=entry.getValue();var p=server.getPlayerManager().getPlayer(entry.getKey());Window target=s.target;
                        if(p==null || target==null || !target.ready || !target.confirmedOrigin || !currentTarget(p,s))continue;
                        if(target.proofKeys.equals(target.owner.geometryKeys()))continue;
                        if(!store.geometryReadAvailable())return;
                        read=store.beginGeometryRead(target.id,SourceMeshBuilder.range(target.origin,16));readingSession=s;readingWindow=target;proving=true;
                        target.proofValid=false;target.alpha=0;target.publishedAlpha=0;proofSamples.clear();break;
                    }
                    int attempts=pending.size();
                    while(read==null && !pending.isEmpty() && attempts-->0) {
                        var next=pending.removeFirst();Session s=next.getKey();
                        if(!sessions.containsValue(s) || s.prepared.containsKey(next.getValue().id()) || s.regions.containsKey(next.getValue().id()))continue;
                        LandmarkMetadata m=store.metadata(next.getValue().id()).orElse(null);if(m==null)continue;
                        ServerPlayerEntity player=player(s);
                        if(player==null || !s.selected.contains(m.id()) || SpiritActivityService.effectiveEmbedding(server,m).vector().squareDistance(q(player))>=MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS)continue;
                        if(s.prepared.size()>=9){pending.addLast(next);continue;}
                        Window w=new Window(m.header().dimension(),new Vec3d(m.header().anchor().x(),m.header().anchor().y(),m.header().anchor().z()),SpiritActivityService.effectiveEmbedding(server,m).vector(),s.local.sourceBasis,m.id());
                        w.owner=m;w.supportEmbedding=w.semantic.clone();w.alpha=0;s.prepared.put(w.id,w);
                        Vec3d focus=view(player,s,w).focus(m.header().bounds());w.stream=new TerrainGeometryStream(m,focus);requestNear(player,s,w,focus);
                        break;
                    }
                    if(read==null){advanceStream();return;}
                }
                LandmarkMetadata current=store.metadata(readingWindow.id).orElse(null);
                if(!retained(readingSession,readingWindow) || current==null || !read.metadata().geometryKeys().equals(current.geometryKeys())){read.cancel();read=null;proving=false;proofSamples.clear();return;}
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
                    Box required=body(readingWindow.origin);boolean sampled=proofSamples.containsKey(BlockPos.ofFloored(readingWindow.origin.add(0,-.05,0)));
                    for(BlockPos point:BlockPos.iterate(MathHelper.floor(required.minX),MathHelper.floor(required.minY),MathHelper.floor(required.minZ),MathHelper.floor(required.maxX),MathHelper.floor(required.maxY),MathHelper.floor(required.maxZ)))sampled &=proofSamples.containsKey(point);
                    // Stop once the exact footprint is known; don't hydrate every distant page of a large cave just to land.
                    if(read.complete() || sampled) {
                        Window target=readingWindow;boolean valid=current!=null && read.metadata().geometryKeys().equals(current.geometryKeys());Box area=body(target.origin);
                        for(BlockPos point:BlockPos.iterate(MathHelper.floor(area.minX),MathHelper.floor(area.minY),MathHelper.floor(area.minZ),MathHelper.floor(area.maxX),MathHelper.floor(area.maxY),MathHelper.floor(area.maxZ))) {
                            // Ownership must be exact; actual body clearance is checked from the current collision shapes, not block-is-AIR heuristics.
                            if(proofSamples.get(point)==null)valid=false;
                        }
                        var floor=proofSamples.get(BlockPos.ofFloored(target.origin.add(0,-.05,0)));
                        valid &=floor!=null && !isAir(floor.blockId());
                        target.proofKeys=List.copyOf(read.metadata().geometryKeys());target.proofValid=valid;
                        if(valid) {
                            ServerWorld source=world(server,target.dimension);
                            for(var sample:proofSamples.entrySet()) {
                                var actual=source==null?null:SourceMeshBuilder.read(source,sample.getKey());
                                target.putTile(sample.getKey(),actual!=null?actual:SourceMeshBuilder.stored(sample.getValue(),sample.getKey(),target.tiles),actual!=null);
                            }
                            target.nodes=compact(server,target,target.origin);
                        }
                        if(!read.complete())read.cancel();read=null;proving=false;proofSamples.clear();
                    }
                    return;
                }
                advanceRead();
            } catch(RuntimeException deferred) {if(read!=null)read.cancel();read=null;proving=false;proofSamples.clear();}
        }
    }
}
