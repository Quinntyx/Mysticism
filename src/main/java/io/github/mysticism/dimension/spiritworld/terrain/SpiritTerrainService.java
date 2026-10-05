package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.activity.SpiritActivityService;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import java.nio.file.Files;
import java.util.*;

/** Server-thread physical overlay. The parent registers init(); no model work or source chunk reads. */
public final class SpiritTerrainService {
    private SpiritTerrainService() {}
    public static final RegistryKey<World> WORLD=RegistryKey.of(RegistryKeys.WORLD,Identifier.of("mysticism","spirit"));
    public record Support(String landmarkId,Vec384f embedding,Vec3d center,Vec3d normal) {
        public Support { embedding=embedding.clone(); }
        @Override public Vec384f embedding() { return embedding.clone(); }
    }
    private static final TerrainConfig CONFIG=TerrainConfig.DEFAULT;
    private static final Map<MinecraftServer,Context> SERVERS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,String> FAILURES=new IdentityHashMap<>();
    private static boolean initialized;
    public static void init() {
        if(initialized)return; initialized=true;
        ServerTickEvents.END_SERVER_TICK.register(SpiritTerrainService::tick);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player,origin,destination)->{
            Context c=SERVERS.get(player.getServer());
            if(c!=null && origin.getRegistryKey().equals(WORLD))c.leave(player);
            if(destination.getRegistryKey().equals(WORLD))context(player.getServer()).enter(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{ var c=SERVERS.get(server); if(c!=null) { c.returnPending(handler.player); c.leave(handler.player); } });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer,newPlayer,alive)->{ var c=SERVERS.get(newPlayer.getServer()); if(c!=null)c.leave(newPlayer); });
        ServerWorldEvents.UNLOAD.register((server,world)->{ if(world.getRegistryKey().equals(WORLD))close(server); });
        ServerLifecycleEvents.SERVER_STOPPING.register(SpiritTerrainService::close);
        ServerLifecycleEvents.SERVER_STOPPED.register(SpiritTerrainService::close);
        PlayerBlockBreakEvents.AFTER.register((world,player,pos,state,entity)->{
            if(world instanceof ServerWorld sw && sw.getRegistryKey().equals(WORLD))protect(sw,pos);
        });
        // A placement of the very same state is otherwise indistinguishable from our generated block.
        // Conservatively protect both clicked and adjacent positions, including cancelled interactions.
        UseBlockCallback.EVENT.register((player,world,hand,hit)->{
            if(world instanceof ServerWorld sw && sw.getRegistryKey().equals(WORLD)) {
                protect(sw,hit.getBlockPos()); protect(sw,hit.getBlockPos().offset(hit.getSide()));
            }
            return ActionResult.PASS;
        });
    }
    private static void protect(ServerWorld world,BlockPos pos) {
        var c=SERVERS.get(world.getServer()); if(c==null)return;
        c.state.ledger.protect(position(pos)); c.state.markDirty();
    }
    private static OverlayLedger.Pos position(BlockPos p) { return new OverlayLedger.Pos(p.getX(),p.getY(),p.getZ()); }
    private static BlockPos block(OverlayLedger.Pos p) { return new BlockPos(p.x(),p.y(),p.z()); }
    private static void close(MinecraftServer server) {
        FAILURES.remove(server); var c=SERVERS.remove(server); if(c!=null) {
            c.cancel(); for(var p:List.copyOf(server.getPlayerManager().getPlayerList())) { c.returnPending(p); c.leave(p); }
        }
    }
    /** Parent entry command calls this BEFORE its dimension teleport, and aborts on false.
     * Saves the real return pose; only that entry may suspend gravity while staging collision. */
    public static boolean prepareEnter(ServerPlayerEntity player) {
        try {
            if(player.getWorld().getRegistryKey().equals(WORLD))return false;
            LandmarkProfiles.wrap(player.getComponent(MysticismEntityComponents.LATENT_POS).get());
            var c=context(player.getServer()); if(c.returns.size()>=8 || c.players.size()>=8)return false;
            c.returns.put(player.getUuid(),new Context.ReturnPose(player.getServerWorld(),player.getPos(),player.getYaw(),player.getPitch(),player.hasNoGravity(),c.tick+40));
            return true;
        } catch(RuntimeException failure) { player.sendMessage(Text.literal("[Spirit terrain] Entry unavailable: "+failure.getMessage()),false); return false; }
    }
    /** Parent uses this if its prepared dimension transition is cancelled or throws. */
    public static void cancelEnter(ServerPlayerEntity player) {
        var c=SERVERS.get(player.getServer()); if(c!=null)c.leave(player);
    }
    private static Context context(MinecraftServer server) {
        if(!server.isOnThread())throw new IllegalStateException("terrain requires server thread");
        ServerWorld world=server.getWorld(WORLD); if(world==null)throw new IllegalStateException("spirit dimension unavailable");
        return SERVERS.computeIfAbsent(server,s->new Context(s,world));
    }
    private static void tick(MinecraftServer server) {
        if(server.getWorld(WORLD)==null)return;
        Context c=SERVERS.get(server);
        if(c==null && server.getPlayerManager().getPlayerList().stream().noneMatch(p->p.getWorld().getRegistryKey().equals(WORLD)))return;
        try { context(server).tick(); }
        catch(RuntimeException failure) {
            c=SERVERS.get(server); String message="Paused: "+failure.getMessage();
            if(c!=null) { c.cancel(); c.status(message); }
            else if(!Objects.equals(FAILURES.put(server,message),message))
                for(var p:server.getPlayerManager().getPlayerList())if(p.getWorld().getRegistryKey().equals(WORLD))p.sendMessage(Text.literal("[Spirit terrain] "+message),true);
        }
    }
    /** Constant-size footprint probe; no repository reads, hydration, model work or chunk loading. */
    public static Optional<Support> support(ServerPlayerEntity player) {
        MinecraftServer server=player.getServer(); if(server==null || !server.isOnThread() || !player.getWorld().getRegistryKey().equals(WORLD))return Optional.empty();
        var c=SERVERS.get(server); if(c==null || c.state.frame()==null || !player.isOnGround())return Optional.empty();
        if(!c.state.frame().semanticOrigin().profile().equals(LandmarkProfiles.current()))return Optional.empty();
        double feet=player.getY(); var box=player.getBoundingBox();
        int y=MathHelper.floor(feet-.05);
        for(int dy=0;dy<2;dy++)for(int z=MathHelper.floor(box.minZ+.001);z<=MathHelper.floor(box.maxZ-.001) && z<=MathHelper.floor(box.minZ)+2;z++)
            for(int x=MathHelper.floor(box.minX+.001);x<=MathHelper.floor(box.maxX-.001) && x<=MathHelper.floor(box.minX)+2;x++) {
                var pos=new BlockPos(x,y-dy,z); if(!c.world.isChunkLoaded(pos))continue;
                var entry=c.state.ledger.entry(position(pos)); if(entry==null || entry.protectedEdit())continue;
                BlockState actual=c.world.getBlockState(pos); if(!material(actual).equals(entry.generated()))continue;
                var shape=actual.getCollisionShape(c.world,pos);
                var layer=c.layers.get(entry.owner()); if(layer==null || !contact(shape,pos,box,feet))continue;
                Point3 a=layer.placement().realmAnchor();
                return Optional.of(new Support(entry.owner(),layer.embedding().vector(),new Vec3d(a.x(),a.y(),a.z()),new Vec3d(0,1,0)));
            }
        return Optional.empty();
    }
    private static BlockPalette.State material(BlockState state) {
        Map<String,String> values=new TreeMap<>(); state.getEntries().forEach((p,v)->values.put(p.getName(),propertyName(p,v)));
        return new BlockPalette.State(Registries.BLOCK.getId(state.getBlock()).toString(),values);
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static String propertyName(Property property,Comparable value) { return property.name(value); }
    private static <T extends Comparable<T>> BlockState apply(BlockState state,Property<T> property,String value) {
        return state.with(property,property.parse(value).orElseThrow(()->new IllegalArgumentException("unknown palette value")));
    }
    private static final class PlayerState {
        boolean entering; long revision,entryDeadline; String status;
        RepresentativeSelector.RepresentativeSet selection;
        final RepresentativeSelector selector=CONFIG.selector();
    }
    private static final class Context {
        final MinecraftServer server; final ServerWorld world; final TerrainState state;
        final Map<UUID,PlayerState> players=new LinkedHashMap<>();
        final Map<UUID,ReturnPose> returns=new HashMap<>();
        record ReturnPose(ServerWorld world,Vec3d position,float yaw,float pitch,boolean noGravity,long preparedUntil) {}
        final Map<String,TerrainField.Layer> layers=new TreeMap<>();
        final Set<String> desired=new TreeSet<>();
        final Set<String> geometryChanged=new TreeSet<>();
        final Map<String,Set<OverlayLedger.Region>> layerRegions=new TreeMap<>();
        final NavigableSet<OverlayLedger.Region> frontier=new TreeSet<>();
        record Stamp(long epoch,boolean withinFog) {}
        final Map<OverlayLedger.Region,Stamp> stamped=new HashMap<>();
        final LinkedHashMap<BlockPalette.State,BlockState> palette=new LinkedHashMap<>(16,.75f,true);
        Stage stage; long tick,epoch=1; OverlayLedger.Region lastRegion; String error;
        Landing landing;
        record Landing(BlockPos floor,String owner) {}
        Context(MinecraftServer server,ServerWorld world) {
            this.server=server; this.world=world;
            var manager=world.getPersistentStateManager();
            var saved=manager.get(TerrainState.TYPE,TerrainState.KEY);
            // Do not let vanilla's decoder-error/null behavior overwrite an unreadable existing manifest.
            if(saved==null && !Files.notExists(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                    .resolve("dimensions/mysticism/spirit/data").resolve(TerrainState.KEY+".dat")))
                throw new IllegalStateException("unreadable spirit terrain manifest");
            state=saved==null?new TerrainState():saved; if(saved==null)manager.set(TerrainState.KEY,state);
            if(state.frame()!=null)state.frame().semanticOrigin().profile().requireCompatible(LandmarkProfiles.current());
            frontier.addAll(state.ledger.regions());
        }
        void enter(ServerPlayerEntity player) {
            if(players.size()>=8 && !players.containsKey(player.getUuid())) { player.sendMessage(Text.literal("[Spirit terrain] player budget reached"),false); return; }
            var p=players.computeIfAbsent(player.getUuid(),id->new PlayerState()); p.entering=true; p.entryDeadline=tick+400;
            if(returns.containsKey(player.getUuid())) { player.setNoGravity(true); player.setVelocity(Vec3d.ZERO); }
            else player.sendMessage(Text.literal("[Spirit terrain] Entry not prepared: asynchronous landing cannot suspend gravity safely"),false);
        }
        void returnPending(ServerPlayerEntity player) {
            var actor=players.get(player.getUuid()); var pose=returns.get(player.getUuid());
            if(actor!=null && actor.entering && pose!=null && server.getWorld(pose.world.getRegistryKey())==pose.world) {
                player.teleport(pose.world,pose.position.x,pose.position.y,pose.position.z,pose.yaw,pose.pitch);
                player.setNoGravity(pose.noGravity);
            }
        }
        void leave(ServerPlayerEntity player) {
            var pose=returns.remove(player.getUuid()); if(pose!=null)player.setNoGravity(pose.noGravity);
            players.remove(player.getUuid());
        }
        void status(String message) {
            error=message;
            for(var p:server.getPlayerManager().getPlayerList()) {
                var actor=players.get(p.getUuid());
                if(actor!=null && !Objects.equals(actor.status,message)) { actor.status=message; p.sendMessage(Text.literal("[Spirit terrain] "+message),true); }
            }
        }
        void cancel() { if(stage!=null)stage.cancel(); stage=null; }
        void tick() {
            tick++;
            returns.entrySet().removeIf(e->{
                var p=server.getPlayerManager().getPlayer(e.getKey());
                return p==null || !p.getWorld().getRegistryKey().equals(WORLD) && tick>e.getValue().preparedUntil;
            });
            List<ServerPlayerEntity> observers=new ArrayList<>();
            for(var p:server.getPlayerManager().getPlayerList())if(p.getWorld().getRegistryKey().equals(WORLD)) {
                observers.add(p);
                if(observers.size()>8) {
                    for(var pending:List.copyOf(server.getPlayerManager().getPlayerList()))returnPending(pending);
                    status("More than eight spirit players: mutations paused for everyone's safety"); return;
                }
                players.computeIfAbsent(p.getUuid(),id->new PlayerState()); // Reload/login is NOT another enter teleport.
            }
            Set<UUID> live=new HashSet<>(); observers.forEach(p->live.add(p.getUuid())); players.keySet().removeIf(id->!live.contains(id));
            for(var p:List.copyOf(observers)) {
                var actor=players.get(p.getUuid()); var pose=returns.get(p.getUuid());
                if(!actor.entering || pose==null)continue;
                p.setVelocity(Vec3d.ZERO); p.fallDistance=0;
                if(tick>=actor.entryDeadline) {
                    p.teleport(pose.world,pose.position.x,pose.position.y,pose.position.z,pose.yaw,pose.pitch);
                    p.setNoGravity(pose.noGravity); leave(p); observers.remove(p);
                    p.sendMessage(Text.literal("[Spirit terrain] Entry timed out; returned without changing the source world"),false);
                }
            }
            if(state.frame()==null) {
                if(observers.isEmpty())return;
                var first=observers.getFirst(); var basis=first.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
                Vec384f[] axes=orthonormal(basis.i,basis.j,basis.k);
                state.initialize(new ProjectionFrame(1,world.getSeed(),LandmarkProfiles.wrap(first.getComponent(MysticismEntityComponents.LATENT_POS).get()),
                        new Point3(0,128,0),axes[0],axes[1],axes[2],96));
            }
            state.frame().semanticOrigin().profile().requireCompatible(LandmarkProfiles.current());
            if(!observers.isEmpty() && (tick%20==1))refresh(observers.get((int)((tick/20)%observers.size())));
            Set<String> previousDesired=new TreeSet<>(desired); desired.clear();
            for(var actor:players.values())if(actor.selection!=null)for(var rep:actor.selection.representatives())desired.add(rep.landmarkId());
            if(desired.size()>8) { status("Global representative budget reached; existing collision retained"); desired.retainAll(layers.keySet()); }
            if(!desired.equals(previousDesired))epoch++;
            if(stage!=null && !desired.contains(stage.metadata.id()))cancel();
            if(stage==null)for(String id:desired)if((!layers.containsKey(id) && layers.size()<8) || geometryChanged.contains(id)) {
                try { stage=new Stage(LandmarkStore.get(server).beginGeometryRead(id)); }
                catch(IllegalStateException busy) { status("Geometry cursor busy; retrying without changing collision"); }
                break;
            }
            if(stage!=null)stage.advance();
            for(var it=layers.entrySet().iterator();it.hasNext();) {
                var e=it.next(); if(!desired.contains(e.getKey()) && state.ledger.ownedCount(e.getKey())==0) {
                    it.remove(); layerRegions.remove(e.getKey()); geometryChanged.remove(e.getKey()); epoch++;
                    frontier.removeIf(r->!state.ledger.regions().contains(r) && layerRegions.values().stream().noneMatch(rs->rs.contains(r)));
                    stamped.keySet().retainAll(frontier);
                }
            }
            var active=layers.values().stream().filter(l->desired.contains(l.metadata().id())).toList();
            TerrainField field=new TerrainField(state.frame().seed(),active);
            for(int i=0;i<CONFIG.probesPerTick() && !frontier.isEmpty();i++) {
                OverlayLedger.Region r=lastRegion==null?frontier.first():frontier.higher(lastRegion);
                if(r==null)r=frontier.first(); lastRegion=r;
                var access=new Access(observers);
                boolean withinFog=access.inPrefetch(r);
                Stamp target=new Stamp(epoch,withinFog); if(target.equals(stamped.get(r)))continue;
                if(!access.loaded(r) || !access.mayChange(r))continue;
                if(state.ledger.reconcile(r,access,pos->{
                    var sample=withinFog?field.sample(pos.x(),pos.y(),pos.z()):null; if(sample==null)return null;
                    resolve(sample.material()); return new OverlayLedger.Desired<>(sample.material(),sample.landmarkId());
                })) {
                    state.markDirty(); stamped.put(r,target);
                    offerLanding(r,field,observers);
                    if(state.ledger.forgetEmpty(r) && layerRegions.values().stream().noneMatch(rs->rs.contains(lastRegion))) { frontier.remove(r); stamped.remove(r); }
                    // <=512 ordinary block writes/tick; a rejected batch may additionally roll back <=512.
                } else status("Overlay capacity reached; existing terrain and player edits retained");
                break; // One region preflight per tick, not one successful preflight per tick.
            }
            tryLanding(field,observers);
            if(observers.isEmpty())desired.clear();
        }
        void refresh(ServerPlayerEntity player) {
            var actor=players.get(player.getUuid());
            if(tick-actor.revision<CONFIG.selectionPeriod() && actor.selection!=null)return;
            var query=LandmarkProfiles.wrap(player.getComponent(MysticismEntityComponents.LATENT_POS).get());
            var metadata=LandmarkStore.get(server).semanticRange(query,CONFIG.semanticRadius(),CONFIG.catalogLimit(),CONFIG.catalogLimit());
            List<Landmark> effective=new ArrayList<>();
            for(var m:metadata) {
                var h=m.header(); var embedding=SpiritActivityService.effectiveEmbedding(server,m);
                h.baseEmbedding().profile().requireCompatible(embedding.profile());
                double importance=SpiritActivityService.importance(server,m);
                if(!Double.isFinite(importance))throw new IllegalArgumentException("nonfinite activity importance"); importance=Math.clamp(importance,0,1);
                effective.add(new Landmark(h.id(),h.dimension(),h.algorithmVersion(),h.kind(),h.biome(),h.anchor(),h.bounds(),embedding,importance,
                        new ActivityMetadata(0,tick),h.ownership(),h.geometry(),h.revision(),h.provenance()));
                var old=layers.get(h.id()); if(old!=null) {
                    // Activity changes selection/support, NEVER the base placement or collision transform.
                    if(!old.metadata().geometryKeys().equals(m.geometryKeys()))geometryChanged.add(h.id());
                    layers.put(h.id(),new TerrainField.Layer(old.metadata(),old.placement(),old.geometry(),embedding,importance));
                    if(old.importance()!=importance)epoch++;
                }
            }
            actor.selection=actor.selector.select(effective,query,tick,state.frame(),new Point3(player.getX(),player.getY(),player.getZ()),CONFIG.fog(),
                    new ImportancePolicy(1,1200,0,0),tick,1,actor.selection,Set.of());
            actor.revision=tick;
        }
        BlockState resolve(BlockPalette.State material) {
            BlockState cached=palette.get(material); if(cached!=null)return cached;
            Identifier id=Identifier.of(material.blockId()); if(!Registries.BLOCK.containsId(id))throw new IllegalArgumentException("unknown terrain block "+id);
            BlockState state=Registries.BLOCK.get(id).getDefaultState();
            for(var e:material.properties().entrySet()) {
                var property=state.getBlock().getStateManager().getProperty(e.getKey()); if(property==null)throw new IllegalArgumentException("unknown terrain property");
                state=apply(state,property,e.getValue());
            }
            if(state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty())throw new IllegalArgumentException("unsupported solid palette (air/fluid/block entity): "+id);
            palette.put(material,state); if(palette.size()>512)palette.remove(palette.keySet().iterator().next()); return state;
        }
        final class Access implements OverlayLedger.WorldAccess<BlockPalette.State> {
            final List<ServerPlayerEntity> observers;
            Access(List<ServerPlayerEntity> observers) { this.observers=observers; }
            public boolean loaded(OverlayLedger.Region r) {
                var o=r.origin(); return o.y()>=world.getBottomY() && o.y()+8<=world.getTopY()
                        && Math.abs((long)o.x())<29999976 && Math.abs((long)o.z())<29999976
                        && world.getChunkManager().getChunk(Math.floorDiv(o.x(),16),Math.floorDiv(o.z(),16),ChunkStatus.FULL,false)!=null;
            }
            boolean inPrefetch(OverlayLedger.Region r) {
                var o=r.origin(); Box bounds=new Box(o.x(),o.y(),o.z(),o.x()+8,o.y()+8,o.z()+8);
                for(var p:observers)if(distanceSquared(bounds,p.getPos())<CONFIG.fog().prefetch()*CONFIG.fog().prefetch())return true;
                return false;
            }
            public boolean mayChange(OverlayLedger.Region r) {
                var o=r.origin(); Box bounds=new Box(o.x(),o.y(),o.z(),o.x()+8,o.y()+8,o.z()+8);
                for(var p:observers) {
                    if(bounds.expand(2).intersects(p.getBoundingBox()))return false;
                    var actor=players.get(p.getUuid());
                    double distance=distanceSquared(bounds,p.getPos());
                    if((actor==null || !actor.entering) && distance<=CONFIG.mutationDistance()*CONFIG.mutationDistance())return false;
                }
                // Empty dimensions get bounded cleanup, but never cause chunk generation/loading.
                return true;
            }
            public BlockPalette.State get(OverlayLedger.Pos p) { return material(world.getBlockState(block(p))); }
            public boolean replaceable(OverlayLedger.Pos p,BlockPalette.State state) {
                String id=state.blockId();
                return (id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")) && world.getBlockEntity(block(p))==null;
            }
            public boolean set(OverlayLedger.Pos p,BlockPalette.State state) {
                BlockPos pos=block(p); BlockState next;
                if(state.blockId().equals("minecraft:air"))next=Blocks.AIR.getDefaultState();
                else if(state.blockId().equals("minecraft:cave_air"))next=Blocks.CAVE_AIR.getDefaultState();
                else if(state.blockId().equals("minecraft:void_air"))next=Blocks.VOID_AIR.getDefaultState();
                else next=resolve(state);
                return world.getBlockState(pos).equals(next) || world.setBlockState(pos,next,Block.NOTIFY_LISTENERS|Block.FORCE_STATE|Block.SKIP_DROPS);
            }
        }
        void offerLanding(OverlayLedger.Region region,TerrainField field,List<ServerPlayerEntity> observers) {
            if(landing!=null || observers.stream().noneMatch(p->players.get(p.getUuid()).entering))return;
            var o=region.origin();
            for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++) {
                var floor=new BlockPos(o.x()+x,o.y()+y,o.z()+z); var e=state.ledger.entry(position(floor));
                if(e==null || e.protectedEdit() || !field.knownAir(e.owner(),floor.getX(),floor.getY()+1,floor.getZ())
                        || !field.knownAir(e.owner(),floor.getX(),floor.getY()+2,floor.getZ()))continue;
                if(!world.getBlockState(floor).isSideSolidFullSquare(world,floor,Direction.UP)
                        || !world.getBlockState(floor.up()).isAir() || !world.getBlockState(floor.up(2)).isAir())continue;
                landing=new Landing(floor,e.owner()); return;
            }
        }
        void tryLanding(TerrainField field,List<ServerPlayerEntity> observers) {
            if(landing==null)return; BlockPos floor=landing.floor;
            if(!desired.contains(landing.owner)) { landing=null; return; }
            var center=position(floor).region();
            // Prepare a 24-block halo before entry. Unknown/outside geometry stays base-world terrain.
            for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                var r=new OverlayLedger.Region(center.x()+dx,center.y()+dy,center.z()+dz);
                if(frontier.contains(r) && !new Stamp(epoch,true).equals(stamped.get(r)))return;
            }
            if(!world.isChunkLoaded(floor) || !world.getBlockState(floor).isSideSolidFullSquare(world,floor,Direction.UP)
                    || !world.getBlockState(floor.up()).isAir() || !world.getBlockState(floor.up(2)).isAir()
                    || !field.knownAir(landing.owner,floor.getX(),floor.getY()+1,floor.getZ())) { landing=null; return; }
            for(var p:observers) {
                var actor=players.get(p.getUuid()); if(!actor.entering)continue;
                Vec3d target=new Vec3d(floor.getX()+.5,floor.getY()+1,floor.getZ()+.5);
                if(!world.getWorldBorder().contains(floor) || !world.isSpaceEmpty(p,p.getBoundingBox().offset(target.subtract(p.getPos()))))continue;
                p.teleport(world,target.x,target.y,target.z,p.getYaw(),p.getPitch()); p.setVelocity(Vec3d.ZERO); p.fallDistance=0; actor.entering=false;
                var pose=returns.remove(p.getUuid()); if(pose!=null)p.setNoGravity(pose.noGravity);
                p.sendMessage(Text.literal("[Spirit terrain] Entered observed landmark air"),true);
            }
        }
        final class Stage {
            final LandmarkStore.GeometryRead read; final LandmarkMetadata metadata; final List<GeometryPage> pages=new ArrayList<>();
            final NavigableSet<OverlayLedger.Region> regions=new TreeSet<>(); int leaves;
            TerrainField.Layer layer; int pageIndex; OverlayLedger.Region cursor;
            Stage(LandmarkStore.GeometryRead read) {
                this.read=read; metadata=read.metadata();
                if(metadata.geometryKeys().size()>CONFIG.pagesPerLandmark()) { read.cancel(); throw new IllegalArgumentException("landmark page budget exceeded"); }
            }
            void cancel() { read.cancel(); }
            void advance() {
                if(!read.isCurrent()) { cancel(); stage=null; status("Landmark changed during staging; retrying"); return; }
                if(layer==null) {
                    read.advance(1,1024);
                    for(var page:read.drain()) {
                        for(var old:pages)if(old.bounds().intersects(page.bounds()))throw new IllegalArgumentException("overlapping page AABBs require bounded reconciliation upstream");
                        // Conservatively charge the page maximum without enumerating its leaves again on tick.
                        leaves+=GeometryPage.MAX_LEAVES; if(leaves>CONFIG.leavesPerLandmark())throw new IllegalArgumentException("landmark conservative leaf budget exceeded"); pages.add(page);
                    }
                    if(!read.complete())return;
                    var embedding=SpiritActivityService.effectiveEmbedding(server,metadata);
                    double importance=Math.clamp(SpiritActivityService.importance(server,metadata),0,1);
                    layer=new TerrainField.Layer(metadata,state.placement(metadata),new SourceGeometry(pages,metadata.header().geometry().frontiers()),embedding,importance);
                }
                // Incremental projected-region frontier. Never iterate all source voxels on a tick.
                int budget=64;
                while(pageIndex<pages.size() && budget-->0) {
                    RealmBounds b=layer.placement().projectedBounds(pages.get(pageIndex).bounds());
                    int minX=Math.floorDiv((int)Math.floor(b.min().x()),8), minY=Math.floorDiv((int)Math.floor(b.min().y()),8), minZ=Math.floorDiv((int)Math.floor(b.min().z()),8);
                    int maxX=Math.floorDiv((int)Math.ceil(b.max().x())-1,8), maxY=Math.floorDiv((int)Math.ceil(b.max().y())-1,8), maxZ=Math.floorDiv((int)Math.ceil(b.max().z())-1,8);
                    if(cursor==null)cursor=new OverlayLedger.Region(minX,minY,minZ);
                    regions.add(cursor); if(regions.size()>4096)throw new IllegalArgumentException("landmark frontier budget exceeded");
                    if(cursor.z()<maxZ)cursor=new OverlayLedger.Region(cursor.x(),cursor.y(),cursor.z()+1);
                    else if(cursor.y()<maxY)cursor=new OverlayLedger.Region(cursor.x(),cursor.y()+1,minZ);
                    else if(cursor.x()<maxX)cursor=new OverlayLedger.Region(cursor.x()+1,minY,minZ);
                    else { cursor=null; pageIndex++; }
                }
                if(pageIndex<pages.size())return;
                Set<OverlayLedger.Region> combined=new HashSet<>(frontier); combined.addAll(regions);
                if(combined.size()>4096)throw new IllegalArgumentException("global terrain frontier budget exceeded");
                layers.put(metadata.id(),layer); layerRegions.put(metadata.id(),Set.copyOf(regions));
                geometryChanged.remove(metadata.id()); frontier.addAll(regions); epoch++; stage=null; status("Geometry staged; building loaded, fog-hidden regions");
            }
        }
    }
    static boolean contact(net.minecraft.util.shape.VoxelShape shape,BlockPos pos,Box player,double feet) {
        if(shape.isEmpty())return false;
        var boxes=shape.getBoundingBoxes(); if(boxes.size()>32)return false;
        for(Box b:boxes)if(Math.abs(pos.getY()+b.maxY-feet)<=.12 && b.offset(pos).intersects(player.expand(0,.06,0)))return true;
        return false;
    }
    static double distanceSquared(Box b,Vec3d p) {
        double x=Math.max(Math.max(b.minX-p.x,0),p.x-b.maxX), y=Math.max(Math.max(b.minY-p.y,0),p.y-b.maxY), z=Math.max(Math.max(b.minZ-p.z,0),p.z-b.maxZ);
        return x*x+y*y+z*z;
    }
    static Vec384f[] orthonormal(Vec384f... input) {
        Vec384f[] result=new Vec384f[3];
        for(int axis=0;axis<3;axis++) {
            float[] v=input[axis].data();
            for(int attempt=0;attempt<=v.length;attempt++) {
                for(int j=0;j<axis;j++) { float[] previous=result[j].data(); double dot=0; for(int i=0;i<v.length;i++)dot+=v[i]*previous[i]; for(int i=0;i<v.length;i++)v[i]-=(float)(dot*previous[i]); }
                double norm=0; for(float f:v)norm+=f*f;
                if(Double.isFinite(norm) && norm>1e-12) { for(int i=0;i<v.length;i++)v[i]/=(float)Math.sqrt(norm); break; }
                v=new float[v.length]; v[(axis+attempt)%v.length]=1;
            }
            result[axis]=new Vec384f(v);
        }
        return result;
    }
}
