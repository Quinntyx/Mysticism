package io.github.mysticism.net;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.terrain.*;
import io.github.mysticism.navigation.SpiritNavigationService;
import io.github.mysticism.net.mixin.SourceEntityLookupAccessor;
import io.github.mysticism.vector.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.minecraft.entity.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.*;
import net.minecraft.util.*;
import net.minecraft.util.function.LazyIterationConsumer;
import net.minecraft.util.math.*;
import io.netty.buffer.Unpooled;
import java.util.*;

/** Observer transport only. No frame persistence, first-ID world positions or global geometry anchor. */
public final class SpiritProjectionService {
    public static final double SCALE=96,OBSERVER_RADIUS=96,TOUCH_REACH=4;
    public interface Navigation {
        boolean deep(ServerPlayerEntity player);
        Optional<SpiritScenePayload.Binding> binding(ServerPlayerEntity player);
        void touch(ServerPlayerEntity actor,ServerPlayerEntity target);
    }
    public interface MeshProvider {
        boolean clearRay(ServerPlayerEntity viewer,Vec3d from,Vec3d to);
        /** Retired source-box metadata; canonical TerrainMeshFrame has its own exact stream. */
        default List<SpiritScenePayload.Mesh> meshes(ServerPlayerEntity viewer){return List.of();}
    }
    private static Navigation navigation=new Navigation(){
        public boolean deep(ServerPlayerEntity p){var n=p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);return n.active()&&n.deep();}
        public Optional<SpiritScenePayload.Binding> binding(ServerPlayerEntity p){var n=p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);if(!n.active()||n.deep())return Optional.empty();return SpiritTerrainService.sourcePosition(p).map(s->new SpiritScenePayload.Binding(s.dimension(),s.landmarkId(),s.position(),q(p)));}
        public void touch(ServerPlayerEntity a,ServerPlayerEntity t){SpiritNavigationService.touch(a,t);}
    };
    private static MeshProvider meshes=SpiritTerrainService::clearRay;
    private static final Map<ServerPlayNetworkHandler,Connection> connections=new IdentityHashMap<>();
    private static boolean initialized;private static int cursor,activeConnections;
    private static final class Connection {
        final UUID nonce=UUID.randomUUID();long generation,glyphSequence,sceneSequence,terrainSequence,lastTouch=-1,lastSendTick=-20;ServerPlayerEntity player;
        SpiritFramePayload.Session session;boolean acknowledged;TerrainMeshFrame pendingTerrain,sentTerrain;String error;Set<UUID> seenPeers=Set.of();
        void invalidate(){if(player!=null)SourceObservationTickets.release(player);if(session!=null)activeConnections--;session=null;acknowledged=false;player=null;pendingTerrain=null;sentTerrain=null;seenPeers=Set.of();glyphSequence=sceneSequence=terrainSequence=0;lastTouch=-1;}
    }
    private SpiritProjectionService(){}
    public static void install(Navigation nav,MeshProvider provider){navigation=Objects.requireNonNull(nav);meshes=Objects.requireNonNull(provider);}
    public static void init(){
        if(initialized)return;initialized=true;
        SpiritTerrainService.onFrame(SpiritProjectionService::sendTerrain);
        ServerTickEvents.END_SERVER_TICK.register(SpiritProjectionService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{var c=connections.remove(h);if(c!=null)c.invalidate();SourceObservationTickets.release(h.player);});
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p,a,b)->invalidate(p));
        ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->{invalidate(old);invalidate(p);});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{SourceObservationTickets.stop(server);connections.entrySet().removeIf(e->{if(e.getKey().player.getServer()!=server)return false;e.getValue().invalidate();return true;});});
    }
    private static Vec384f q(ServerPlayerEntity p){var v=p.getComponent(MysticismEntityComponents.LATENT_POS).get();EmbeddingSpace.requireCurrent(v);return v;}
    private static Basis384f basis(ServerPlayerEntity p){return p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();}
    private static boolean spirit(ServerPlayerEntity p){return p!=null&&p.getWorld().getRegistryKey().getValue().toString().equals("mysticism:spirit");}
    private static void thread(ServerPlayerEntity p){if(p.getServer()==null||!p.getServer().isOnThread())throw new IllegalStateException("Semantic transport requires server thread");}
    private static void invalidate(ServerPlayerEntity p){var c=connections.get(p.networkHandler);if(c!=null)c.invalidate();SourceObservationTickets.release(p);}
    /** Bootstrap establishes transport lifetime only; viewer geometry always comes from current q/basis. */
    public static boolean activate(ServerPlayerEntity p){
        thread(p);if(!spirit(p)){invalidate(p);return false;}var c=connections.get(p.networkHandler);
        if(c!=null&&c.player==p&&c.session!=null)return !c.acknowledged;
        if(activeConnections>=64)throw new IllegalStateException("Semantic observer capacity (64) exhausted");
        if(c==null){c=new Connection();connections.put(p.networkHandler,c);}
        if(!ServerPlayNetworking.canSend(p,SpiritScenePayload.ID)||!ServerPlayNetworking.canSend(p,SpiritDeltaPayload.ID)||!ServerPlayNetworking.canSend(p,SpiritTerrainPayload.ID))throw new IllegalStateException("Client lacks approved semantic scene protocol");
        long epoch=Math.incrementExact(c.generation);var session=new SpiritFramePayload.Session(c.nonce,p.getUuid(),"mysticism:spirit",epoch,epoch);
        var binding=navigation.binding(p).orElse(null);var bootstrap=new SpiritScenePayload(session,1,navigation.deep(p),false,binding,List.of(),List.of(),List.of(),List.of());
        ServerPlayNetworking.send(p,bootstrap);c.generation=epoch;c.session=session;activeConnections++;c.acknowledged=false;c.player=p;c.sceneSequence=1;c.glyphSequence=c.terrainSequence=0;c.sentTerrain=null;c.pendingTerrain=SpiritTerrainService.mesh(p).orElse(null);return true;
    }
    /** Old signature is source compatibility only: the supplied frame is NEVER used/retained. */
    @Deprecated public static boolean activate(ServerPlayerEntity p,io.github.mysticism.landmark.ProjectionFrame ignored){return activate(p);}
    public static void send(ServerPlayerEntity p,List<SpiritDeltaPayload.Added> additions,List<String> removals){
        thread(p);var c=connections.get(p.networkHandler);if(c==null||c.player!=p||c.session==null||!spirit(p))throw new IllegalStateException("No current semantic glyph session");
        if(!c.acknowledged)throw new IllegalStateException("Semantic session awaiting client acknowledgement");
        long next=Math.incrementExact(c.glyphSequence);for(var packet:SpiritDeltaPayload.batches(c.session,next,additions,removals))ServerPlayNetworking.send(p,packet);c.glyphSequence=next;
    }
    public static void sendTerrain(ServerPlayerEntity p,TerrainMeshFrame frame){thread(p);if(!spirit(p))return;try{activate(p);connections.get(p.networkHandler).pendingTerrain=frame;}catch(RuntimeException unavailable){var c=connections.get(p.networkHandler);String error=String.valueOf(unavailable.getMessage());if(c!=null&&!error.equals(c.error)){c.error=error;p.sendMessage(net.minecraft.text.Text.literal("[Spirit transport] "+error),true);}}}
    public static boolean clearRay(ServerPlayerEntity p,Vec3d from,Vec3d to){return meshes.clearRay(p,from,to);}
    private static Vec3d projected(ServerPlayerEntity viewer,Vec384f semantic){return Projection384f.projectToWorld(semantic,q(viewer),basis(viewer),viewer.getEyePos(),SCALE);}
    private static List<SpiritScenePayload.Peer> peers(ServerPlayerEntity viewer){
        var result=new ArrayList<SpiritScenePayload.Peer>();var current=q(viewer);int inspected=0;
        for(var p:viewer.getServer().getPlayerManager().getPlayerList()){
            if(++inspected>128)break;if(p==viewer||!spirit(p)||p.isRemoved())continue;var state=p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);if(!state.semanticReady()||!state.modelCompatible())continue;
            try{var v=q(p);if(v.squareDistance(current)>4||projected(viewer,v).squaredDistanceTo(viewer.getEyePos())>OBSERVER_RADIUS*OBSERVER_RADIUS)continue;result.add(new SpiritScenePayload.Peer(p.getUuid(),v,basis(p),navigation.deep(p),navigation.binding(p).orElse(null),p.getYaw(),p.getPitch(),appearance(viewer,p,"")));if(result.size()==SpiritScenePayload.MAX_PEERS)break;}catch(RuntimeException invalid){/* Invalid profile/unfinished source binding is not a fabricated peer. */}
        }
        return List.copyOf(result);
    }
    private static SpiritScenePayload.Ghost appearance(ServerPlayerEntity viewer,Entity e,String landmark){
        var tracked=e.getDataTracker().getChangedEntries();if(tracked==null)tracked=List.of();var equipment=new ArrayList<SpiritScenePayload.Equipment>();
        if(e instanceof LivingEntity living)for(var slot:EquipmentSlot.values())if(!living.getEquippedStack(slot).isEmpty())equipment.add(new SpiritScenePayload.Equipment(slot,living.getEquippedStack(slot)));
        var profile=e instanceof ServerPlayerEntity sourcePlayer?sourcePlayer.getGameProfile():null;
        var ghost=new SpiritScenePayload.Ghost(e.getUuid(),e.getId(),Registries.ENTITY_TYPE.getId(e.getType()).toString(),landmark,e.getPos(),e.getPose().name(),e.getYaw(),e.getPitch(),e.getBodyYaw(),e.getHeadYaw(),e.getWidth(),e.getHeight(),profile,tracked,equipment);
        var buffer=new RegistryByteBuf(Unpooled.buffer(256,8192),viewer.getServer().getRegistryManager());try{SpiritScenePayload.Ghost.CODEC.encode(buffer,ghost);}finally{buffer.release();}return ghost;
    }
    private static List<SpiritScenePayload.Ghost> ghosts(ServerPlayerEntity viewer,SpiritScenePayload.Binding binding){
        if(binding==null)return List.of();var world=viewer.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(binding.dimension())));if(world==null)return List.of();
        var entities=new ArrayList<Entity>();var box=new Box(binding.sourcePosition().add(-24,-24,-24),binding.sourcePosition().add(24,24,24));
        ((SourceEntityLookupAccessor)world).mysticism$entityLookup().forEachIntersects(TypeFilter.instanceOf(Entity.class),box,e->{entities.add(e);return entities.size()>=64?LazyIterationConsumer.NextIteration.ABORT:LazyIterationConsumer.NextIteration.CONTINUE;});
        entities.sort(Comparator.comparing(Entity::getUuid));var result=new ArrayList<SpiritScenePayload.Ghost>();
        for(var e:entities){if(e.isRemoved()||!e.isAlive())continue;try{
            result.add(appearance(viewer,e,binding.landmarkId()));if(result.size()==16)break;
        }catch(RuntimeException unavailable){/* Bounded/unsupported mod appearance is omitted, never a fabricated marker. */}}
        return List.copyOf(result);
    }
    private static void tick(MinecraftServer server){
        if(server.isStopping())return;var active=new ArrayList<Connection>();
        for(var c:connections.values())if(c.player!=null&&c.player.getServer()==server){if(!spirit(c.player)||c.player.networkHandler.player!=c.player||c.player.isRemoved()){c.invalidate();continue;}if(navigation.deep(c.player))SourceObservationTickets.release(c.player);active.add(c);}
        if(active.isEmpty())return;int sent=0;long tick=server.getTicks();
        for(int i=0;i<active.size()&&sent<4;i++){var c=active.get(Math.floorMod(cursor++,active.size()));if(tick-c.lastSendTick<5)continue;c.lastSendTick=tick;sent++;
            try{var p=c.player;var binding=navigation.binding(p).orElse(null);boolean observed=!navigation.deep(p)&&SourceObservationTickets.observe(p,binding);var peers=peers(p);
                var scene=new SpiritScenePayload(c.session,c.sceneSequence+1,navigation.deep(p),observed,binding,peers,observed?ghosts(p,binding):List.of(),List.of(),DroppedItemSemanticService.snapshots(p));
                var buffer=new RegistryByteBuf(Unpooled.buffer(1024,950_000),server.getRegistryManager());try{SpiritScenePayload.CODEC.encode(buffer,scene);}finally{buffer.release();}
                ServerPlayNetworking.send(p,scene);c.sceneSequence=scene.sequence();c.seenPeers=peers.stream().map(SpiritScenePayload.Peer::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
                if(c.acknowledged&&c.pendingTerrain!=null&&(c.sentTerrain==null||c.pendingTerrain.revision()!=c.sentTerrain.revision())){long next=c.terrainSequence+1;for(var packet:SpiritTerrainPayload.batches(c.session,next,c.pendingTerrain,c.sentTerrain))ServerPlayNetworking.send(p,packet);c.terrainSequence=next;c.sentTerrain=c.pendingTerrain;}
                c.error=null;
            }catch(RuntimeException unavailable){String error=unavailable.getMessage()==null?unavailable.getClass().getSimpleName():unavailable.getMessage();if(!error.equals(c.error)){c.error=error;c.player.sendMessage(net.minecraft.text.Text.literal("[Spirit transport] "+error),true);}}
        }
    }
    public static void acknowledge(ServerPlayerEntity actor,SpiritSessionAckPayload ack){thread(actor);var c=connections.get(actor.networkHandler);if(c!=null&&c.player==actor&&c.session!=null&&c.session.connection().equals(ack.connectionNonce())&&c.session.generation()==ack.epoch())c.acknowledged=true;}
    /** No actor UUID from the packet. No combat, no shallow effect. Both observer meshes are tested. */
    public static void touch(ServerPlayerEntity actor,UUID targetId){
        thread(actor);if(!spirit(actor)||!navigation.deep(actor))return;var actorState=actor.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);if(!actorState.semanticReady()||!actorState.modelCompatible())return;var target=actor.getServer().getPlayerManager().getPlayer(targetId);
        if(target==null||target==actor||!spirit(target)||target.getWorld()!=actor.getWorld()||!navigation.deep(target))return;
        var targetState=target.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);if(!targetState.semanticReady()||!targetState.modelCompatible())return;
        var a=connections.get(actor.networkHandler);var b=connections.get(target.networkHandler);if(a==null||b==null||a.player!=actor||b.player!=target||!a.seenPeers.contains(targetId)||!b.seenPeers.contains(actor.getUuid()))return;
        long tick=actor.getServer().getTicks();if(a.lastTouch>=0&&tick-a.lastTouch<10)return;
        try{var av=q(actor);var bv=q(target);if(av.squareDistance(bv)>4)return;var ab=basis(actor);var bb=basis(target);double alignment=(ab.i.cosine(bb.i)+ab.j.cosine(bb.j)+ab.k.cosine(bb.k))/3;
            Vec3d endA=projected(actor,bv),endB=projected(target,av);if(alignment<.95||endA.squaredDistanceTo(actor.getEyePos())>16||endB.squaredDistanceTo(target.getEyePos())>16)return;
            if(!meshes.clearRay(actor,actor.getEyePos(),endA)||!meshes.clearRay(target,target.getEyePos(),endB))return;
            a.lastTouch=tick;navigation.touch(actor,target);
        }catch(RuntimeException invalid){/* Authentication cannot be upgraded by invalid vectors/geometry. */}
    }
}
