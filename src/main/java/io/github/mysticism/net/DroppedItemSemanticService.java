package io.github.mysticism.net;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.mixin.SourceEntityLookupAccessor;
import io.github.mysticism.vector.*;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.function.LazyIterationConsumer;
import net.minecraft.util.math.*;
import java.util.*;

/** Vanilla entity/stack/delay/owner/inventory semantics retained; virtual position plus one collision proxy. */
public final class DroppedItemSemanticService {
    private static final String KEY="mysticism_semantic_drop";private static final int MAX_ACTIVE=128,MAX_OBSERVERS=128;
    private static boolean initialized;private static final Map<ItemEntity,State> states=new WeakHashMap<>();
    private record Observer(ServerPlayerEntity player,Vec3d head,float[] q,float[] i,float[] j,float[] k){}
    private static final class Observers {long tick=-1;boolean complete;final Map<ServerPlayerEntity,Observer> values=new IdentityHashMap<>();}
    private static final Map<net.minecraft.server.MinecraftServer,Observers> observers=new IdentityHashMap<>();
    private static Observers observers(net.minecraft.server.MinecraftServer server){
        var all=observers.computeIfAbsent(server,s->new Observers());if(all.tick==server.getTicks())return all;all.tick=server.getTicks();all.values.clear();all.complete=true;int n=0;
        for(var p:server.getPlayerManager().getPlayerList()){if(++n>MAX_OBSERVERS){all.complete=false;break;}if(!spirit(p)||p.isRemoved())continue;var nav=p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);if(!nav.semanticReady()||!nav.modelCompatible())continue;try{var v=q(p);var b=basis(p);EmbeddingSpace.requireCurrent(v);EmbeddingSpace.requireCurrent(b.i);EmbeddingSpace.requireCurrent(b.j);EmbeddingSpace.requireCurrent(b.k);all.values.put(p,new Observer(p,p.getEyePos(),v.data(),b.i.data(),b.j.data(),b.k.data()));}catch(RuntimeException invalid){all.complete=false;}}return all;
    }
    private static Vec3d project(Observer o,float[] semantic){double x=0,y=0,z=0;for(int n=0;n<semantic.length;n++){double d=(double)semantic[n]-o.q()[n];x+=d*o.i()[n];y+=d*o.j()[n];z+=d*o.k()[n];}return o.head().add(x*SpiritProjectionService.SCALE,y*SpiritProjectionService.SCALE,z*SpiritProjectionService.SCALE);}
    private static final class State {
        Vec384f q,target;Vec3d proxy;ServerPlayerEntity observer;final boolean originalGravity;int lifetime;String item;
        State(Vec384f q,Vec384f target,Vec3d proxy,boolean gravity,String item){this.q=q.clone();this.target=target.clone();this.proxy=proxy;originalGravity=gravity;this.item=item;}
    }
    private DroppedItemSemanticService(){}
    public static void init(){if(initialized)return;initialized=true;ServerLifecycleEvents.SERVER_STOPPING.register(server->{observers.remove(server);states.entrySet().removeIf(e->{if(e.getKey().getServer()!=server)return false;e.getKey().setNoGravity(e.getValue().originalGravity);return true;});});}
    private static boolean spirit(Entity e){return !e.getWorld().isClient&&e.getWorld().getRegistryKey().getValue().toString().equals("mysticism:spirit");}
    private static Vec384f q(ServerPlayerEntity p){return p.getComponent(MysticismEntityComponents.LATENT_POS).get();}
    private static Basis384f basis(ServerPlayerEntity p){return p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();}
    private static Vec384f lift(Basis384f b,Vec3d v){return b.i.clone().mul((float)v.x).add(b.j.clone().mul((float)v.y)).add(b.k.clone().mul((float)v.z));}
    private static Vec3d project(ServerPlayerEntity p,Vec384f semantic){var o=observers(p.getServer()).values.get(p);if(o==null)throw new IllegalStateException("Observer outside item snapshot budget");return project(o,semantic.data());}
    private static ServerPlayerEntity initial(ItemEntity entity){
        if(entity.getOwner() instanceof ServerPlayerEntity owner&&spirit(owner))return owner;
        int n=0;ServerPlayerEntity nearest=null;double distance=256;
        for(var p:entity.getServer().getPlayerManager().getPlayerList()){if(++n>MAX_OBSERVERS)break;if(!spirit(p))continue;double d=p.squaredDistanceTo(entity);if(d<distance){distance=d;nearest=p;}}return nearest;
    }
    private static State track(ItemEntity entity){
        if(states.size()>=MAX_ACTIVE)return null;var owner=initial(entity);if(owner==null)return null;var nav=owner.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);if(!nav.semanticReady()||!nav.modelCompatible())return null;
        String item=Registries.ITEM.getId(entity.getStack().getItem()).toString();var target=ItemEmbeddingIndexState.get(entity.getServer()).getVec(item);if(target==null||target.length()==0)return null;
        try{EmbeddingSpace.requireCurrent(target);var latent=q(owner).clone().add(lift(basis(owner),entity.getPos().subtract(owner.getEyePos()).multiply(1/SpiritProjectionService.SCALE)));var s=new State(latent,target,entity.getPos(),entity.hasNoGravity(),item);s.observer=owner;states.put(entity,s);entity.setNoGravity(true);return s;}catch(RuntimeException unavailable){return null;}
    }
    private static boolean choose(ItemEntity entity,State s){
        var all=observers(entity.getServer());ServerPlayerEntity best=null;double distance=Double.POSITIVE_INFINITY;float[] semantic=s.q.data();
        for(var o:all.values.values()){double d=project(o,semantic).squaredDistanceTo(o.head());if(d<=SpiritProjectionService.OBSERVER_RADIUS*SpiritProjectionService.OBSERVER_RADIUS&&(d<distance||d==distance&&best!=null&&o.player().getUuid().compareTo(best.getUuid())<0)){distance=d;best=o.player();}}
        s.observer=best;return best!=null||!all.complete;
    }
    private static Vec3d proxy(ItemEntity e,Vec3d desired){return new Vec3d(desired.x,MathHelper.clamp(desired.y,e.getWorld().getBottomY()+1,e.getWorld().getTopY()-1),desired.z);}
    public static void prepare(ItemEntity entity){if(states.containsKey(entity)&&spirit(entity)){entity.setNoGravity(true);entity.setPosition(proxy(entity,entity.getPos()));}}
    /** Called at ItemEntity.tick TAIL. No model IO or world scan; staggered <=128-player observation. */
    public static void tick(ItemEntity entity){
        if(!initialized||entity.getWorld().isClient)return;
        var s=states.get(entity);if(!spirit(entity)||entity.isRemoved()||entity.getStack().isEmpty()){if(s!=null){states.remove(entity);entity.setNoGravity(s.originalGravity);}return;}
        if(s==null)s=track(entity);if(s==null)return;
        entity.setNoGravity(true);if(++s.lifetime>=6000){states.remove(entity);entity.discard();return;}
        try{
            if(s.observer==null||!spirit(s.observer)||s.observer.isRemoved()||Math.floorMod(entity.getServer().getTicks()+entity.getId(),10)==0){if(!choose(entity,s)){states.remove(entity);entity.discard();return;}}
            var observer=s.observer;if(observer==null)return; // Observation budget overflow defers expiry, never falsely deletes.
            if(Math.floorMod(entity.getServer().getTicks()+entity.getId(),20)==0){String id=Registries.ITEM.getId(entity.getStack().getItem()).toString();var target=ItemEmbeddingIndexState.get(entity.getServer()).getVec(id);if(target!=null){s.target=target;s.item=id;}}
            // Honor vanilla/mod velocity and direct proxy displacement, bounded to 8 blocks/tick.
            Vec3d external=entity.getPos().subtract(s.proxy);if(external.lengthSquared()>64)external=external.normalize().multiply(8);
            var candidate=s.q.clone().add(lift(basis(observer),external.multiply(1/SpiritProjectionService.SCALE))).converge(s.target,.015f);
            Vec3d next=project(observer,candidate);Vec3d before=project(observer,s.q);
            if(SpiritProjectionService.clearRay(observer,before,next)){s.q=candidate;entity.setPosition(proxy(entity,next));}else entity.setPosition(proxy(entity,before));
            s.proxy=entity.getPos();entity.setVelocity(entity.getVelocity().multiply(.8));
            if(Math.floorMod(entity.getServer().getTicks()+entity.getId(),5)==0){float[] semantic=s.q.data();for(var o:observers(entity.getServer()).values.values()){if(project(o,semantic).squaredDistanceTo(o.head())<=4)entity.onPlayerCollision(o.player());if(entity.isRemoved()){states.remove(entity);break;}}}
        }catch(RuntimeException unavailable){/* Keep recoverable vanilla object rather than discard on profile/mesh failure. */}
    }
    public static boolean mayPickup(ItemEntity entity,net.minecraft.entity.player.PlayerEntity player){
        var s=states.get(entity);if(s==null)return true;if(!(player instanceof ServerPlayerEntity p)||!spirit(p))return false;
        try{return project(p,s.q).squaredDistanceTo(p.getEyePos())<=4;}catch(RuntimeException invalid){return false;}
    }
    public static List<SpiritScenePayload.Drop> snapshots(ServerPlayerEntity viewer){
        var result=new ArrayList<SpiritScenePayload.Drop>();var indexed=new HashSet<UUID>();for(var item:states.keySet())indexed.add(item.getUuid());
        for(var e:states.entrySet()){var item=e.getKey();var s=e.getValue();if(item.getWorld()!=viewer.getWorld()||item.isRemoved())continue;try{if(project(viewer,s.q).squaredDistanceTo(viewer.getEyePos())>SpiritProjectionService.OBSERVER_RADIUS*SpiritProjectionService.OBSERVER_RADIUS)continue;result.add(new SpiritScenePayload.Drop(item.getUuid(),item.getId(),s.q,s.target,item.getPos(),item.getStack()));if(result.size()==SpiritScenePayload.MAX_DROPS)return List.copyOf(result);}catch(RuntimeException invalid){}}
        // Unindexed/over-budget drops stay visibly recoverable vanilla proxies, explicitly non-semantic.
        var box=viewer.getBoundingBox().expand(SpiritProjectionService.OBSERVER_RADIUS);int[] inspected={0};
        ((SourceEntityLookupAccessor)(ServerWorld)viewer.getWorld()).mysticism$entityLookup().forEachIntersects(TypeFilter.instanceOf(ItemEntity.class),box,item->{
            if(!item.isRemoved()&&!indexed.contains(item.getUuid())&&!item.getStack().isEmpty()&&result.size()<SpiritScenePayload.MAX_DROPS){try{var virtual=q(viewer).clone().add(lift(basis(viewer),item.getPos().subtract(viewer.getEyePos()).multiply(1/SpiritProjectionService.SCALE)));result.add(new SpiritScenePayload.Drop(item.getUuid(),item.getId(),virtual,null,item.getPos(),item.getStack()));}catch(RuntimeException invalid){}}
            return ++inspected[0]>=64||result.size()>=SpiritScenePayload.MAX_DROPS?LazyIterationConsumer.NextIteration.ABORT:LazyIterationConsumer.NextIteration.CONTINUE;
        });return List.copyOf(result);
    }
    public static void write(ItemEntity entity,NbtCompound root){var s=states.get(entity);if(s==null)return;var tag=new NbtCompound();EmbeddingNbt.stamp(tag);tag.putIntArray("q",s.q.toBits());tag.putIntArray("target",s.target.toBits());tag.putInt("lifetime",s.lifetime);tag.putBoolean("originalGravity",s.originalGravity);root.put(KEY,tag);}
    public static void read(ItemEntity entity,NbtCompound root){
        if(!root.contains(KEY))return;var tag=root.getCompound(KEY);boolean gravity=tag.getBoolean("originalGravity");entity.setNoGravity(gravity);
        if(!spirit(entity)||!EmbeddingNbt.compatible(tag)||states.size()>=MAX_ACTIVE)return;
        try{var s=new State(Vec384f.fromBits(tag.getIntArray("q")),Vec384f.fromBits(tag.getIntArray("target")),entity.getPos(),gravity,Registries.ITEM.getId(entity.getStack().getItem()).toString());s.lifetime=Math.max(0,Math.min(6000,tag.getInt("lifetime")));states.put(entity,s);entity.setNoGravity(true);}catch(RuntimeException discarded){/* Old vectors discarded; full original stack and vanilla gravity survive. */}
    }
}
