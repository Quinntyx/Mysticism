package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Leaves vanilla walk/jump/flight controls untouched. Supported geometry pins the semantic neighborhood. */
public final class SpiritBasisEvolver {
    private static final Map<MinecraftServer,Map<UUID,Vec3d>> LAST_POS=new IdentityHashMap<>();
    private static boolean initialized;
    private SpiritBasisEvolver(){}
    private static void evolve(MinecraftServer server){
        Map<UUID,Vec3d> positions=LAST_POS.computeIfAbsent(server,k->new HashMap<>());
        Set<UUID> live=new HashSet<>();int processed=0;
        for(var p:server.getPlayerManager().getPlayerList()){
            if(!p.getWorld().getRegistryKey().getValue().equals(Identifier.of("mysticism","spirit"))){positions.remove(p.getUuid());continue;}
            if(processed++>=64){positions.remove(p.getUuid());continue;}
            live.add(p.getUuid());Vec3d now=p.getPos(),last=positions.put(p.getUuid(),now);
            var att=p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);
            Vec3d delta=last==null?Vec3d.ZERO:now.subtract(last);
            // Teleports are not travel. They neither rotate projection nor inject giant semantic steps.
            if(delta.lengthSquared()>16)delta=Vec3d.ZERO;
            var basis=p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
            var support=!p.getAbilities().flying?SpiritTerrainService.support(p):Optional.<SpiritTerrainService.Support>empty();
            if(support.isPresent()){
                var floor=support.get();var n=floor.normal();
                var orbit=now.subtract(floor.center());
                att.steer(TraversalSteering.supported(floor.embedding(),basis,orbit.x,orbit.y,orbit.z,n.x,n.y,n.z),0.015);
            }else att.steer(att.target(),0.005);
            // Fixed basis: changing it under a collision-pinned floor would destroy ground continuity.
            var latent=p.getComponent(MysticismEntityComponents.LATENT_POS).get();
            latent.converge(att.get(),(float)Math.min(0.02,delta.length()*0.01));
            if(server.getTicks()%40==0){MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p);MysticismEntityComponents.LATENT_POS.sync(p);}
        }
        positions.keySet().retainAll(live);
    }
    public static void init(){
        if(initialized)return;initialized=true;
        ServerTickEvents.END_SERVER_TICK.register(SpiritBasisEvolver::evolve);
        ServerLifecycleEvents.SERVER_STOPPING.register(LAST_POS::remove);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player,origin,destination)->{var map=LAST_POS.get(destination.getServer());if(map!=null)map.remove(player.getUuid());});
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer,newPlayer,alive)->{var map=LAST_POS.get(newPlayer.getServer());if(map!=null)map.remove(newPlayer.getUuid());});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var map=LAST_POS.get(server);if(map!=null)map.remove(handler.player.getUuid());});
    }
}
